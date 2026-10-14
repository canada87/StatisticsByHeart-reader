package app.sbh.reader.net

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.zip.GZIPInputStream

class HttpSbhApiTest {
    private val device = DeviceIdentity("5b0c3c0e-6a7e-4a38-9f0e-0a9d3b1c2d4e", "Xiaomi 14T Pro", "2407FPN8EG", "0.1.0")
    private var config: ServerConfig? = ServerConfig("https://sbh.example.org/", "sbh_secret_token")

    private val seen = mutableListOf<Request>()
    private val bodies = mutableListOf<String>()
    private var answer: (Request) -> Response = { ok(it, "{}") }

    private fun ok(req: Request, body: String, code: Int = 200) = Response.Builder()
        .request(req).protocol(Protocol.HTTP_1_1).code(code).message("x")
        .body(body.toResponseBody("application/json".toMediaType())).build()

    private val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        val req = chain.request()
        seen += req
        bodies += req.body?.let { b ->
            val buf = Buffer().also { b.writeTo(it) }
            if (req.header("Content-Encoding") == "gzip") GZIPInputStream(buf.inputStream()).readBytes().decodeToString() else buf.readUtf8()
        }.orEmpty()
        answer(req)
    }).build()

    private fun api() = HttpSbhApi({ device }, { config }, client)

    private fun records() = buildJsonArray { add(buildJsonObject { put("type", "Steps"); put("hc_id", "x") }) }

    @Test
    fun pingSendsTheBearerTokenToTheNormalizedUrlAndParsesTheAnswer() = runTest {
        answer = {
            ok(it, """{"ok":true,"api_version":"1","server_time":"2026-10-14T09:30:12Z","token":{"name":"Xiaomi","prefix":"sbh_a1"},"limits":{"max_batch_records":500,"max_body_bytes":33554432}}""")
        }

        val r = api().ping() as ApiResult.Ok

        assertEquals("https://sbh.example.org/api/v1/ping", seen.single().url.toString())
        assertEquals("Bearer sbh_secret_token", seen.single().header("Authorization"))
        assertEquals("Xiaomi", r.value.tokenName)
        assertEquals(500, r.value.maxBatchRecords)
    }

    @Test
    fun healthIsCalledWithoutCredentials() = runTest {
        answer = { ok(it, """{"status":"ok"}""") }
        assertTrue(api().health() is ApiResult.Ok)
        assertEquals("https://sbh.example.org/api/v1/health", seen.single().url.toString())
        assertNull(seen.single().header("Authorization"))
    }

    @Test
    fun recordsAreGzippedAndWrappedInTheContractEnvelope() = runTest {
        answer = { ok(it, """{"received":1,"inserted":1,"updated":0,"upserted":1,"unchanged":0,"rejected":[]}""") }

        val r = api().postRecords("sync-1", records()) as ApiResult.Ok

        assertEquals("gzip", seen.single().header("Content-Encoding"))
        val body = Json.parseToJsonElement(bodies.single()).jsonObject
        assertEquals(device.deviceId, body["device_id"]!!.jsonPrimitive.content)
        assertEquals("sync-1", body["sync_id"]!!.jsonPrimitive.content)
        assertEquals("0.1.0", body["device"]!!.jsonObject["app_version"]!!.jsonPrimitive.content)
        assertEquals(1, body["records"]!!.jsonArray.size)
        assertEquals(1, r.value.accepted)
        assertEquals(0, r.value.rejected)
    }

    @Test
    fun rejectedRecordsAreCounted() = runTest {
        answer = { ok(it, """{"received":2,"upserted":1,"unchanged":0,"rejected":[{"index":1,"hc_id":"b","reason":"invalid_record"}]}""") }
        val r = api().postRecords("s", records()) as ApiResult.Ok
        assertEquals(1, r.value.rejected)
    }

    @Test
    fun deletionsAndAggregatesUseTheItemsEnvelope() = runTest {
        answer = { ok(it, """{"received":1,"deleted":1,"already_deleted":0,"not_found":0,"rejected":[]}""") }
        api().postDeletions("s", buildJsonArray { add(buildJsonObject { put("hc_id", "x") }) })
        api().postDailyAggregates("s", JsonArray(emptyList()))

        assertTrue(seen[0].url.encodedPath.endsWith("/ingest/deletions"))
        assertTrue(seen[1].url.encodedPath.endsWith("/ingest/daily-aggregates"))
        val body = Json.parseToJsonElement(bodies[0]).jsonObject
        assertEquals(setOf("device_id", "sync_id", "items"), body.keys)
    }

    @Test
    fun errorBodiesAreMappedToHttpErrorWithTheContractCode() = runTest {
        answer = { ok(it, """{"error":{"code":"token_revoked","message":"revoked"}}""", 401) }
        val r = api().ping() as ApiResult.HttpError
        assertEquals(401, r.status)
        assertEquals("token_revoked", r.code)
    }

    @Test
    fun unreachableServerIsANetworkError() = runTest {
        answer = { throw java.net.ConnectException("down") }
        val r = api().ping() as ApiResult.NetworkError
        assertEquals(NetworkErrorKind.UNREACHABLE, r.kind)
        assertFalse("error details never contain request data", r.message.orEmpty().contains("sbh_secret"))
    }

    @Test
    fun missingConfigurationIsReportedWithoutAnyNetworkCall() = runTest {
        config = null
        assertEquals(NetworkErrorKind.NOT_CONFIGURED, (api().ping() as ApiResult.NetworkError).kind)
        config = ServerConfig("https://x.org", null)
        assertEquals(NetworkErrorKind.NOT_CONFIGURED, (api().ping() as ApiResult.NetworkError).kind)
        config = ServerConfig("not a url", "t")
        assertEquals(NetworkErrorKind.BAD_URL, (api().ping() as ApiResult.NetworkError).kind)
        assertTrue(seen.isEmpty())
    }

    @Test
    fun ioFailuresDoNotLeakAsExceptions() = runTest {
        answer = { throw IOException("boom") }
        assertTrue(api().postRecords("s", records()) is ApiResult.NetworkError)
    }
}
