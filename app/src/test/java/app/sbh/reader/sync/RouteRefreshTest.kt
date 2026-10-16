package app.sbh.reader.sync

import app.sbh.reader.fakes.FakeHealthRepository
import app.sbh.reader.fakes.FakeSbhApi
import app.sbh.reader.fakes.InMemorySyncStore
import app.sbh.reader.fakes.TestRecords.exercise
import app.sbh.reader.fakes.TestRecords.steps
import app.sbh.reader.health.HealthTypes
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/** Workout GPS routes: inclusion in the normal sync and the resumable "re-download routes" job. Synthetic data only. */
class RouteRefreshTest {
    private val now = Instant.parse("2026-10-14T12:00:00Z")
    private val base = Instant.parse("2026-09-20T08:00:00Z")

    private val health = FakeHealthRepository()
    private val store = InMemorySyncStore()
    private val api = FakeSbhApi()
    private val enabled = setOf("ExerciseSession", "Steps")
    private var ids = 0

    private val settings = object : SyncSettings {
        override suspend fun enabledTypeIds() = enabled
        override suspend fun isConfigured() = true
    }

    private fun engine(limits: SyncLimits = SyncLimits()) = SyncEngine(
        health, store, api, settings,
        clock = { now }, zone = { ZoneOffset.UTC }, newId = { "sync-${++ids}" }, limits = limits,
    )

    private fun day(n: Long) = base.plus(n, ChronoUnit.DAYS)

    private fun routeOf(record: JsonObject): JsonElement? = record["data"]!!.jsonObject["route"]

    private fun hcId(record: JsonObject) = record["hc_id"]!!.jsonPrimitive.content

    private fun postedExercises(): List<JsonObject> = api.postedBatches
        .flatMap { batch -> batch.map { it.jsonObject } }
        .filter { it["type"]!!.jsonPrimitive.content == "ExerciseSession" }

    // ---------------------------------------------------------------- normal sync

    @Test
    fun withTheRoutesPermissionTheSyncSendsTheRouteOfWorkouts() = runTest {
        health.granted += HealthTypes.ROUTES_PERMISSION
        health.seed(exercise("ex-1", day(0), routePoints = 4))

        engine().run()

        assertEquals(4, routeOf(postedExercises().single())!!.jsonArray.size)
    }

    @Test
    fun withoutThePermissionTheSyncSendsWorkoutsWithoutRouteAndWithoutErrors() = runTest {
        health.seed(exercise("ex-1", day(0), routePoints = 4))

        val result = engine().run()

        assertEquals(SyncStatus.COMPLETED, result.status)
        assertNull(routeOf(postedExercises().single()))
        assertEquals(1, result.counts["records_sent"])
    }

    @Test
    fun modifiedWorkoutsCarryTheirRouteThroughTheChangesApi() = runTest {
        health.granted += HealthTypes.ROUTES_PERMISSION
        engine().run() // backfill of an empty store creates the Changes token
        health.insert(exercise("ex-9", day(1), routePoints = 3))

        engine().run()

        assertEquals(3, routeOf(postedExercises().single())!!.jsonArray.size)
    }

    // ---------------------------------------------------------------- refresh

    @Test
    fun refreshSendsOnlyWorkoutsThatNowHaveARoute() = runTest {
        health.granted += HealthTypes.ROUTES_PERMISSION
        health.seed(exercise("with-1", day(0), routePoints = 3))
        health.seed(exercise("none", day(1), routePoints = 0))
        health.seed(exercise("with-2", day(2), routePoints = 2))
        health.seed(steps("st-1", day(2)))

        val result = engine().refreshRoutes()

        assertEquals(SyncStatus.COMPLETED, result.status)
        assertEquals(SyncMode.ROUTES, result.mode)
        assertEquals(setOf("with-1", "with-2"), postedExercises().map { hcId(it) }.toSet())
        assertTrue(postedExercises().all { routeOf(it) != null })
        assertTrue("other types are not touched", api.postedBatches.flatten().none { it.jsonObject["type"]!!.jsonPrimitive.content == "Steps" })
        assertEquals(2, result.counts["routes_sent"])
        assertEquals(2, result.counts["routes_queued"])
        assertTrue(store.routeState!!.complete)
        assertTrue(store.outbox.isEmpty())
    }

    @Test
    fun refreshDoesNotTouchNormalSyncCursorsTokenOrSentStats() = runTest {
        health.granted += HealthTypes.ROUTES_PERMISSION
        health.seed(exercise("ex-1", day(0), routePoints = 3))

        engine().refreshRoutes()

        assertNull(store.token)
        assertTrue(store.progress.isEmpty())
        assertTrue("the job is logged locally but not reported to the writer", api.reports.isEmpty())
        assertEquals(SyncMode.ROUTES, store.logList.single().mode)
    }

    @Test
    fun refreshWithoutThePermissionFailsClearlyAndSendsNothing() = runTest {
        health.seed(exercise("ex-1", day(0), routePoints = 3))

        val result = engine().refreshRoutes()

        assertEquals(SyncStatus.FAILED, result.status)
        assertEquals(FailureReason.PERMISSIONS_MISSING, result.failure)
        assertFalse(result.shouldRetry)
        assertTrue(api.postedBatches.isEmpty())
        assertTrue(store.logList.single().errors.contains("routes_not_granted=1"))
    }

    @Test
    fun refreshResumesFromItsCursorAfterAnInterruption() = runTest {
        health.granted += HealthTypes.ROUTES_PERMISSION
        // three 7-day windows: day 0, day 10, day 20
        listOf(0L, 10L, 20L).forEachIndexed { i, d -> health.seed(exercise("ex-$i", day(d), routePoints = 2)) }
        // window 1 is delivered (ingest call 1), window 2 hits a server error (call 2)
        api.failures[2] = FakeSbhApi.serverError

        val first = engine().refreshRoutes()

        assertEquals(FailureReason.SERVER, first.failure)
        assertTrue(first.shouldRetry)
        val interrupted = store.routeState!!
        assertFalse(interrupted.complete)
        assertNotNull(interrupted.cursor)
        assertEquals(setOf("ex-0"), api.serverRecords.keys.map { it.second }.toSet())

        val second = engine().refreshRoutes()

        assertEquals(SyncStatus.COMPLETED, second.status)
        assertEquals(setOf("ex-0", "ex-1", "ex-2"), api.serverRecords.keys.map { it.second }.toSet())
        assertEquals("ex-0 is not re-read once the cursor passed it", 1, postedExercises().count { hcId(it) == "ex-0" })
        assertTrue(store.routeState!!.complete)
    }

    @Test
    fun aCompletedRefreshDoesNothingUntilItIsStartedAgain() = runTest {
        health.granted += HealthTypes.ROUTES_PERMISSION
        health.seed(exercise("ex-1", day(0), routePoints = 3))
        engine().refreshRoutes()
        val posts = api.recordPostCount

        engine().refreshRoutes()
        assertEquals(posts, api.recordPostCount)

        store.routeState = RouteRefreshState() // what the "Riscarica tracce GPS" action does
        engine().refreshRoutes()
        assertEquals(posts + 1, api.recordPostCount)
    }

    @Test
    fun refreshBatchesStayBelowTheRouteLimitAndAreSplitOn413() = runTest {
        health.granted += HealthTypes.ROUTES_PERMISSION
        repeat(7) { health.seed(exercise("ex-$it", day(0).plusSeconds(it * 3600L), routePoints = 2)) }
        val limits = SyncLimits(routePageSize = 4, maxRouteRecordsPerBatch = 3)

        engine(limits).refreshRoutes()

        assertTrue("no batch above the route limit", api.postedBatches.all { it.size <= 3 })
        assertEquals(7, postedExercises().size)

        // a server that only takes single sessions: the existing split on 413 still delivers everything
        store.routeState = RouteRefreshState()
        api.maxBatch = 1
        val before = postedExercises().size
        val result = engine(limits).refreshRoutes()

        assertEquals(SyncStatus.COMPLETED, result.status)
        assertEquals(before + 7, postedExercises().size)
    }

    @Test
    fun refreshSkipsWorkoutsWhoseRouteIsStillNotReadable() = runTest {
        health.granted += HealthTypes.ROUTES_PERMISSION
        health.seed(exercise("ex-1", day(0), routePoints = null))
        health.seed(exercise("ex-2", day(1), routePoints = 2))

        engine().refreshRoutes()

        assertEquals(listOf("ex-2"), postedExercises().map { hcId(it) })
    }
}
