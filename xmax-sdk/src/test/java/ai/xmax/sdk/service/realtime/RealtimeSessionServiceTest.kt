package ai.xmax.sdk.service.realtime

import ai.xmax.sdk.RealtimeModel
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import ai.xmax.sdk.service.network.ApiMethod
import ai.xmax.sdk.service.network.ApiServicing
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
public class RealtimeSessionServiceTest {
    @Test
    public fun `create session parses object connection and normalizes values`() = runTest {
        val apiService = RealtimeApiServiceStub(
            ApiBehavior.Success(
                JSONObject()
                    .put("sessionUid", " session-1 ")
                    .put("userUid", " user-1 ")
                    .put("status", " ACTIVE ")
                    .put(
                        "modelExtra",
                        JSONObject()
                            .put("room_id", " room-1 ")
                            .put("room_token", " token-1 ")
                            .put("user_id", " rtc-user-1 ")
                            .put("bot_name", " bot-1 "),
                    ),
            ),
        )
        val service = RealtimeSessionService(apiService)

        val session = service.createSession(RealtimeModel.X2_0)

        assertEquals(
            RealtimeSession(
                id = "session-1",
                userId = "user-1",
                status = "ACTIVE",
                connection = RealtimeSessionConnection(
                    roomId = "room-1",
                    userId = "rtc-user-1",
                    token = "token-1",
                    botName = "bot-1",
                ),
                closeReason = null,
            ),
            session,
        )
        val request = apiService.requests.single()
        assertEquals(ApiMethod.POST, request.method)
        assertEquals("/session", request.path)
        assertEquals("x2.0", JSONObject(request.body!!).getString("model"))
    }

    @Test
    public fun `create session parses JSON string and falls back to session user`() = runTest {
        val apiService = RealtimeApiServiceStub(
            ApiBehavior.Success(
                JSONObject()
                    .put("sessionUid", "session-2")
                    .put("userUid", "user-2")
                    .put(
                        "modelExtra",
                        """{"room_id":"room-2","room_token":"token-2"}""",
                    ),
            ),
        )
        val service = RealtimeSessionService(apiService)

        val session = service.createSession(RealtimeModel.X2_0)

        assertEquals(
            RealtimeSessionConnection(
                roomId = "room-2",
                userId = "user-2",
                token = "token-2",
                botName = null,
            ),
            session.connection,
        )
    }

    @Test
    public fun `create session rejects missing identifier`() = runTest {
        val service = RealtimeSessionService(
            RealtimeApiServiceStub(ApiBehavior.Success(JSONObject())),
        )

        val error = expectXmaxError {
            service.createSession(RealtimeModel.X2_0)
        }

        assertEquals(XmaxErrorCode.SESSION_ERROR, error.code)
        assertEquals("Invalid session response", error.message)
    }

    @Test
    public fun `create session rejects incomplete connection`() = runTest {
        val service = RealtimeSessionService(
            RealtimeApiServiceStub(
                ApiBehavior.Success(
                    JSONObject()
                        .put("sessionUid", "session-3")
                        .put("modelExtra", JSONObject().put("room_id", "room-3")),
                ),
            ),
        )

        val error = expectXmaxError {
            service.createSession(RealtimeModel.X2_0)
        }

        assertEquals(XmaxErrorCode.SESSION_ERROR, error.code)
        assertEquals(
            "Session does not contain complete RTC join information",
            error.message,
        )
    }

    @Test
    public fun `close session uses delete endpoint`() = runTest {
        val apiService = RealtimeApiServiceStub(
            ApiBehavior.Success(JSONObject()),
        )
        val service = RealtimeSessionService(apiService)

        service.closeSession("session-4")

        assertEquals(
            RealtimeApiRequest(
                method = ApiMethod.DELETE,
                path = "/session/session-4",
                body = null,
            ),
            apiService.requests.single(),
        )
    }

    @Test
    public fun `heartbeat reports inactive session and stops cycle`() = runTest {
        val apiService = RealtimeApiServiceStub(
            ApiBehavior.Success(
                JSONObject()
                    .put("sessionUid", "session-5")
                    .put("status", "CLOSED")
                    .put("closeReason", "generation ended"),
            ),
        )
        val sleeper = ManualHeartbeatSleeper()
        val failure = CompletableDeferred<Pair<String, XmaxError>>()
        val service = RealtimeSessionService(
            apiService = apiService,
            heartbeatSleeper = sleeper,
            heartbeatScope = this,
        )

        service.startHeartbeat("session-5") { sessionId, error ->
            failure.complete(sessionId to error)
        }
        sleeper.resume()
        advanceUntilIdle()

        val (sessionId, error) = failure.await()
        assertEquals("session-5", sessionId)
        assertEquals(XmaxErrorCode.SESSION_ERROR, error.code)
        assertEquals("generation ended", error.message)
        assertEquals(ApiMethod.PUT, apiService.requests.single().method)
        assertEquals(
            "/session/session-5/heartbeat",
            apiService.requests.single().path,
        )
        service.stopHeartbeat()
    }

    @Test
    public fun `active heartbeat continues into next cycle`() = runTest {
        val apiService = RealtimeApiServiceStub(
            ApiBehavior.Success(
                JSONObject().put("sessionUid", "session-active").put("status", "ACTIVE"),
            ),
            ApiBehavior.Success(
                JSONObject().put("sessionUid", "session-active"),
            ),
        )
        val sleeper = ManualHeartbeatSleeper()
        val reportedFailure = AtomicBoolean(false)
        val service = RealtimeSessionService(
            apiService = apiService,
            heartbeatSleeper = sleeper,
            heartbeatScope = this,
        )

        service.startHeartbeat("session-active") { _, _ ->
            reportedFailure.set(true)
        }
        sleeper.resume()
        runCurrent()
        sleeper.resume()
        runCurrent()

        assertEquals(2, apiService.requests.size)
        assertFalse(reportedFailure.get())
        service.stopHeartbeat()
        advanceUntilIdle()
    }

    @Test
    public fun `heartbeat forwards API failure and stops cycle`() = runTest {
        val expectedError = XmaxError(
            code = XmaxErrorCode.NETWORK_ERROR,
            message = "offline",
        )
        val apiService = RealtimeApiServiceStub(
            ApiBehavior.Failure(expectedError),
        )
        val sleeper = ManualHeartbeatSleeper()
        val failure = CompletableDeferred<Pair<String, XmaxError>>()
        val service = RealtimeSessionService(
            apiService = apiService,
            heartbeatSleeper = sleeper,
            heartbeatScope = this,
        )

        service.startHeartbeat("session-network") { sessionId, error ->
            failure.complete(sessionId to error)
        }
        sleeper.resume()
        advanceUntilIdle()

        val (sessionId, error) = failure.await()
        assertEquals("session-network", sessionId)
        assertTrue(error === expectedError)
        assertEquals(1, apiService.requests.size)
        service.stopHeartbeat()
    }

    @Test
    public fun `stopped heartbeat ignores late request failure`() = runTest {
        val pendingResponse = PendingApiResponse()
        val apiService = RealtimeApiServiceStub(
            ApiBehavior.Pending(pendingResponse),
        )
        val sleeper = ManualHeartbeatSleeper()
        val reportedFailure = AtomicBoolean(false)
        val service = RealtimeSessionService(
            apiService = apiService,
            heartbeatSleeper = sleeper,
            heartbeatScope = this,
        )

        service.startHeartbeat("session-6") { _, _ ->
            reportedFailure.set(true)
        }
        sleeper.resume()
        runCurrent()
        assertTrue(pendingResponse.started.isCompleted)

        val stopped = async { service.stopHeartbeat() }
        runCurrent()
        assertFalse(stopped.isCompleted)
        pendingResponse.resolve(
            Result.failure(
                XmaxError(XmaxErrorCode.NETWORK_ERROR, "late failure"),
            ),
        )
        advanceUntilIdle()

        stopped.await()
        assertFalse(reportedFailure.get())
        assertEquals(1, apiService.requests.size)
    }

    private suspend fun expectXmaxError(block: suspend () -> Unit): XmaxError = try {
        block()
        throw AssertionError("Expected XmaxError")
    } catch (error: XmaxError) {
        error
    }
}

private data class RealtimeApiRequest(
    val method: ApiMethod,
    val path: String,
    val body: String?,
)

private class RealtimeApiServiceStub(
    vararg behaviors: ApiBehavior,
) : ApiServicing {
    private val lock = Any()
    private val storedBehaviors = ArrayDeque(behaviors.toList())
    private val storedRequests = mutableListOf<RealtimeApiRequest>()

    val requests: List<RealtimeApiRequest>
        get() = synchronized(lock) { storedRequests.toList() }

    override suspend fun request(
        method: ApiMethod,
        path: String,
        body: JSONObject?,
    ): JSONObject {
        val behavior = synchronized(lock) {
            storedRequests += RealtimeApiRequest(method, path, body?.toString())
            check(storedBehaviors.isNotEmpty()) { "Missing test API response" }
            storedBehaviors.removeFirst()
        }
        return when (behavior) {
            is ApiBehavior.Success -> behavior.payload
            is ApiBehavior.Failure -> throw behavior.error
            is ApiBehavior.Pending -> behavior.response.value()
        }
    }
}

private sealed interface ApiBehavior {
    data class Success(val payload: JSONObject) : ApiBehavior
    data class Failure(val error: XmaxError) : ApiBehavior
    data class Pending(val response: PendingApiResponse) : ApiBehavior
}

private class PendingApiResponse {
    val started = CompletableDeferred<Unit>()
    private val result = CompletableDeferred<Result<JSONObject>>()

    suspend fun value(): JSONObject {
        started.complete(Unit)
        return withContext(NonCancellable) {
            result.await().getOrThrow()
        }
    }

    fun resolve(value: Result<JSONObject>) {
        result.complete(value)
    }
}

private class ManualHeartbeatSleeper : RealtimeHeartbeatSleeper {
    private val resumes = Channel<Unit>(Channel.UNLIMITED)

    override suspend fun sleep() {
        resumes.receive()
    }

    fun resume() {
        resumes.trySend(Unit).getOrThrow()
    }
}
