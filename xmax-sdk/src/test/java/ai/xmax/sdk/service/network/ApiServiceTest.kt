package ai.xmax.sdk.service.network

import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

public class ApiServiceTest {
    @Test
    public fun `GET sends normalized authenticated request and decodes payload`() = runTest {
        val transport = RecordingTransport {
            response(
                status = 200,
                envelope = JSONObject()
                    .put("success", true)
                    .put("data", JSONObject().put("value", "ok")),
            )
        }
        val service = ApiService(
            apiKey = "  secret-key  ",
            baseUrl = "https://api.example.test/v1/",
            timeoutMs = 2_500,
            transport = transport,
        )

        val value = service.get("status") { it.getString("value") }

        assertEquals("ok", value)
        val request = transport.requests.single()
        assertEquals(ApiMethod.GET, request.method)
        assertEquals("https://api.example.test/v1/status", request.url.toString())
        assertEquals("secret-key", request.headers["X-Api-Key"])
        assertEquals("application/json", request.headers["Accept"])
        assertEquals(2_500, request.connectTimeoutMs)
        assertEquals(2_500, request.readTimeoutMs)
        assertNull(request.body)
    }

    @Test
    public fun `POST PUT and DELETE preserve methods and request bodies`() = runTest {
        val transport = RecordingTransport {
            response(200, successEnvelope())
        }
        val service = ApiService("key", transport = transport)
        val body = JSONObject().put("prompt", "hello")

        service.post("/session", body)
        service.put("/session/id/heartbeat")
        service.delete("/session/id")

        assertEquals(
            listOf(ApiMethod.POST, ApiMethod.PUT, ApiMethod.DELETE),
            transport.requests.map(ApiHttpRequest::method),
        )
        assertEquals("hello", JSONObject(transport.requests[0].body!!.toString(Charsets.UTF_8)).getString("prompt"))
        assertNull(transport.requests[1].body)
        assertNull(transport.requests[2].body)
    }

    @Test
    public fun `invalid API key fails before transport`() = runTest {
        val transport = RecordingTransport { response(200, successEnvelope()) }
        val service = ApiService("  ", transport = transport)

        val error = expectXmaxError { service.get("/status") }

        assertEquals(XmaxErrorCode.INVALID_API_KEY, error.code)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    public fun `invalid base URL and absolute request paths are rejected`() = runTest {
        val transport = RecordingTransport { response(200, successEnvelope()) }
        val invalidBase = ApiService(
            apiKey = "key",
            baseUrl = "http://api.example.test",
            transport = transport,
        )
        val validService = ApiService("key", transport = transport)

        val configurationError = expectXmaxError { invalidBase.get("/status") }
        val pathError = expectXmaxError { validService.get("https://other.example/status") }

        assertEquals(XmaxErrorCode.INVALID_CONFIGURATION, configurationError.code)
        assertEquals(XmaxErrorCode.INVALID_CONFIGURATION, pathError.code)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    public fun `API failure preserves business and HTTP status`() = runTest {
        val transport = RecordingTransport {
            response(
                status = 403,
                envelope = JSONObject()
                    .put("success", false)
                    .put("code", 10_403)
                    .put("message", "Access denied"),
            )
        }
        val service = ApiService("key", transport = transport)

        val error = expectXmaxError { service.get("/protected") }

        assertEquals(XmaxErrorCode.API_ERROR, error.code)
        assertEquals("Access denied", error.message)
        assertEquals(10_403, error.apiCode)
        assertEquals(403, error.httpStatus)
    }

    @Test
    public fun `invalid JSON and missing data are API errors`() = runTest {
        val responses = ArrayDeque(
            listOf(
                ApiHttpResponse(200, "not-json".toByteArray()),
                response(
                    200,
                    JSONObject().put("success", true),
                ),
            ),
        )
        val service = ApiService(
            apiKey = "key",
            transport = RecordingTransport { responses.removeFirst() },
        )

        val jsonError = expectXmaxError { service.get("/first") }
        val dataError = expectXmaxError { service.get("/second") }

        assertEquals(XmaxErrorCode.API_ERROR, jsonError.code)
        assertEquals("Server returned invalid JSON", jsonError.message)
        assertEquals(XmaxErrorCode.API_ERROR, dataError.code)
        assertEquals("Server returned invalid response data", dataError.message)
    }

    @Test
    public fun `transport failures are business errors and cancellation is preserved`() = runTest {
        val networkService = ApiService(
            apiKey = "key",
            transport = RecordingTransport { throw IOException("offline") },
        )
        val cancelledService = ApiService(
            apiKey = "key",
            transport = RecordingTransport { throw CancellationException("cancelled") },
        )

        val networkError = expectXmaxError { networkService.get("/status") }
        val cancelledError = try {
            cancelledService.get("/status")
            throw AssertionError("Expected CancellationException")
        } catch (error: CancellationException) { error }

        assertEquals(XmaxErrorCode.NETWORK_ERROR, networkError.code)
        assertTrue(networkError.message!!.contains("offline"))
        assertFalse(cancelledError.message.isNullOrBlank())
    }

    @Test
    public fun `typed decoder failures become API errors`() = runTest {
        val service = ApiService(
            apiKey = "key",
            transport = RecordingTransport { response(200, successEnvelope()) },
        )

        val error = expectXmaxError {
            service.get("/status") { it.getString("missing") }
        }

        assertEquals(XmaxErrorCode.API_ERROR, error.code)
        assertTrue(error.message!!.startsWith("Server returned invalid response data"))
    }

    @Test
    public fun `API log message contains metadata without authentication or body`() {
        val message = ApiLogger.responseMessage(
            method = ApiMethod.POST,
            path = "/session",
            statusCode = 201,
            bodyByteCount = 128,
            durationMs = 42,
        )

        assertTrue(message.contains("POST /session"))
        assertTrue(message.contains("201"))
        assertTrue(message.contains("42 ms"))
        assertTrue(message.contains("128 bytes"))
        assertFalse(message.contains("X-Api-Key"))
        assertFalse(message.contains("Authorization"))
    }

    private suspend fun expectXmaxError(block: suspend () -> Unit): XmaxError {
        return try {
            block()
            throw AssertionError("Expected XmaxError")
        } catch (error: XmaxError) {
            error
        }
    }

    private fun successEnvelope(): JSONObject = JSONObject()
        .put("success", true)
        .put("data", JSONObject())

    private fun response(status: Int, envelope: JSONObject): ApiHttpResponse =
        ApiHttpResponse(status, envelope.toString().toByteArray(Charsets.UTF_8))
}

private class RecordingTransport(
    private val response: suspend (ApiHttpRequest) -> ApiHttpResponse,
) : ApiTransport {
    val requests = mutableListOf<ApiHttpRequest>()

    override suspend fun execute(request: ApiHttpRequest): ApiHttpResponse {
        requests += request
        return response(request)
    }
}
