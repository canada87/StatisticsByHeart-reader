package app.sbh.reader.data

import androidx.room.withTransaction
import app.sbh.reader.sync.ChangesTokenState
import app.sbh.reader.sync.OutboxItem
import app.sbh.reader.sync.OutboxKind
import app.sbh.reader.sync.RouteRefreshState
import app.sbh.reader.sync.StateUpdate
import app.sbh.reader.sync.SyncLogEntry
import app.sbh.reader.sync.SyncMode
import app.sbh.reader.sync.SyncStatus
import app.sbh.reader.sync.SyncStore
import app.sbh.reader.sync.TypeProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.time.Instant

class RoomSyncStore(private val db: SyncDatabase) : SyncStore {
    private val dao = db.dao()
    private val json = Json

    override suspend fun commit(items: List<OutboxItem>, state: StateUpdate) {
        db.withTransaction {
            if (items.isNotEmpty()) dao.insertOutbox(items.map { it.toEntity() })
            state.changesToken?.let { t ->
                dao.upsertKv(KvEntity(KEY_TOKEN, t.token))
                dao.upsertKv(KvEntity(KEY_TOKEN_TYPES, t.typesKey))
                dao.upsertKv(KvEntity(KEY_TOKEN_CREATED, t.createdAt.toEpochMilli().toString()))
            }
            state.routeRefresh?.let { r ->
                dao.upsertKv(KvEntity(KEY_ROUTES, "${r.cursor?.toEpochMilli() ?: "-"}|${if (r.complete) 1 else 0}"))
            }
            for (b in state.backfill) {
                val cur = dao.typeState(b.typeId) ?: TypeStateEntity(b.typeId)
                dao.upsertTypeState(cur.copy(backfillCursorMillis = b.cursor?.toEpochMilli(), backfillComplete = b.complete))
            }
        }
    }

    override suspend fun peekOutbox(limit: Int): List<OutboxItem> = dao.peekOutbox(limit).map { it.toItem() }

    override suspend fun markDelivered(item: OutboxItem) {
        db.withTransaction {
            dao.deleteOutbox(item.id)
            if (item.kind == OutboxKind.RECORDS && item.typeId != null) {
                val cur = dao.typeState(item.typeId) ?: TypeStateEntity(item.typeId)
                val last = maxOf(cur.lastRecordMillis ?: Long.MIN_VALUE, item.maxTime?.toEpochMilli() ?: Long.MIN_VALUE)
                dao.upsertTypeState(
                    cur.copy(
                        sentCount = cur.sentCount + item.itemCount,
                        lastRecordMillis = if (last == Long.MIN_VALUE) null else last,
                    ),
                )
            }
        }
    }

    override suspend fun recordFailure(item: OutboxItem, error: String) = dao.failOutbox(item.id, error.take(200))

    override suspend fun discard(item: OutboxItem) = dao.deleteOutbox(item.id)

    override suspend fun changesToken(): ChangesTokenState? {
        val token = dao.kv(KEY_TOKEN) ?: return null
        val types = dao.kv(KEY_TOKEN_TYPES) ?: return null
        val created = dao.kv(KEY_TOKEN_CREATED)?.toLongOrNull() ?: return null
        return ChangesTokenState(token, types, Instant.ofEpochMilli(created))
    }

    override suspend fun typeProgress(typeId: String): TypeProgress =
        (dao.typeState(typeId) ?: TypeStateEntity(typeId)).toProgress()

    override suspend fun resetBackfill(typeIds: Collection<String>) {
        db.withTransaction {
            for (id in typeIds) {
                val cur = dao.typeState(id) ?: continue
                dao.upsertTypeState(cur.copy(backfillCursorMillis = null, backfillComplete = false))
            }
            dao.deleteKv(KEY_TOKEN)
            dao.deleteKv(KEY_TOKEN_TYPES)
            dao.deleteKv(KEY_TOKEN_CREATED)
        }
    }

    override suspend fun routeRefresh(): RouteRefreshState? = dao.kv(KEY_ROUTES).toRouteRefresh()

    override fun routeRefreshState(): Flow<RouteRefreshState?> = dao.kvFlow(KEY_ROUTES).map { it.toRouteRefresh() }

    private fun String?.toRouteRefresh(): RouteRefreshState? {
        val parts = this?.split('|') ?: return null
        if (parts.size != 2) return null
        return RouteRefreshState(cursor = parts[0].toLongOrNull()?.let(Instant::ofEpochMilli), complete = parts[1] == "1")
    }

    override suspend fun addLog(entry: SyncLogEntry) {
        db.withTransaction {
            dao.insertLog(entry.toEntity())
            dao.trimLogs()
        }
    }

    override fun outboxCount(): Flow<Int> = dao.outboxCount()

    override fun typeProgressAll(): Flow<List<TypeProgress>> = dao.typeStates().map { l -> l.map { it.toProgress() } }

    override fun logs(limit: Int): Flow<List<SyncLogEntry>> = dao.logs(limit).map { l -> l.map { it.toEntry() } }

    override fun lastSuccess(): Flow<Instant?> = dao.lastSuccessMillis().map { it?.let(Instant::ofEpochMilli) }

    override fun lastRun(): Flow<SyncLogEntry?> = dao.lastRun().map { it?.toEntry() }

    private fun OutboxItem.toEntity() = OutboxEntity(
        id = id, kind = kind.name, typeId = typeId, syncId = syncId, payload = payload,
        itemCount = itemCount, maxTimeMillis = maxTime?.toEpochMilli(), attempts = attempts, lastError = lastError,
    )

    private fun OutboxEntity.toItem() = OutboxItem(
        id = id, kind = OutboxKind.valueOf(kind), typeId = typeId, syncId = syncId, payload = payload,
        itemCount = itemCount, maxTime = maxTimeMillis?.let(Instant::ofEpochMilli), attempts = attempts, lastError = lastError,
    )

    private fun TypeStateEntity.toProgress() = TypeProgress(
        typeId = typeId,
        sentCount = sentCount,
        lastRecordTime = lastRecordMillis?.let(Instant::ofEpochMilli),
        backfillCursor = backfillCursorMillis?.let(Instant::ofEpochMilli),
        backfillComplete = backfillComplete,
    )

    private fun SyncLogEntry.toEntity() = SyncLogEntity(
        id = id, syncId = syncId, mode = mode.name, status = status.name,
        startedAtMillis = startedAt.toEpochMilli(), finishedAtMillis = finishedAt.toEpochMilli(),
        countsJson = json.encodeToString(MapSerializer(String.serializer(), Int.serializer()), counts),
        errorsJson = json.encodeToString(ListSerializer(String.serializer()), errors),
    )

    private fun SyncLogEntity.toEntry() = SyncLogEntry(
        id = id, syncId = syncId, mode = SyncMode.valueOf(mode), status = SyncStatus.valueOf(status),
        startedAt = Instant.ofEpochMilli(startedAtMillis), finishedAt = Instant.ofEpochMilli(finishedAtMillis),
        counts = runCatching { json.decodeFromString(MapSerializer(String.serializer(), Int.serializer()), countsJson) }.getOrDefault(emptyMap()),
        errors = runCatching { json.decodeFromString(ListSerializer(String.serializer()), errorsJson) }.getOrDefault(emptyList()),
    )

    private companion object {
        const val KEY_TOKEN = "changes_token"
        const val KEY_ROUTES = "route_refresh"
        const val KEY_TOKEN_TYPES = "changes_token_types"
        const val KEY_TOKEN_CREATED = "changes_token_created"
    }
}
