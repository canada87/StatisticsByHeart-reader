package app.sbh.reader.sync

import app.sbh.reader.fakes.FakeHealthRepository
import app.sbh.reader.fakes.FakeSbhApi
import app.sbh.reader.fakes.InMemorySyncStore
import app.sbh.reader.fakes.TestRecords.steps
import app.sbh.reader.fakes.TestRecords.weight
import app.sbh.reader.health.SdkStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

class SyncEngineTest {
    private val now = Instant.parse("2026-10-14T12:00:00Z")
    private val base = Instant.parse("2026-09-20T08:00:00Z")

    private val health = FakeHealthRepository()
    private val store = InMemorySyncStore()
    private val api = FakeSbhApi()
    private var enabled = setOf("Steps")
    private var configured = true
    private var ids = 0

    private val settings = object : SyncSettings {
        override suspend fun enabledTypeIds() = enabled
        override suspend fun isConfigured() = configured
    }

    private fun engine(limits: SyncLimits = SyncLimits()) = SyncEngine(
        health, store, api, settings,
        clock = { now }, zone = { ZoneOffset.UTC }, newId = { "sync-${++ids}" }, limits = limits,
    )

    private fun seedDailySteps(n: Int) = repeat(n) { health.seed(steps("s$it", base.plus(it.toLong(), ChronoUnit.DAYS))) }

    private fun recordCalls() = api.calls.count { it.startsWith("records:") }

    // ---------------------------------------------------------------- backfill

    @Test
    fun firstRunBackfillsEverythingAndStoresTheChangesToken() = runTest {
        seedDailySteps(20)

        val result = engine().run()

        assertEquals(SyncStatus.COMPLETED, result.status)
        assertEquals(SyncMode.BACKFILL, result.mode)
        assertEquals(20, api.serverRecords.size)
        assertTrue(store.outbox.isEmpty())
        assertNotNull(store.token)
        assertTrue(store.progress.getValue("Steps").backfillComplete)
        assertEquals(20L, store.progress.getValue("Steps").sentCount)
        assertEquals(20, result.counts["records_sent"])
        assertTrue("official daily aggregates are sent", api.aggregates.isNotEmpty())
        assertEquals(1, api.reports.size)
        assertEquals("backfill", api.reports.single().mode)
        assertEquals("completed", api.reports.single().status)
    }

    @Test
    fun backfillReadsInSevenDayWindows() = runTest {
        seedDailySteps(20)
        engine().run()
        // 20 days of daily records / 7-day windows = 3 record batches
        assertEquals(3, recordCalls())
    }

    @Test
    fun backfillWithNoDataCompletesImmediately() = runTest {
        val result = engine().run()
        assertEquals(SyncStatus.COMPLETED, result.status)
        assertTrue(store.progress.getValue("Steps").backfillComplete)
        assertEquals(0, recordCalls())
    }

    @Test
    fun backfillResumesAfterATransientFailureWithoutResendingDeliveredBatches() = runTest {
        seedDailySteps(20)
        // ingest call #1 = records of window 1, #2 = aggregates of window 1 -> network down
        api.failures[2] = FakeSbhApi.networkDown

        val first = engine().run()
        assertEquals(SyncStatus.FAILED, first.status)
        assertEquals(FailureReason.NETWORK, first.failure)
        assertTrue(first.shouldRetry)
        assertTrue("undelivered work stays in the outbox", store.outbox.isNotEmpty())
        assertNotNull("cursor was persisted before the failure", store.progress.getValue("Steps").backfillCursor)

        val second = engine().run()
        assertEquals(SyncStatus.COMPLETED, second.status)
        assertEquals(20, api.serverRecords.size)
        assertEquals("no batch is posted twice", 3, recordCalls())
        assertTrue(store.outbox.isEmpty())
    }

    @Test
    fun serverErrorKeepsDataQueuedAndAsksForRetry() = runTest {
        seedDailySteps(5)
        api.failures[1] = FakeSbhApi.serverError

        val first = engine().run()
        assertEquals(FailureReason.SERVER, first.failure)
        assertTrue(first.shouldRetry)
        assertTrue(store.outbox.isNotEmpty())

        val second = engine().run()
        assertEquals(SyncStatus.COMPLETED, second.status)
        assertEquals(5, api.serverRecords.size)
    }

    @Test
    fun authFailureStopsTheRunAndDoesNotRetryInALoop() = runTest {
        seedDailySteps(3)
        api.failures[1] = FakeSbhApi.unauthorized

        val result = engine().run()

        assertEquals(FailureReason.AUTH, result.failure)
        assertFalse(result.shouldRetry)
        assertTrue(api.serverRecords.isEmpty())
        assertTrue(store.outbox.isNotEmpty())
        assertEquals(SyncStatus.FAILED, store.logList.single().status)
        assertTrue("failure:auth" in store.logList.single().errors)
    }

    @Test
    fun oversizedBatchesAreSplitInHalvesOn413() = runTest {
        repeat(10) { health.seed(steps("m$it", base.plus(it * 20L, ChronoUnit.MINUTES), minutes = 5)) }
        api.maxBatch = 3

        val result = engine().run()

        assertEquals(SyncStatus.COMPLETED, result.status)
        assertEquals(10, api.serverRecords.size)
    }

    @Test
    fun batchesAreCappedAtMaxBatchRecords() = runTest {
        repeat(10) { health.seed(steps("m$it", base.plus(it * 20L, ChronoUnit.MINUTES), minutes = 5)) }
        engine(SyncLimits(maxBatchRecords = 4)).run()
        assertEquals(listOf(4, 4, 2), api.calls.filter { it.startsWith("records:") }.map { it.substringAfterLast(':').toInt() })
    }

    @Test
    fun rejectedRecordsAreCountedAndNeverRetried() = runTest {
        seedDailySteps(5)
        api.rejectHcIds += "s2"

        val result = engine().run()

        assertEquals(SyncStatus.PARTIAL, result.status)
        assertEquals(1, result.counts["records_rejected"])
        assertEquals(4, result.counts["records_sent"])
        assertTrue(store.outbox.isEmpty())
        assertEquals(4, api.serverRecords.size)
    }

    @Test
    fun aTypeThatCannotBeReadDoesNotAbortTheOthersAndIsRetriedLater() = runTest {
        enabled = setOf("Steps", "Weight")
        seedDailySteps(3)
        health.seed(weight("w1", base))
        health.failReadFor += "Weight"

        val first = engine().run()
        assertEquals(SyncStatus.PARTIAL, first.status)
        assertTrue(first.counts["records_sent"]!! >= 3)
        assertTrue("type_read_failed:Weight=1" in store.logList.single().errors)
        assertFalse(store.progress["Weight"]?.backfillComplete ?: false)

        health.failReadFor.clear()
        val second = engine().run()
        assertEquals(SyncStatus.COMPLETED, second.status)
        assertTrue(api.serverRecords.containsKey("Weight" to "w1"))
        assertTrue(store.progress.getValue("Weight").backfillComplete)
    }

    // ---------------------------------------------------------------- incremental (Changes API)

    @Test
    fun incrementalRunSendsUpsertsThenDeletionsInOrder() = runTest {
        seedDailySteps(3)
        engine().run()
        api.calls.clear()

        health.insert(steps("new", base.plus(30, ChronoUnit.DAYS)))
        health.insert(steps("s0", base, count = 999, modified = Instant.parse("2026-10-01T00:00:00Z"))) // edited
        health.delete("s1")

        val result = engine().run()

        assertEquals(SyncMode.INCREMENTAL, result.mode)
        assertEquals(SyncStatus.COMPLETED, result.status)
        assertTrue(api.serverRecords.containsKey("Steps" to "new"))
        assertEquals("2026-10-01T00:00:00Z", api.serverRecords["Steps" to "s0"])
        assertTrue("s1" in api.deleted)
        val order = api.calls.filter { it.startsWith("records") || it.startsWith("deletions") }
        assertEquals(listOf("records:Steps:2", "deletions:1"), order)
    }

    @Test
    fun noChangesMeansNothingButAggregatesIsSent() = runTest {
        seedDailySteps(3)
        engine().run()
        api.calls.clear()

        val result = engine().run()

        assertEquals(SyncStatus.COMPLETED, result.status)
        assertEquals(0, recordCalls())
        assertTrue(api.calls.none { it.startsWith("deletions") })
    }

    @Test
    fun changesArePagedAndTheTokenAdvancesToTheEnd() = runTest {
        engine().run()
        health.changesPageSize = 2
        repeat(5) { health.insert(steps("n$it", base.plus(it.toLong(), ChronoUnit.HOURS))) }

        engine().run()
        assertEquals(5, api.serverRecords.size)

        api.calls.clear()
        engine().run()
        assertEquals("token was persisted at the end of the change log", 0, recordCalls())
    }

    @Test
    fun expiredChangesTokenTriggersAFullReconciliationBackfill() = runTest {
        seedDailySteps(5)
        engine().run()
        val oldToken = store.token!!.token
        health.expiredTokens += oldToken
        val sentBefore = api.recordPostCount

        val result = engine().run()

        assertEquals(SyncMode.BACKFILL, result.mode)
        assertTrue("token_expired=1" in store.logList.last().errors)
        assertNotEquals(oldToken, store.token!!.token)
        assertEquals(2, health.tokensCreated)
        assertTrue("history is re-read", api.recordPostCount > sentBefore)
        assertEquals("idempotent upserts: still 5 records on the server", 5, api.serverRecords.size)
        assertTrue(store.progress.getValue("Steps").backfillComplete)
    }

    @Test
    fun enablingANewTypeBackfillsItAndRenewsTheToken() = runTest {
        seedDailySteps(3)
        engine().run()

        health.insert(weight("w1", base)) // not covered by the old token (Steps only)
        enabled = setOf("Steps", "Weight")
        engine().run()

        assertTrue(api.serverRecords.containsKey("Weight" to "w1"))
        assertEquals("Steps,Weight", store.token!!.typesKey)
        assertTrue(store.progress.getValue("Weight").backfillComplete)
    }

    @Test
    fun aDisabledTypeIsNotReadAtAll() = runTest {
        enabled = setOf("Steps")
        health.seed(weight("w1", base))
        engine().run()
        assertTrue(api.serverRecords.keys.none { it.first == "Weight" })
    }

    // ---------------------------------------------------------------- preconditions

    @Test
    fun missingPermissionsFailWithAClearReasonAndNoRetry() = runTest {
        health.granted.clear()
        val result = engine().run()
        assertEquals(FailureReason.PERMISSIONS_MISSING, result.failure)
        assertFalse(result.shouldRetry)
        assertEquals(SyncStatus.FAILED, store.logList.single().status)
    }

    @Test
    fun healthConnectNotInstalledFailsWithAClearReason() = runTest {
        health.sdk = SdkStatus.NOT_INSTALLED
        val result = engine().run()
        assertEquals(FailureReason.HC_UNAVAILABLE, result.failure)
    }

    @Test
    fun unconfiguredServerFailsWithoutReadingHealthConnectOrTouchingTheNetwork() = runTest {
        configured = false
        seedDailySteps(2)
        val result = engine().run()
        assertEquals(FailureReason.NOT_CONFIGURED, result.failure)
        assertTrue(api.calls.isEmpty())
        assertNull(store.token)
    }

    // ---------------------------------------------------------------- privacy

    @Test
    fun logAndReportCarryOnlyCountsAndTechnicalCodes() = runTest {
        seedDailySteps(3)
        api.rejectHcIds += "s1"
        engine().run()
        val log = store.logList.single()
        assertTrue(log.counts.keys.all { it.matches(Regex("[a-z_]+")) })
        assertTrue(log.errors.all { it.matches(Regex("""[A-Za-z_]+(:[A-Za-z0-9_]+)?(=\d+)?""")) })
        val report = api.reports.single()
        assertTrue(report.errors.all { it.message == null })
    }
}
