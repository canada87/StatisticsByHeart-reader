package app.sbh.reader.fakes

import app.sbh.reader.net.ApiResult
import app.sbh.reader.net.IngestResult
import app.sbh.reader.net.NetworkErrorKind
import app.sbh.reader.net.PingInfo
import app.sbh.reader.net.SbhApi
import app.sbh.reader.net.SyncReportBody
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Fake writer with real idempotency semantics: upsert on (type, hc_id) keeping the greatest last_modified,
 * soft deletes by hc_id. Failures can be scripted per call number.
 */
class FakeSbhApi : SbhApi {
    /** (type, hc_id) -> last_modified of what the "server" currently holds. */
    val serverRecords = mutableMapOf<Pair<String, String>, String>()
    val deleted = mutableSetOf<String>()
    val aggregates = mutableListOf<String>()
    val reports = mutableListOf<SyncReportBody>()

    /** Every accepted records POST, as sent (to inspect the data of each record). */
    val postedBatches = mutableListOf<JsonArray>()

    /** Every call in order: "records:Steps:3", "deletions:2", "aggregates:5", "report". */
    val calls = mutableListOf<String>()
    var recordPostCount = 0
        private set

    /** Scripted failure for the n-th (1-based) ingest call overall. */
    val failures = mutableMapOf<Int, ApiResult<Nothing>>()
    var maxBatch = Int.MAX_VALUE
    var rejectHcIds = mutableSetOf<String>()
    private var ingestCalls = 0

    override suspend fun health(): ApiResult<Unit> = ApiResult.Ok(Unit)

    override suspend fun ping(): ApiResult<PingInfo> = ApiResult.Ok(PingInfo("1", "test", "sbh_test", 500, 33_554_432, null))

    private fun scripted(size: Int): ApiResult<Nothing>? {
        ingestCalls++
        failures[ingestCalls]?.let { return it }
        if (size > maxBatch) return ApiResult.HttpError(413, "batch_too_large", null)
        return null
    }

    override suspend fun postRecords(syncId: String, records: JsonArray): ApiResult<IngestResult> {
        scripted(records.size)?.let { return it }
        recordPostCount++
        postedBatches += records
        var accepted = 0
        var unchanged = 0
        var rejected = 0
        var type = ""
        for (r in records) {
            val o = r.jsonObject
            type = o["type"]!!.jsonPrimitive.content
            val id = o["hc_id"]!!.jsonPrimitive.content
            if (id in rejectHcIds) { rejected++; continue }
            val modified = o["last_modified"]!!.jsonPrimitive.content
            val key = type to id
            val current = serverRecords[key]
            if (current == null || modified > current) {
                serverRecords[key] = modified
                deleted.remove(id)
                accepted++
            } else {
                unchanged++
            }
        }
        calls += "records:$type:${records.size}"
        return ApiResult.Ok(IngestResult(records.size, accepted, unchanged, rejected))
    }

    override suspend fun postDeletions(syncId: String, items: JsonArray): ApiResult<IngestResult> {
        scripted(items.size)?.let { return it }
        items.forEach { deleted += it.jsonObject["hc_id"]!!.jsonPrimitive.content }
        calls += "deletions:${items.size}"
        return ApiResult.Ok(IngestResult(items.size, items.size, 0, 0))
    }

    override suspend fun postDailyAggregates(syncId: String, items: JsonArray): ApiResult<IngestResult> {
        scripted(items.size)?.let { return it }
        items.forEach { aggregates += it.toString() }
        calls += "aggregates:${items.size}"
        return ApiResult.Ok(IngestResult(items.size, items.size, 0, 0))
    }

    override suspend fun postSyncReport(report: SyncReportBody): ApiResult<Unit> {
        reports += report
        calls += "report"
        return ApiResult.Ok(Unit)
    }

    companion object {
        val networkDown = ApiResult.NetworkError(NetworkErrorKind.UNREACHABLE, "IOException")
        val unauthorized = ApiResult.HttpError(401, "invalid_token", null)
        val serverError = ApiResult.HttpError(503, null, null)
    }
}
