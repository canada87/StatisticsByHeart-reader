package app.sbh.reader.net

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownServiceException
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import javax.net.ssl.SSLException
import kotlin.coroutines.resume

/** Where and as whom to talk to the writer. */
data class ServerConfig(val baseUrl: String, val token: String?)

data class DeviceIdentity(val deviceId: String, val name: String, val model: String, val appVersion: String)

/**
 * OkHttp implementation of [SbhApi]. Bodies are gzip-compressed JSON; nothing from a body is ever logged.
 * [configProvider] is read on every call so that settings changes apply immediately.
 */
class HttpSbhApi(
    private val deviceProvider: suspend () -> DeviceIdentity,
    private val configProvider: suspend () -> ServerConfig?,
    private val client: OkHttpClient = defaultClient(),
) : SbhApi {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun health(): ApiResult<Unit> =
        call(authenticated = false) { base -> Request.Builder().url("$base/api/v1/health").get().build() }
            .mapOk { }

    override suspend fun ping(): ApiResult<PingInfo> =
        call(authenticated = true) { base -> Request.Builder().url("$base/api/v1/ping").get().build() }
            .mapOk { body ->
                val o = parse(body)
                val limits = o["limits"]?.jsonObject
                val token = o["token"]?.jsonObject
                PingInfo(
                    apiVersion = o["api_version"]?.jsonPrimitive?.contentOrNull ?: "?",
                    tokenName = token?.get("name")?.jsonPrimitive?.contentOrNull,
                    tokenPrefix = token?.get("prefix")?.jsonPrimitive?.contentOrNull,
                    maxBatchRecords = limits?.get("max_batch_records")?.jsonPrimitive?.intOrNull ?: DEFAULT_MAX_BATCH,
                    maxBodyBytes = limits?.get("max_body_bytes")?.jsonPrimitive?.longOrNull ?: DEFAULT_MAX_BODY,
                    serverTime = o["server_time"]?.jsonPrimitive?.contentOrNull,
                )
            }

    override suspend fun postRecords(syncId: String, records: JsonArray): ApiResult<IngestResult> {
        val device = deviceProvider()
        val body = buildJsonObject {
            put("device_id", device.deviceId)
            put("sync_id", syncId)
            put("device", buildJsonObject {
                put("name", device.name.take(100))
                put("model", device.model.take(100))
                put("app_version", device.appVersion.take(100))
            })
            put("records", records)
        }
        return post("/api/v1/ingest/records", body).mapOk { b ->
            val o = parse(b)
            IngestResult(
                received = o.int("received"),
                accepted = o.int("upserted"),
                unchanged = o.int("unchanged"),
                rejected = (o["rejected"] as? JsonArray)?.size ?: 0,
            )
        }
    }

    override suspend fun postDeletions(syncId: String, items: JsonArray): ApiResult<IngestResult> =
        post("/api/v1/ingest/deletions", envelope(syncId, items)).mapOk { b ->
            val o = parse(b)
            IngestResult(
                received = o.int("received"),
                accepted = o.int("deleted"),
                unchanged = o.int("already_deleted") + o.int("not_found"),
                rejected = (o["rejected"] as? JsonArray)?.size ?: 0,
            )
        }

    override suspend fun postDailyAggregates(syncId: String, items: JsonArray): ApiResult<IngestResult> =
        post("/api/v1/ingest/daily-aggregates", envelope(syncId, items)).mapOk { b ->
            val o = parse(b)
            IngestResult(
                received = o.int("received"),
                accepted = o.int("upserted"),
                unchanged = o.int("unchanged"),
                rejected = (o["rejected"] as? JsonArray)?.size ?: 0,
            )
        }

    override suspend fun postSyncReport(report: SyncReportBody): ApiResult<Unit> {
        val body = buildJsonObject {
            put("device_id", deviceProvider().deviceId)
            put("sync_id", report.syncId)
            put("mode", report.mode)
            put("status", report.status)
            put("started_at", report.startedAt)
            put("finished_at", report.finishedAt)
            put("counts", buildJsonObject { report.counts.entries.take(50).forEach { (k, v) -> put(k, v) } })
            put("errors", buildJsonArray {
                report.errors.take(50).forEach { e ->
                    add(buildJsonObject {
                        put("code", e.code)
                        e.type?.let { put("type", it) }
                        put("count", e.count)
                        // Only technical messages (exception class names); never health values.
                        e.message?.let { put("message", it.take(500)) }
                    })
                }
            })
        }
        return post("/api/v1/ingest/sync-report", body).mapOk { }
    }

    private suspend fun envelope(syncId: String, items: JsonArray): JsonObject = buildJsonObject {
        put("device_id", deviceProvider().deviceId)
        put("sync_id", syncId)
        put("items", items)
    }

    private suspend fun post(path: String, body: JsonObject): ApiResult<String> {
        val bytes = gzip(json.encodeToString(JsonObject.serializer(), body).toByteArray(Charsets.UTF_8))
        return call(authenticated = true) { base ->
            Request.Builder()
                .url(base + path)
                .header("Content-Encoding", "gzip")
                .post(bytes.toRequestBody(JSON))
                .build()
        }
    }

    private suspend fun call(authenticated: Boolean, build: (String) -> Request): ApiResult<String> {
        val config = configProvider() ?: return ApiResult.NetworkError(NetworkErrorKind.NOT_CONFIGURED, null)
        val base = UrlNormalizer.normalize(config.baseUrl)
            ?: return ApiResult.NetworkError(NetworkErrorKind.BAD_URL, null)
        if (authenticated && config.token.isNullOrBlank()) {
            return ApiResult.NetworkError(NetworkErrorKind.NOT_CONFIGURED, null)
        }
        val userAgentVersion = deviceProvider().appVersion
        val request = try {
            build(base).newBuilder().apply {
                header("Accept", "application/json")
                header("User-Agent", "StatisticsByHeart-reader/$userAgentVersion")
                if (authenticated) header("Authorization", "Bearer ${config.token}")
            }.build()
        } catch (_: IllegalArgumentException) {
            return ApiResult.NetworkError(NetworkErrorKind.BAD_URL, null)
        }
        return try {
            client.newCall(request).await().use { resp ->
                val text = resp.body.string()
                if (resp.isSuccessful) {
                    ApiResult.Ok(text)
                } else {
                    val err = runCatching { parse(text)["error"]?.jsonObject }.getOrNull()
                    ApiResult.HttpError(
                        status = resp.code,
                        code = err?.get("code")?.jsonPrimitive?.contentOrNull,
                        message = err?.get("message")?.jsonPrimitive?.contentOrNull,
                    )
                }
            }
        } catch (e: UnknownServiceException) {
            ApiResult.NetworkError(NetworkErrorKind.CLEARTEXT_BLOCKED, e.javaClass.simpleName)
        } catch (e: SocketTimeoutException) {
            ApiResult.NetworkError(NetworkErrorKind.TIMEOUT, e.javaClass.simpleName)
        } catch (e: SSLException) {
            ApiResult.NetworkError(NetworkErrorKind.TLS, e.javaClass.simpleName)
        } catch (e: IOException) {
            ApiResult.NetworkError(NetworkErrorKind.UNREACHABLE, e.javaClass.simpleName)
        }
    }

    private fun parse(text: String): JsonObject = json.parseToJsonElement(text).jsonObject

    private fun JsonObject.int(key: String): Int = this[key]?.jsonPrimitive?.intOrNull ?: 0

    private inline fun <T> ApiResult<String>.mapOk(transform: (String) -> T): ApiResult<T> = when (this) {
        is ApiResult.Ok -> try {
            ApiResult.Ok(transform(value))
        } catch (e: Exception) {
            // A 2xx we cannot parse: treat as transient, the idempotent request is simply retried.
            if (e is kotlinx.serialization.SerializationException || e is IllegalArgumentException) {
                ApiResult.NetworkError(NetworkErrorKind.UNREACHABLE, "bad_response")
            } else {
                throw e
            }
        }
        is ApiResult.HttpError -> this
        is ApiResult.NetworkError -> this
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWith(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) cont.resume(response) else response.close()
            }
        })
        cont.invokeOnCancellation { cancel() }
    }

    companion object {
        const val DEFAULT_MAX_BATCH = 500
        const val DEFAULT_MAX_BODY = 33_554_432L
        private val JSON = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        fun gzip(data: ByteArray): ByteArray {
            val out = ByteArrayOutputStream(data.size / 4 + 32)
            GZIPOutputStream(out).use { it.write(data) }
            return out.toByteArray()
        }
    }
}
