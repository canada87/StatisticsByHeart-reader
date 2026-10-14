package app.sbh.reader.net

import kotlinx.serialization.json.JsonArray

/** Outcome of one HTTP call to the writer. Error variants never carry health values. */
sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>

    /** Non-2xx answer. [code] is the contract's `error.code` when present (e.g. `invalid_token`). */
    data class HttpError(val status: Int, val code: String?, val message: String?) : ApiResult<Nothing>

    /** No usable answer: server unreachable, timeout, TLS problem, cleartext blocked, bad URL, no token. */
    data class NetworkError(val kind: NetworkErrorKind, val message: String?) : ApiResult<Nothing>
}

enum class NetworkErrorKind { NOT_CONFIGURED, BAD_URL, CLEARTEXT_BLOCKED, UNREACHABLE, TIMEOUT, TLS }

data class PingInfo(
    val apiVersion: String,
    val tokenName: String?,
    val tokenPrefix: String?,
    val maxBatchRecords: Int,
    val maxBodyBytes: Long,
    val serverTime: String?,
)

/** Result of an ingest call; [rejected] are permanent per-record failures (never retried). */
data class IngestResult(val received: Int, val accepted: Int, val unchanged: Int, val rejected: Int)

data class ReportError(val code: String, val type: String? = null, val count: Int = 1, val message: String? = null)

data class SyncReportBody(
    val syncId: String,
    val mode: String,
    val status: String,
    val startedAt: String,
    val finishedAt: String,
    val counts: Map<String, Int>,
    val errors: List<ReportError>,
)

/**
 * Client of the writer API (contract v1, `StatisticsByHeart-writer/docs/api-contract.md`).
 * Implementations must be safe to call concurrently and must never log request/response bodies.
 */
interface SbhApi {
    /** `GET /api/v1/health`, no auth: distinguishes "server unreachable" from "token invalid". */
    suspend fun health(): ApiResult<Unit>

    /** `GET /api/v1/ping` with the Bearer token. */
    suspend fun ping(): ApiResult<PingInfo>

    suspend fun postRecords(syncId: String, records: JsonArray): ApiResult<IngestResult>

    suspend fun postDeletions(syncId: String, items: JsonArray): ApiResult<IngestResult>

    suspend fun postDailyAggregates(syncId: String, items: JsonArray): ApiResult<IngestResult>

    suspend fun postSyncReport(report: SyncReportBody): ApiResult<Unit>
}
