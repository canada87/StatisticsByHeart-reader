package app.sbh.reader.health

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import kotlinx.serialization.json.JsonObject
import java.lang.reflect.Method
import java.time.Instant
import java.time.ZoneOffset
import kotlin.reflect.KClass

/** How the writer expects the record's time fields (contract §4 "forma"). */
enum class RecordShape { INTERVAL, INSTANT, SERIES, SESSION }

/** Semantic colour family of a type (maps to the `--m-*` tokens of §8.1). */
enum class MetricGroup { HEART, SLEEP, ACTIVITY, ENERGY, VITALS }

enum class AggregateKind { SUM, AVG, MIN, MAX, COUNT }

/** One field of a daily aggregate: which HC metric feeds it and how to turn the HC value into the contract unit. */
class AggregateField(
    val kind: AggregateKind,
    val metric: AggregateMetric<*>,
    val toDouble: (Any) -> Double,
)

/**
 * One entry of the type registry (PROJECT_GUIDE §5.1): HC class, permission and mapper to the wire DTO.
 * Adding or removing a type is one line in [HealthTypes].
 */
class HealthTypeSpec(
    /** Contract type name: the HC class name without the `Record` suffix. */
    val id: String,
    val recordClass: KClass<out Record>,
    val tier: Int,
    val shape: RecordShape,
    val group: MetricGroup,
    @StringRes val labelRes: Int,
    @DrawableRes val iconRes: Int,
    val aggregates: List<AggregateField> = emptyList(),
    /** `HealthConnectFeatures.FEATURE_*` that must be available for this type, if any. */
    val requiredFeature: Int? = null,
    private val dataMapper: (Record) -> JsonObject,
) {
    val readPermission: String = HealthPermission.getReadPermission(recordClass)
    val defaultEnabled: Boolean get() = tier == 1

    // HC's IntervalRecord / InstantaneousRecord interfaces are internal to the library, so the time accessors of
    // each concrete class are resolved once by name (kept by proguard-rules.pro; HealthTypesTest checks them all).
    private val interval = shape != RecordShape.INSTANT
    private val mStart: Method = recordClass.java.getMethod(if (interval) "getStartTime" else "getTime")
    private val mStartOffset: Method = recordClass.java.getMethod(if (interval) "getStartZoneOffset" else "getZoneOffset")
    private val mEnd: Method? = if (interval) recordClass.java.getMethod("getEndTime") else null
    private val mEndOffset: Method? = if (interval) recordClass.java.getMethod("getEndZoneOffset") else null

    fun startOf(record: Record): Instant = mStart.invoke(record) as Instant

    /** End of interval/series/session records; null for instantaneous ones. */
    fun endOf(record: Record): Instant? = mEnd?.invoke(record) as Instant?

    /** Pure and idempotent: the same HC record always yields the same DTO. */
    fun map(record: Record): OutRecord {
        val md = record.metadata
        return OutRecord(
            type = id,
            hcId = md.id,
            startTime = startOf(record).toString(),
            endTime = endOf(record)?.toString(),
            startOffsetS = (mStartOffset.invoke(record) as ZoneOffset?)?.totalSeconds,
            endOffsetS = (mEndOffset?.invoke(record) as ZoneOffset?)?.totalSeconds,
            source = OutSource(
                packageName = md.dataOrigin.packageName,
                device = md.device?.toOut(),
                recordingMethod = md.recordingMethodName(),
            ),
            lastModified = md.lastModifiedTime.toString(),
            data = dataMapper(record),
        )
    }
}

private fun Device.toOut() = OutDevice(
    type = when (type) {
        Device.TYPE_WATCH -> "WATCH"
        Device.TYPE_PHONE -> "PHONE"
        Device.TYPE_SCALE -> "SCALE"
        Device.TYPE_RING -> "RING"
        Device.TYPE_HEAD_MOUNTED -> "HEAD_MOUNTED"
        Device.TYPE_FITNESS_BAND -> "FITNESS_BAND"
        Device.TYPE_CHEST_STRAP -> "CHEST_STRAP"
        Device.TYPE_SMART_DISPLAY -> "SMART_DISPLAY"
        else -> "UNKNOWN"
    },
    manufacturer = manufacturer?.take(100),
    model = model?.take(100),
)

private fun Metadata.recordingMethodName(): String = when (recordingMethod) {
    Metadata.RECORDING_METHOD_ACTIVELY_RECORDED -> "active"
    Metadata.RECORDING_METHOD_AUTOMATICALLY_RECORDED -> "automatic"
    Metadata.RECORDING_METHOD_MANUAL_ENTRY -> "manual"
    else -> "unknown"
}
