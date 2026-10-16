package app.sbh.reader.sync

import androidx.health.connect.client.records.Record
import app.sbh.reader.health.AggregateKind
import app.sbh.reader.health.ChangeItem
import app.sbh.reader.health.DailyAggregate
import app.sbh.reader.health.HealthRepository
import app.sbh.reader.health.HealthTypeSpec
import app.sbh.reader.health.HealthTypes
import app.sbh.reader.health.OutRecord
import app.sbh.reader.health.SdkStatus
import app.sbh.reader.net.ApiResult
import app.sbh.reader.net.IngestResult
import app.sbh.reader.net.NetworkErrorKind
import app.sbh.reader.net.ReportError
import app.sbh.reader.net.SbhApi
import app.sbh.reader.net.SyncReportBody
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** What the sync needs to know from the app settings. */
interface SyncSettings {
    suspend fun enabledTypeIds(): Set<String>
    suspend fun isConfigured(): Boolean
}

enum class FailureReason(val retry: Boolean) {
    NOT_CONFIGURED(false),
    CONFIG_INVALID(false),
    HC_UNAVAILABLE(false),
    PERMISSIONS_MISSING(false),
    AUTH(false),
    NETWORK(true),
    SERVER(true),
    HC_ERROR(true),
}

data class SyncResult(
    val syncId: String,
    val mode: SyncMode,
    val status: SyncStatus,
    val failure: FailureReason?,
    val counts: Map<String, Int>,
) {
    /** WorkManager should re-run with backoff only for transient problems. */
    val shouldRetry: Boolean get() = failure?.retry == true
}

enum class SyncPhase { PREPARING, CHANGES, BACKFILL, AGGREGATES, UPLOADING, ROUTES }

data class SyncProgress(val phase: SyncPhase, val typeId: String? = null, val recordsDelivered: Int = 0)

data class SyncLimits(
    val maxBatchRecords: Int = 500,
    /** Close a batch when its JSON reaches this size (contract recommends ~5 MiB). */
    val softBatchBytes: Int = 4_000_000,
    val backfillWindow: Duration = Duration.ofDays(7),
    val scalarPageSize: Int = 1000,
    val seriesPageSize: Int = 200,
    val recentAggregateDays: Int = 2,
    val maxAttempts: Int = 10,
    /** A route can weigh megabytes: the GPS refresh reads few sessions per page and posts few per batch. */
    val routePageSize: Int = 10,
    val maxRouteRecordsPerBatch: Int = 5,
)

/**
 * The sync use case (PROJECT_GUIDE §3.1/§5.2/§6): backfill by windows, incremental sync with the Changes API
 * (deletions included, expired token handled), everything staged through the outbox, then delivered FIFO.
 * Never logs or reports health values: only counts, type ids and technical codes.
 */
class SyncEngine(
    private val health: HealthRepository,
    private val store: SyncStore,
    private val api: SbhApi,
    private val settings: SyncSettings,
    private val clock: () -> Instant = Instant::now,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val limits: SyncLimits = SyncLimits(),
) {
    private class Run(val syncId: String, val startedAt: Instant) {
        var mode = SyncMode.INCREMENTAL
        var recordsDelivered = 0
        var recordsRejected = 0
        var deletionsSent = 0
        var aggregatesSent = 0
        var routesSent = 0
        var routesQueued = 0
        var batches = 0
        val errors = linkedMapOf<String, Int>()
        var onProgress: (SyncProgress) -> Unit = {}

        fun error(code: String, type: String? = null) {
            val key = if (type == null) code else "$code:$type"
            errors[key] = (errors[key] ?: 0) + 1
        }
    }

    private companion object {
        val runLock = Mutex()
    }

    private class Stop(val reason: FailureReason) : Exception(null, null, false, false)

    /** Runs one sync. Concurrent calls (periodic + "sync now") are serialized. */
    suspend fun run(onProgress: (SyncProgress) -> Unit = {}): SyncResult =
        runLock.withLock { runLocked(SyncMode.INCREMENTAL, onProgress) { execute(it) } }

    /**
     * Re-reads the workouts of the available history and re-queues only those that now carry a GPS route
     * (needs READ_EXERCISE_ROUTES). Resumable through its own cursor; shares the lock with [run] but no cursor,
     * token or per-type stat of the normal sync.
     */
    suspend fun refreshRoutes(onProgress: (SyncProgress) -> Unit = {}): SyncResult =
        runLock.withLock { runLocked(SyncMode.ROUTES, onProgress) { executeRoutes(it) } }

    private suspend fun runLocked(mode: SyncMode, onProgress: (SyncProgress) -> Unit, body: suspend (Run) -> Unit): SyncResult {
        val run = Run(newId(), clock()).also { it.onProgress = onProgress; it.mode = mode }
        onProgress(SyncProgress(SyncPhase.PREPARING))
        var failure: FailureReason? = null
        try {
            body(run)
        } catch (e: Stop) {
            failure = e.reason
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            run.error("permission_denied")
            failure = FailureReason.PERMISSIONS_MISSING
        } catch (e: Exception) {
            run.error("unexpected_${e.javaClass.simpleName}")
            failure = FailureReason.HC_ERROR
        }
        return finish(run, failure)
    }

    // ------------------------------------------------------------------ main flow

    private suspend fun execute(run: Run) {
        if (!settings.isConfigured()) throw Stop(FailureReason.NOT_CONFIGURED)
        when (health.sdkStatus()) {
            SdkStatus.AVAILABLE -> Unit
            else -> throw Stop(FailureReason.HC_UNAVAILABLE)
        }
        val granted = health.grantedPermissions()
        val unsupported = health.unsupportedTypeIds()
        val specs = settings.enabledTypeIds()
            .mapNotNull { HealthTypes.byId[it] }
            .filter { it.readPermission in granted && it.id !in unsupported }
            .sortedBy { it.tier }
        if (specs.isEmpty()) throw Stop(FailureReason.PERMISSIONS_MISSING)

        // Leftovers of earlier runs go first (FIFO guarantees record-before-deletion order).
        flush(run)

        val typesKey = specs.map { it.id }.sorted().joinToString(",")
        val touchedDays = mutableMapOf<String, MutableSet<LocalDate>>()
        var tokenState = store.changesToken()
        var needNewToken = tokenState == null

        if (tokenState != null) {
            run.onProgress(SyncProgress(SyncPhase.CHANGES))
            val drained = drainChanges(run, tokenState, specs, touchedDays)
            if (drained == null) {
                // Token expired: changes in the gap are lost, so everything is re-read (idempotent upserts).
                run.error("token_expired")
                run.mode = SyncMode.BACKFILL
                store.resetBackfill(specs.map { it.id })
                needNewToken = true
            } else if (drained.typesKey != typesKey) {
                needNewToken = true
                // Types that were not covered by the old token missed changes: read them from scratch.
                val before = drained.typesKey.split(',').toSet()
                val added = specs.filter { it.id !in before }
                if (added.isNotEmpty()) {
                    store.commit(emptyList(), StateUpdate(backfill = added.map { BackfillUpdate(it.id, null, false) }))
                }
            }
        }
        if (needNewToken) {
            // The token is taken before reading history so nothing changing during the backfill is lost.
            val token = health.createChangesToken(specs)
            store.commit(emptyList(), StateUpdate(changesToken = ChangesTokenState(token, typesKey, clock())))
        }

        val pending = specs.filter { !store.typeProgress(it.id).backfillComplete }
        if (pending.isNotEmpty()) {
            run.mode = SyncMode.BACKFILL
            backfill(run, pending)
        }
        enqueueRecentAggregates(run, specs, touchedDays)
        flush(run)
    }

    // ------------------------------------------------------------------ GPS routes refresh

    private suspend fun executeRoutes(run: Run) {
        if (!settings.isConfigured()) throw Stop(FailureReason.NOT_CONFIGURED)
        if (health.sdkStatus() != SdkStatus.AVAILABLE) throw Stop(FailureReason.HC_UNAVAILABLE)
        val granted = health.grantedPermissions()
        val spec = HealthTypes.byId.getValue("ExerciseSession")
        if (spec.readPermission !in granted || spec.id in health.unsupportedTypeIds()) throw Stop(FailureReason.PERMISSIONS_MISSING)
        if (HealthTypes.ROUTES_PERMISSION !in granted) {
            run.error("routes_not_granted")
            throw Stop(FailureReason.PERMISSIONS_MISSING)
        }
        var state = store.routeRefresh() ?: RouteRefreshState()
        if (state.complete) return

        flush(run) // leftovers of earlier runs first
        val now = clock()
        var cursor = state.cursor ?: Instant.EPOCH
        run.onProgress(SyncProgress(SyncPhase.ROUTES, spec.id, run.routesSent))
        while (cursor.isBefore(now)) {
            currentCoroutineContext().ensureActive()
            val peek = health.readRecords(spec, cursor, now, null, 1).records.firstOrNull() ?: break
            val windowStart = maxOf(cursor, spec.startOf(peek))
            val windowEnd = minOf(windowStart.plus(limits.backfillWindow), now)
            readRouteWindow(run, spec, windowStart, windowEnd)
            state = RouteRefreshState(cursor = windowEnd, complete = false)
            store.commit(emptyList(), StateUpdate(routeRefresh = state))
            cursor = windowEnd
            flush(run)
            run.onProgress(SyncProgress(SyncPhase.ROUTES, spec.id, run.routesSent))
        }
        store.commit(emptyList(), StateUpdate(routeRefresh = RouteRefreshState(cursor = now, complete = true)))
        flush(run)
    }

    private suspend fun readRouteWindow(run: Run, spec: HealthTypeSpec, from: Instant, to: Instant) {
        var token: String? = null
        do {
            currentCoroutineContext().ensureActive()
            val page = health.readRecords(spec, from, to, token, limits.routePageSize)
            val withRoute = page.records.filter { HealthTypes.hasRoute(it) }
            run.routesQueued += withRoute.size
            if (withRoute.isNotEmpty()) {
                store.commit(recordItems(run, spec, withRoute, OutboxKind.ROUTE_UPDATES, limits.maxRouteRecordsPerBatch))
            }
            token = page.nextPageToken
        } while (token != null)
    }

    // ------------------------------------------------------------------ incremental (Changes API)

    /** Returns the (possibly advanced) token state, or null when the token is expired. */
    private suspend fun drainChanges(
        run: Run,
        state: ChangesTokenState,
        specs: List<HealthTypeSpec>,
        touchedDays: MutableMap<String, MutableSet<LocalDate>>,
    ): ChangesTokenState? {
        val allowed = specs.associateBy { it.recordClass }
        var current = state
        do {
            currentCoroutineContext().ensureActive()
            val page = health.readChanges(current.token)
            if (page.tokenExpired) return null
            val upserts = mutableMapOf<HealthTypeSpec, MutableList<Record>>()
            val deletions = mutableListOf<String>()
            for (c in page.changes) {
                when (c) {
                    is ChangeItem.Upsert -> allowed[c.record::class]?.let { spec ->
                        upserts.getOrPut(spec) { mutableListOf() }.add(c.record)
                        touchedDays.getOrPut(spec.id) { mutableSetOf() }.add(localDay(spec, c.record))
                    }
                    is ChangeItem.Delete -> deletions.add(c.recordId)
                }
            }
            val items = mutableListOf<OutboxItem>()
            for ((spec, records) in upserts) items += recordItems(run, spec, records)
            items += deletionItems(run, deletions)
            current = current.copy(token = page.nextToken)
            // Token and outbox are written in one transaction: a crash never loses a change set.
            store.commit(items, StateUpdate(changesToken = current))
            if (page.hasMore) flush(run)
        } while (page.hasMore)
        return current
    }

    // ------------------------------------------------------------------ backfill

    private suspend fun backfill(run: Run, specs: List<HealthTypeSpec>) {
        for (spec in specs) {
            currentCoroutineContext().ensureActive()
            try {
                backfillType(run, spec)
            } catch (e: Stop) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecurityException) {
                run.error("type_read_failed", spec.id)
            } catch (e: Exception) {
                run.error("type_read_failed", spec.id)
            }
        }
    }

    private suspend fun backfillType(run: Run, spec: HealthTypeSpec) {
        val now = clock()
        var cursor = store.typeProgress(spec.id).backfillCursor ?: Instant.EPOCH
        run.onProgress(SyncProgress(SyncPhase.BACKFILL, spec.id, run.recordsDelivered))
        while (cursor.isBefore(now)) {
            currentCoroutineContext().ensureActive()
            // Skip empty stretches: jump to the next record instead of walking every window.
            val peek = health.readRecords(spec, cursor, now, null, 1).records.firstOrNull() ?: break
            val windowStart = maxOf(cursor, spec.startOf(peek))
            val windowEnd = minOf(windowStart.plus(limits.backfillWindow), now)
            readWindow(run, spec, windowStart, windowEnd)
            queueAggregates(run, spec, localDay(windowStart), localDay(windowEnd).plusDays(1))
            val done = !windowEnd.isBefore(now)
            store.commit(emptyList(), StateUpdate(backfill = listOf(BackfillUpdate(spec.id, windowEnd, done))))
            cursor = windowEnd
            flush(run) // keeps the outbox small during long backfills and surfaces network/auth problems early
            run.onProgress(SyncProgress(SyncPhase.BACKFILL, spec.id, run.recordsDelivered))
            if (done) break
        }
        store.commit(emptyList(), StateUpdate(backfill = listOf(BackfillUpdate(spec.id, now, true))))
    }

    private suspend fun readWindow(run: Run, spec: HealthTypeSpec, from: Instant, to: Instant) {
        val pageSize = if (spec.shape == app.sbh.reader.health.RecordShape.SERIES || spec.shape == app.sbh.reader.health.RecordShape.SESSION) {
            limits.seriesPageSize
        } else {
            limits.scalarPageSize
        }
        var token: String? = null
        do {
            currentCoroutineContext().ensureActive()
            val page = health.readRecords(spec, from, to, token, pageSize)
            if (page.records.isNotEmpty()) store.commit(recordItems(run, spec, page.records))
            token = page.nextPageToken
        } while (token != null)
    }

    // ------------------------------------------------------------------ aggregates

    private suspend fun enqueueRecentAggregates(
        run: Run,
        specs: List<HealthTypeSpec>,
        touchedDays: Map<String, Set<LocalDate>>,
    ) {
        val today = localDay(clock())
        for (spec in specs) {
            if (spec.aggregates.isEmpty()) continue
            val days = (touchedDays[spec.id] ?: emptySet()) +
                (0 until limits.recentAggregateDays).map { today.minusDays(it.toLong()) }
            queueAggregates(run, spec, days.min(), days.max().plusDays(1))
        }
    }

    private suspend fun queueAggregates(run: Run, spec: HealthTypeSpec, from: LocalDate, toExclusive: LocalDate) {
        if (spec.aggregates.isEmpty()) return
        run.onProgress(SyncProgress(SyncPhase.AGGREGATES, spec.id, run.recordsDelivered))
        val aggregates = try {
            health.dailyAggregates(spec, from, toExclusive)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            run.error("aggregate_failed", spec.id)
            return
        }
        if (aggregates.isEmpty()) return
        val snapshotAt = clock().toString()
        val items = aggregates.map { aggregateJson(it, snapshotAt) }
        val chunks = items.chunked(limits.maxBatchRecords)
        store.commit(
            chunks.map { chunk ->
                OutboxItem(
                    kind = OutboxKind.AGGREGATES,
                    typeId = spec.id,
                    syncId = run.syncId,
                    payload = JsonArray(chunk).toString(),
                    itemCount = chunk.size,
                    maxTime = null,
                )
            },
        )
    }

    private fun aggregateJson(a: DailyAggregate, snapshotAt: String): JsonObject = buildJsonObject {
        put("date", a.date.toString())
        put("type", a.typeId)
        for ((kind, v) in a.values) {
            put(
                when (kind) {
                    AggregateKind.SUM -> "sum"
                    AggregateKind.AVG -> "avg"
                    AggregateKind.MIN -> "min"
                    AggregateKind.MAX -> "max"
                    AggregateKind.COUNT -> "count"
                },
                v,
            )
        }
        if (a.dataOrigins.isNotEmpty()) putJsonArray("data_origins") { a.dataOrigins.sorted().forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
        put("snapshot_at", snapshotAt)
    }

    // ------------------------------------------------------------------ outbox production

    private fun recordItems(
        run: Run,
        spec: HealthTypeSpec,
        records: List<Record>,
        kind: OutboxKind = OutboxKind.RECORDS,
        maxRecords: Int = limits.maxBatchRecords,
    ): List<OutboxItem> {
        val items = mutableListOf<OutboxItem>()
        var buf = StringBuilder("[")
        var count = 0
        var maxTime: Instant? = null

        fun close() {
            if (count == 0) return
            buf.append(']')
            items += OutboxItem(
                kind = kind, typeId = spec.id, syncId = run.syncId,
                payload = buf.toString(), itemCount = count, maxTime = maxTime,
            )
            buf = StringBuilder("[")
            count = 0
            maxTime = null
        }

        for (record in records) {
            val out: OutRecord = try {
                spec.map(record)
            } catch (e: Exception) {
                run.error("map_failed", spec.id)
                continue
            }
            val encoded = WireJson.encodeToString(OutRecord.serializer(), out)
            if (count > 0 && (count >= maxRecords || buf.length + encoded.length > limits.softBatchBytes)) close()
            if (count > 0) buf.append(',')
            buf.append(encoded)
            count++
            val t = runCatching { Instant.parse(out.endTime ?: out.startTime) }.getOrNull()
            if (t != null && (maxTime == null || t.isAfter(maxTime))) maxTime = t
        }
        close()
        return items
    }

    private fun deletionItems(run: Run, ids: List<String>): List<OutboxItem> =
        ids.distinct().chunked(limits.maxBatchRecords).map { chunk ->
            OutboxItem(
                kind = OutboxKind.DELETIONS,
                typeId = null,
                syncId = run.syncId,
                payload = buildJsonArray { chunk.forEach { add(buildJsonObject { put("hc_id", it) }) } }.toString(),
                itemCount = chunk.size,
                maxTime = null,
            )
        }

    // ------------------------------------------------------------------ delivery

    /** Delivers the whole outbox FIFO. Throws [Stop] on conditions that make further attempts pointless. */
    private suspend fun flush(run: Run) {
        while (true) {
            currentCoroutineContext().ensureActive()
            val items = store.peekOutbox(20)
            if (items.isEmpty()) return
            run.onProgress(SyncProgress(SyncPhase.UPLOADING, null, run.recordsDelivered))
            for (item in items) deliver(run, item)
        }
    }

    private suspend fun deliver(run: Run, item: OutboxItem) {
        val array = Json.parseToJsonElement(item.payload).jsonArray
        when (val result = post(item.kind, item.syncId, array)) {
            is ApiResult.Ok -> {
                run.batches++
                when (item.kind) {
                    OutboxKind.RECORDS -> {
                        run.recordsDelivered += item.itemCount - result.value.rejected
                        run.recordsRejected += result.value.rejected
                        if (result.value.rejected > 0) repeat(result.value.rejected) { run.error("record_rejected", item.typeId) }
                    }
                    OutboxKind.ROUTE_UPDATES -> {
                        run.routesSent += item.itemCount - result.value.rejected
                        run.recordsRejected += result.value.rejected
                        repeat(result.value.rejected) { run.error("record_rejected", item.typeId) }
                    }
                    OutboxKind.DELETIONS -> run.deletionsSent += item.itemCount
                    OutboxKind.AGGREGATES -> run.aggregatesSent += item.itemCount
                }
                store.markDelivered(item)
            }
            is ApiResult.HttpError -> handleHttpError(run, item, result)
            is ApiResult.NetworkError -> {
                store.recordFailure(item, "net:${result.kind}")
                throw Stop(
                    when (result.kind) {
                        NetworkErrorKind.NOT_CONFIGURED -> FailureReason.NOT_CONFIGURED
                        NetworkErrorKind.BAD_URL, NetworkErrorKind.CLEARTEXT_BLOCKED -> FailureReason.CONFIG_INVALID
                        else -> FailureReason.NETWORK
                    },
                )
            }
        }
    }

    private suspend fun handleHttpError(run: Run, item: OutboxItem, e: ApiResult.HttpError) {
        when {
            e.status == 401 -> {
                store.recordFailure(item, "http:401")
                run.error(e.code ?: "unauthorized")
                throw Stop(FailureReason.AUTH)
            }
            e.status in 400..499 && e.status != 408 && e.status != 429 -> {
                // 400/422: a client-side bug that retrying cannot fix. Drop the item so it does not block the queue.
                run.error("permanent_${e.status}", item.typeId)
                store.discard(item)
            }
            else -> {
                store.recordFailure(item, "http:${e.status}")
                if (item.attempts + 1 >= limits.maxAttempts) {
                    run.error("discarded_after_retries", item.typeId)
                    store.discard(item)
                } else {
                    throw Stop(FailureReason.SERVER)
                }
            }
        }
    }

    /** Posts [array]; on 413 splits it in halves (contract §1.2). */
    private suspend fun post(kind: OutboxKind, syncId: String, array: JsonArray): ApiResult<IngestResult> {
        val result = when (kind) {
            OutboxKind.RECORDS, OutboxKind.ROUTE_UPDATES -> api.postRecords(syncId, array)
            OutboxKind.DELETIONS -> api.postDeletions(syncId, array)
            OutboxKind.AGGREGATES -> api.postDailyAggregates(syncId, array)
        }
        if (result is ApiResult.HttpError && result.status == 413 && array.size > 1) {
            val mid = array.size / 2
            val first = post(kind, syncId, JsonArray(array.subList(0, mid)))
            if (first !is ApiResult.Ok) return first
            val second = post(kind, syncId, JsonArray(array.subList(mid, array.size)))
            if (second !is ApiResult.Ok) return second
            return ApiResult.Ok(
                IngestResult(
                    received = first.value.received + second.value.received,
                    accepted = first.value.accepted + second.value.accepted,
                    unchanged = first.value.unchanged + second.value.unchanged,
                    rejected = first.value.rejected + second.value.rejected,
                ),
            )
        }
        return result
    }

    // ------------------------------------------------------------------ wrap-up

    private suspend fun finish(run: Run, failure: FailureReason?): SyncResult {
        val finishedAt = clock()
        val outboxLeft = store.peekOutbox(1).isNotEmpty()
        val status = when {
            failure != null -> SyncStatus.FAILED
            run.errors.isNotEmpty() || outboxLeft -> SyncStatus.PARTIAL
            else -> SyncStatus.COMPLETED
        }
        val counts = linkedMapOf(
            "records_sent" to run.recordsDelivered,
            "records_rejected" to run.recordsRejected,
            "deletions_sent" to run.deletionsSent,
            "aggregates_sent" to run.aggregatesSent,
            "batches" to run.batches,
        )
        if (run.mode == SyncMode.ROUTES) {
            counts["routes_queued"] = run.routesQueued
            counts["routes_sent"] = run.routesSent
        }
        val errorList = buildList {
            failure?.let { add("failure:${it.name.lowercase()}") }
            run.errors.forEach { (k, v) -> add("$k=$v") }
        }
        store.addLog(SyncLogEntry(
            syncId = run.syncId, mode = run.mode, status = status,
            startedAt = run.startedAt, finishedAt = finishedAt, counts = counts, errors = errorList,
        ))
        // Best-effort report for the writer's sync history; pointless when the server is not reachable.
        // The writer's report only knows backfill/incremental (contract): the GPS job stays in the local log.
        if (run.mode != SyncMode.ROUTES && failure != FailureReason.NOT_CONFIGURED && failure != FailureReason.NETWORK && failure != FailureReason.CONFIG_INVALID) {
            runCatching {
                api.postSyncReport(
                    SyncReportBody(
                        syncId = run.syncId,
                        mode = run.mode.name.lowercase(),
                        status = status.name.lowercase(),
                        startedAt = run.startedAt.toString(),
                        finishedAt = finishedAt.toString(),
                        counts = counts,
                        errors = run.errors.map { (k, v) ->
                            val code = k.substringBefore(':')
                            ReportError(code = code, type = k.substringAfter(':', "").ifEmpty { null }, count = v)
                        } + listOfNotNull(failure?.let { ReportError(code = it.name.lowercase()) }),
                    ),
                )
            }
        }
        return SyncResult(run.syncId, run.mode, status, failure, counts)
    }

    // ------------------------------------------------------------------ helpers

    private fun localDay(instant: Instant): LocalDate = instant.atZone(zone()).toLocalDate()

    /** Day a record counts for: wake-up day for sleep, start day otherwise (contract §3.1). */
    private fun localDay(spec: HealthTypeSpec, record: Record): LocalDate {
        val instant = if (spec.id == "SleepSession") spec.endOf(record) ?: spec.startOf(record) else spec.startOf(record)
        return localDay(instant)
    }
}

/** JSON flavour of the wire protocol: nulls are omitted (the writer rejects unknown or null-typed fields). */
internal val WireJson = Json {
    explicitNulls = false
    encodeDefaults = false
}
