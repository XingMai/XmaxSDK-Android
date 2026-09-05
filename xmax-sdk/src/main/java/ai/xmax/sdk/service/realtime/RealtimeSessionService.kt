package ai.xmax.sdk.service.realtime

import ai.xmax.sdk.RealtimeModel
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import ai.xmax.sdk.service.network.ApiServicing
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/** 管理实时生成会话的创建、心跳维持和关闭。 */
internal class RealtimeSessionService(
    private val apiService: ApiServicing,
    private val heartbeatSleeper: RealtimeHeartbeatSleeper = RealtimeHeartbeatSleeper.live,
    private val heartbeatScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : RealtimeSessionServicing {
    private val heartbeatLock = Any()
    private val heartbeatVersion = AtomicLong(0L)
    private var heartbeatJob: Job? = null

    override suspend fun createSession(model: RealtimeModel): RealtimeSession =
        apiService.post(
            path = "/session",
            body = JSONObject().put("model", model.id),
        ) { payload ->
            makeSession(payload, requiresConnection = true)
        }

    override fun startHeartbeat(
        sessionId: String,
        onFailure: RealtimeSessionHeartbeatFailureHandler,
    ) {
        synchronized(heartbeatLock) {
            val version = heartbeatVersion.incrementAndGet()
            heartbeatJob?.cancel()
            heartbeatJob = heartbeatScope.launch {
                runHeartbeat(
                    HeartbeatContext(
                        version = version,
                        sessionId = sessionId,
                        onFailure = onFailure,
                    ),
                )
            }
        }
    }

    override suspend fun stopHeartbeat() {
        val job = synchronized(heartbeatLock) {
            heartbeatVersion.incrementAndGet()
            heartbeatJob.also { heartbeatJob = null }
        }
        job?.cancelAndJoin()
    }

    override suspend fun closeSession(sessionId: String) {
        apiService.delete("/session/$sessionId")
    }

    private suspend fun runHeartbeat(context: HeartbeatContext) {
        while (isCurrent(context.version)) {
            try {
                heartbeatSleeper.sleep()
                currentCoroutineContext().ensureActive()
                if (!isCurrent(context.version)) return

                val session = heartbeatSession(context.sessionId)
                if (!isCurrent(context.version)) return
                ensureSessionActive(session)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (!invalidate(context.version)) return
                context.onFailure(context.sessionId, XmaxError.from(error))
                return
            }
        }
    }

    private suspend fun heartbeatSession(sessionId: String): RealtimeSession =
        apiService.put("/session/$sessionId/heartbeat") { payload ->
            makeSession(payload, requiresConnection = false)
        }

    private fun ensureSessionActive(session: RealtimeSession) {
        val status = session.status ?: return
        if (status == ACTIVE_STATUS) return
        throw XmaxError(
            code = XmaxErrorCode.SESSION_ERROR,
            message = session.closeReason ?: "Session is no longer active: $status",
        )
    }

    private fun makeSession(
        payload: JSONObject,
        requiresConnection: Boolean,
    ): RealtimeSession {
        val sessionId = payload.nonEmptyString("sessionUid") ?: throw XmaxError(
            code = XmaxErrorCode.SESSION_ERROR,
            message = "Invalid session response",
        )
        val userId = payload.nonEmptyString("userUid")
        val connection = makeConnection(payload.opt("modelExtra"), userId)
        if (requiresConnection && connection == null) {
            throw XmaxError(
                code = XmaxErrorCode.SESSION_ERROR,
                message = "Session does not contain complete RTC join information",
            )
        }

        return RealtimeSession(
            id = sessionId,
            userId = userId,
            status = payload.nonEmptyString("status"),
            connection = connection,
            closeReason = payload.nonEmptyString("closeReason"),
        )
    }

    private fun makeConnection(
        modelExtra: Any?,
        fallbackUserId: String?,
    ): RealtimeSessionConnection? {
        val payload = when (modelExtra) {
            is JSONObject -> modelExtra
            is String -> try {
                JSONObject(modelExtra)
            } catch (_: Exception) {
                return null
            }
            else -> return null
        }

        val roomId = payload.nonEmptyString("room_id") ?: return null
        val token = payload.nonEmptyString("room_token") ?: return null
        val userId = payload.nonEmptyString("user_id") ?: fallbackUserId ?: return null
        return RealtimeSessionConnection(
            roomId = roomId,
            userId = userId,
            token = token,
            botName = payload.nonEmptyString("bot_name"),
        )
    }

    private fun JSONObject.nonEmptyString(key: String): String? =
        (opt(key) as? String)?.trim()?.takeIf(String::isNotEmpty)

    private fun isCurrent(version: Long): Boolean = heartbeatVersion.get() == version

    private fun invalidate(version: Long): Boolean =
        heartbeatVersion.compareAndSet(version, version + 1L)

    private data class HeartbeatContext(
        val version: Long,
        val sessionId: String,
        val onFailure: RealtimeSessionHeartbeatFailureHandler,
    )

    private companion object {
        const val ACTIVE_STATUS = "ACTIVE"
    }
}

/** 提供可替换的心跳等待行为，便于测试周期任务。 */
internal fun interface RealtimeHeartbeatSleeper {
    suspend fun sleep()

    companion object {
        val live: RealtimeHeartbeatSleeper = RealtimeHeartbeatSleeper {
            delay(10_000L)
        }
    }
}
