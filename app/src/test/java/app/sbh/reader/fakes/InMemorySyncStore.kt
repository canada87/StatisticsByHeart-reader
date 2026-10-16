package app.sbh.reader.fakes

import app.sbh.reader.sync.ChangesTokenState
import app.sbh.reader.sync.OutboxItem
import app.sbh.reader.sync.OutboxKind
import app.sbh.reader.sync.RouteRefreshState
import app.sbh.reader.sync.StateUpdate
import app.sbh.reader.sync.SyncMode
import app.sbh.reader.sync.SyncLogEntry
import app.sbh.reader.sync.SyncStatus
import app.sbh.reader.sync.SyncStore
import app.sbh.reader.sync.TypeProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.time.Instant

class InMemorySyncStore : SyncStore {
    private var nextId = 1L
    val outbox = mutableListOf<OutboxItem>()
    val progress = mutableMapOf<String, TypeProgress>()
    var token: ChangesTokenState? = null
    private val routeFlow = MutableStateFlow<RouteRefreshState?>(null)
    var routeState: RouteRefreshState?
        get() = routeFlow.value
        set(v) { routeFlow.value = v }
    val logList = mutableListOf<SyncLogEntry>()
    private val outboxFlow = MutableStateFlow(0)
    private val logFlow = MutableStateFlow<List<SyncLogEntry>>(emptyList())

    override suspend fun commit(items: List<OutboxItem>, state: StateUpdate) {
        items.forEach { outbox += it.copy(id = nextId++) }
        state.changesToken?.let { token = it }
        state.routeRefresh?.let { routeFlow.value = it }
        for (b in state.backfill) {
            val cur = progress[b.typeId] ?: TypeProgress(b.typeId)
            progress[b.typeId] = cur.copy(backfillCursor = b.cursor, backfillComplete = b.complete)
        }
        outboxFlow.value = outbox.size
    }

    override suspend fun peekOutbox(limit: Int) = outbox.take(limit).toList()

    override suspend fun markDelivered(item: OutboxItem) {
        outbox.removeAll { it.id == item.id }
        if (item.kind == OutboxKind.RECORDS && item.typeId != null) {
            val cur = progress[item.typeId] ?: TypeProgress(item.typeId)
            progress[item.typeId] = cur.copy(
                sentCount = cur.sentCount + item.itemCount,
                lastRecordTime = listOfNotNull(cur.lastRecordTime, item.maxTime).maxOrNull(),
            )
        }
        outboxFlow.value = outbox.size
    }

    override suspend fun recordFailure(item: OutboxItem, error: String) {
        val i = outbox.indexOfFirst { it.id == item.id }
        if (i >= 0) outbox[i] = outbox[i].copy(attempts = outbox[i].attempts + 1, lastError = error)
    }

    override suspend fun discard(item: OutboxItem) {
        outbox.removeAll { it.id == item.id }
        outboxFlow.value = outbox.size
    }

    override suspend fun changesToken() = token

    override suspend fun typeProgress(typeId: String) = progress[typeId] ?: TypeProgress(typeId)

    override suspend fun resetBackfill(typeIds: Collection<String>) {
        typeIds.forEach { id -> progress[id]?.let { progress[id] = it.copy(backfillCursor = null, backfillComplete = false) } }
        token = null
    }

    override suspend fun routeRefresh() = routeFlow.value
    override fun routeRefreshState(): Flow<RouteRefreshState?> = routeFlow

    override suspend fun addLog(entry: SyncLogEntry) {
        logList += entry
        logFlow.value = logList.toList()
    }

    override fun outboxCount(): Flow<Int> = outboxFlow
    override fun typeProgressAll(): Flow<List<TypeProgress>> = MutableStateFlow(progress.values.toList())
    override fun logs(limit: Int): Flow<List<SyncLogEntry>> = logFlow
    override fun lastSuccess(): Flow<Instant?> =
        logFlow.map { l -> l.filter { it.status == SyncStatus.COMPLETED && it.mode != SyncMode.ROUTES }.maxOfOrNull { it.finishedAt } }
    override fun lastRun(): Flow<SyncLogEntry?> = logFlow.map { it.lastOrNull() }
}
