package app.sbh.reader.sync

import kotlinx.coroutines.flow.Flow
import java.time.Instant

/** [ROUTE_UPDATES] are re-sent workouts carrying a GPS route: posted like records but not counted in the per-type stats. */
enum class OutboxKind { RECORDS, DELETIONS, AGGREGATES, ROUTE_UPDATES }

/** One unit of work waiting to be delivered. [payload] is the JSON array of items; it never appears in logs. */
data class OutboxItem(
    val id: Long = 0,
    val kind: OutboxKind,
    val typeId: String?,
    val syncId: String,
    val payload: String,
    val itemCount: Int,
    /** Latest record end/start time inside the payload (for the "last record sent" stat). */
    val maxTime: Instant?,
    val attempts: Int = 0,
    val lastError: String? = null,
)

data class ChangesTokenState(
    val token: String,
    /** Sorted ids of the types the token was created for; a different set needs a new token. */
    val typesKey: String,
    val createdAt: Instant,
)

data class TypeProgress(
    val typeId: String,
    val sentCount: Long = 0,
    val lastRecordTime: Instant? = null,
    val backfillCursor: Instant? = null,
    val backfillComplete: Boolean = false,
)

enum class SyncMode { BACKFILL, INCREMENTAL, ROUTES }
enum class SyncStatus { COMPLETED, PARTIAL, FAILED }

data class SyncLogEntry(
    val id: Long = 0,
    val syncId: String,
    val mode: SyncMode,
    val status: SyncStatus,
    val startedAt: Instant,
    val finishedAt: Instant,
    /** Counts only, never health values. */
    val counts: Map<String, Int>,
    /** Short machine-readable error codes with a count, e.g. `network=1`. */
    val errors: List<String>,
)

/**
 * Progress of the "re-download GPS routes" job, kept apart from the normal backfill cursors and the Changes token.
 * [cursor] = everything before it has been read; null = start from the beginning.
 */
data class RouteRefreshState(val cursor: Instant? = null, val complete: Boolean = false)

data class BackfillUpdate(val typeId: String, val cursor: Instant?, val complete: Boolean)

/** Sync state that must be persisted atomically with the outbox items produced from it. */
data class StateUpdate(
    val changesToken: ChangesTokenState? = null,
    val backfill: List<BackfillUpdate> = emptyList(),
    val routeRefresh: RouteRefreshState? = null,
)

/**
 * Persistence used by the sync engine: outbox, per-type progress, Changes token and sync log.
 * Room-backed in the app, in-memory in tests. All writes that must be atomic go through [commit].
 */
interface SyncStore {
    /** Atomically adds outbox items and applies [state] (token / cursors). */
    suspend fun commit(items: List<OutboxItem>, state: StateUpdate = StateUpdate())

    suspend fun peekOutbox(limit: Int): List<OutboxItem>

    /** Removes a delivered item and, for records, updates the per-type "sent" stats. */
    suspend fun markDelivered(item: OutboxItem)

    suspend fun recordFailure(item: OutboxItem, error: String)

    /** A permanently invalid item (never retried): removed from the outbox. */
    suspend fun discard(item: OutboxItem)

    suspend fun changesToken(): ChangesTokenState?

    suspend fun typeProgress(typeId: String): TypeProgress

    /** Forgets all backfill cursors and the Changes token (full re-read on next run). */
    suspend fun resetBackfill(typeIds: Collection<String>)

    /** Null = the route refresh was never started. */
    suspend fun routeRefresh(): RouteRefreshState?

    fun routeRefreshState(): Flow<RouteRefreshState?>

    suspend fun addLog(entry: SyncLogEntry)

    fun outboxCount(): Flow<Int>

    fun typeProgressAll(): Flow<List<TypeProgress>>

    fun logs(limit: Int): Flow<List<SyncLogEntry>>

    fun lastSuccess(): Flow<Instant?>

    fun lastRun(): Flow<SyncLogEntry?>
}
