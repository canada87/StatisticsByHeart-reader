package app.sbh.reader.health

import androidx.health.connect.client.records.Record
import java.time.Instant
import java.time.LocalDate

enum class SdkStatus {
    AVAILABLE,

    /** Health Connect is not installed (Android <= 13 without the Play Store APK, or disabled). */
    NOT_INSTALLED,

    /** The installed provider is too old for this client. */
    UPDATE_REQUIRED,
}

enum class HcFeature { HISTORY, BACKGROUND }

data class RecordPage(val records: List<Record>, val nextPageToken: String?)

sealed interface ChangeItem {
    data class Upsert(val record: Record) : ChangeItem
    data class Delete(val recordId: String) : ChangeItem
}

data class ChangesPage(
    val changes: List<ChangeItem>,
    val nextToken: String,
    val hasMore: Boolean,
    /** The token is older than ~30 days: changes were lost, a reconciliation backfill is needed. */
    val tokenExpired: Boolean,
)

/** One official HC aggregate for one local day (§5.2.4); [values] holds only the metrics that had data. */
data class DailyAggregate(
    val date: LocalDate,
    val typeId: String,
    val values: Map<AggregateKind, Double>,
    val dataOrigins: Set<String>,
)

/** Records of one type grouped by source app, used by the inventory. */
data class SourceStat(
    val packageName: String,
    val count: Long,
    val first: Instant?,
    val last: Instant?,
)

/**
 * Abstraction over Health Connect so that sync/outbox logic can be tested with a fake
 * (PROJECT_GUIDE §3.1 "Test"). Never log record contents from implementations.
 */
interface HealthRepository {
    fun sdkStatus(): SdkStatus

    suspend fun grantedPermissions(): Set<String>

    suspend fun isFeatureAvailable(feature: HcFeature): Boolean

    /** Types whose [HealthTypeSpec.requiredFeature] is unavailable on this device. */
    suspend fun unsupportedTypeIds(): Set<String>

    /**
     * Records of [spec] overlapping `[from, to)`, ascending by time, one page at a time.
     * Pass the previous [RecordPage.nextPageToken] to continue; `null` starts the read.
     */
    suspend fun readRecords(
        spec: HealthTypeSpec,
        from: Instant,
        to: Instant,
        pageToken: String?,
        pageSize: Int,
    ): RecordPage

    suspend fun createChangesToken(specs: Collection<HealthTypeSpec>): String

    suspend fun readChanges(token: String): ChangesPage

    /** Official aggregate of [spec] over one absolute time range (used for the "today" summary). */
    suspend fun aggregateRange(spec: HealthTypeSpec, from: Instant, to: Instant): Map<AggregateKind, Double>

    /** Official aggregates per local day for `[fromDay, toDayExclusive)`; only days with data are returned. */
    suspend fun dailyAggregates(spec: HealthTypeSpec, fromDay: LocalDate, toDayExclusive: LocalDate): List<DailyAggregate>
}
