package app.sbh.reader.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Pending delivery. Holds health payloads at rest in app-private storage until the server acknowledges them. */
@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val typeId: String?,
    val syncId: String,
    val payload: String,
    val itemCount: Int,
    val maxTimeMillis: Long?,
    val attempts: Int = 0,
    val lastError: String? = null,
)

@Entity(tableName = "type_state")
data class TypeStateEntity(
    @PrimaryKey val typeId: String,
    val sentCount: Long = 0,
    val lastRecordMillis: Long? = null,
    val backfillCursorMillis: Long? = null,
    val backfillComplete: Boolean = false,
)

@Entity(tableName = "kv")
data class KvEntity(@PrimaryKey val key: String, val value: String)

@Entity(tableName = "sync_log")
data class SyncLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val syncId: String,
    val mode: String,
    val status: String,
    val startedAtMillis: Long,
    val finishedAtMillis: Long,
    val countsJson: String,
    val errorsJson: String,
)

@Dao
interface SyncDao {
    @Insert
    suspend fun insertOutbox(items: List<OutboxEntity>)

    @Query("SELECT * FROM outbox ORDER BY id ASC LIMIT :limit")
    suspend fun peekOutbox(limit: Int): List<OutboxEntity>

    @Query("DELETE FROM outbox WHERE id = :id")
    suspend fun deleteOutbox(id: Long)

    @Query("UPDATE outbox SET attempts = attempts + 1, lastError = :error WHERE id = :id")
    suspend fun failOutbox(id: Long, error: String)

    @Query("SELECT COUNT(*) FROM outbox")
    fun outboxCount(): Flow<Int>

    @Query("SELECT * FROM type_state WHERE typeId = :typeId")
    suspend fun typeState(typeId: String): TypeStateEntity?

    @Query("SELECT * FROM type_state")
    fun typeStates(): Flow<List<TypeStateEntity>>

    @Upsert
    suspend fun upsertTypeState(state: TypeStateEntity)

    @Query("DELETE FROM type_state WHERE typeId IN (:typeIds)")
    suspend fun deleteTypeStates(typeIds: Collection<String>)

    @Query("SELECT value FROM kv WHERE `key` = :key")
    suspend fun kv(key: String): String?

    @Upsert
    suspend fun upsertKv(entry: KvEntity)

    @Query("DELETE FROM kv WHERE `key` = :key")
    suspend fun deleteKv(key: String)

    @Insert
    suspend fun insertLog(entry: SyncLogEntity)

    @Query("DELETE FROM sync_log WHERE id NOT IN (SELECT id FROM sync_log ORDER BY id DESC LIMIT 500)")
    suspend fun trimLogs()

    @Query("SELECT * FROM sync_log ORDER BY id DESC LIMIT :limit")
    fun logs(limit: Int): Flow<List<SyncLogEntity>>

    @Query("SELECT MAX(finishedAtMillis) FROM sync_log WHERE status = 'COMPLETED'")
    fun lastSuccessMillis(): Flow<Long?>

    @Query("SELECT * FROM sync_log ORDER BY id DESC LIMIT 1")
    fun lastRun(): Flow<SyncLogEntity?>
}

@Database(
    entities = [OutboxEntity::class, TypeStateEntity::class, KvEntity::class, SyncLogEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class SyncDatabase : RoomDatabase() {
    abstract fun dao(): SyncDao

    companion object {
        fun create(context: Context): SyncDatabase =
            Room.databaseBuilder(context, SyncDatabase::class.java, "sbh-sync.db").build()
    }
}
