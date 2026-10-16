package app.sbh.reader.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import kotlin.reflect.KClass

/** Real [HealthRepository] backed by the Health Connect client. */
class HealthConnectRepository(private val context: Context) : HealthRepository {

    private val client: HealthConnectClient by lazy { HealthConnectClient.getOrCreate(context) }

    override fun sdkStatus(): SdkStatus = when (HealthConnectClient.getSdkStatus(context)) {
        HealthConnectClient.SDK_AVAILABLE -> SdkStatus.AVAILABLE
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> SdkStatus.UPDATE_REQUIRED
        else -> SdkStatus.NOT_INSTALLED
    }

    override suspend fun grantedPermissions(): Set<String> = client.permissionController.getGrantedPermissions()

    override suspend fun isFeatureAvailable(feature: HcFeature): Boolean {
        val id = when (feature) {
            HcFeature.HISTORY -> HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY
            HcFeature.BACKGROUND -> HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND
        }
        return client.features.getFeatureStatus(id) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
    }

    override suspend fun unsupportedTypeIds(): Set<String> =
        HealthTypes.all
            .filter { spec ->
                spec.requiredFeature?.let { client.features.getFeatureStatus(it) != HealthConnectFeatures.FEATURE_STATUS_AVAILABLE } == true
            }
            .map { it.id }
            .toSet()

    override suspend fun readRecords(
        spec: HealthTypeSpec,
        from: Instant,
        to: Instant,
        pageToken: String?,
        pageSize: Int,
    ): RecordPage {
        @Suppress("UNCHECKED_CAST")
        val request = ReadRecordsRequest(
            recordType = spec.recordClass as KClass<Record>,
            timeRangeFilter = TimeRangeFilter.between(from, to),
            ascendingOrder = true,
            pageSize = pageSize,
            pageToken = pageToken,
        )
        val response = client.readRecords(request)
        return RecordPage(response.records, response.pageToken)
    }

    override suspend fun readRecordById(spec: HealthTypeSpec, id: String): Record {
        @Suppress("UNCHECKED_CAST")
        return client.readRecord(spec.recordClass as KClass<Record>, id).record
    }

    override suspend fun createChangesToken(specs: Collection<HealthTypeSpec>): String =
        client.getChangesToken(ChangesTokenRequest(recordTypes = specs.map { it.recordClass }.toSet()))

    override suspend fun readChanges(token: String): ChangesPage {
        val response = client.getChanges(token)
        val items = response.changes.mapNotNull { change ->
            when (change) {
                is UpsertionChange -> ChangeItem.Upsert(change.record)
                is DeletionChange -> ChangeItem.Delete(change.recordId)
                else -> null
            }
        }
        return ChangesPage(items, response.nextChangesToken, response.hasMore, response.changesTokenExpired)
    }

    override suspend fun aggregateRange(spec: HealthTypeSpec, from: Instant, to: Instant): Map<AggregateKind, Double> {
        if (spec.aggregates.isEmpty() || !from.isBefore(to)) return emptyMap()
        val result = client.aggregate(
            AggregateRequest(
                metrics = spec.aggregates.map { it.metric }.toSet(),
                timeRangeFilter = TimeRangeFilter.between(from, to),
            ),
        )
        return buildMap {
            for (f in spec.aggregates) {
                @Suppress("UNCHECKED_CAST")
                val raw = result[f.metric as AggregateMetric<Any>] ?: continue
                put(f.kind, f.toDouble(raw))
            }
        }
    }

    override suspend fun dailyAggregates(
        spec: HealthTypeSpec,
        fromDay: LocalDate,
        toDayExclusive: LocalDate,
    ): List<DailyAggregate> {
        if (spec.aggregates.isEmpty() || !fromDay.isBefore(toDayExclusive)) return emptyList()
        val request = AggregateGroupByPeriodRequest(
            metrics = spec.aggregates.map { it.metric }.toSet(),
            timeRangeFilter = TimeRangeFilter.between(fromDay.atStartOfDay(), toDayExclusive.atStartOfDay()),
            timeRangeSlicer = Period.ofDays(1),
        )
        return client.aggregateGroupByPeriod(request).mapNotNull { group ->
            val values = buildMap {
                for (f in spec.aggregates) {
                    @Suppress("UNCHECKED_CAST")
                    val raw = group.result[f.metric as AggregateMetric<Any>] ?: continue
                    put(f.kind, f.toDouble(raw))
                }
            }
            if (values.isEmpty()) {
                null
            } else {
                DailyAggregate(
                    date = group.startTime.toLocalDate(),
                    typeId = spec.id,
                    values = values,
                    dataOrigins = group.result.dataOrigins.map { it.packageName }.toSet(),
                )
            }
        }
    }
}
