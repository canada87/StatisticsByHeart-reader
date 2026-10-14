package app.sbh.reader.health

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Wire DTO of one Health Connect record, as defined by the writer contract
 * (`StatisticsByHeart-writer/docs/api-contract.md` §2.3). Field names are the JSON names.
 * Unknown fields are rejected by the writer, so do not add any without a contract change.
 */
@Serializable
data class OutRecord(
    val type: String,
    @SerialName("hc_id") val hcId: String,
    @SerialName("start_time") val startTime: String,
    @SerialName("end_time") val endTime: String? = null,
    @SerialName("start_offset_s") val startOffsetS: Int? = null,
    @SerialName("end_offset_s") val endOffsetS: Int? = null,
    val source: OutSource,
    @SerialName("last_modified") val lastModified: String,
    val data: JsonObject,
)

@Serializable
data class OutSource(
    @SerialName("package") val packageName: String,
    val device: OutDevice? = null,
    @SerialName("recording_method") val recordingMethod: String? = null,
)

@Serializable
data class OutDevice(
    val type: String? = null,
    val manufacturer: String? = null,
    val model: String? = null,
)
