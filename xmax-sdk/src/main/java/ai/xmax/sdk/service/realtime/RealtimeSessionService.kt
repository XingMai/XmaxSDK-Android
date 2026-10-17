//
//  RealtimeSessionService.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import ai.xmax.sdk.foundation.rtc.RtcEngineManager
import org.json.JSONObject

/**
 * 管理实时生成会话的创建、心跳维持和关闭。
 *
 * @param apiService 发起服务端 API 请求的客户端。
 * @param heartbeatSleeper 控制会话心跳间隔的挂起组件；默认值为 `RealtimeHeartbeatSleeper.live`。
 * @param heartbeatScope 执行会话心跳的协程作用域。
 */
internal class RealtimeSessionService(
    private val apiService: ApiServicing,
    private val heartbeatSleeper: RealtimeHeartbeatSleeper = RealtimeHeartbeatSleeper.live,
    private val heartbeatScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : RealtimeSessionServicing {
    /**
     * 会话心跳的并发保护。
     */
    private val heartbeatLock = Any()

    /**
     * 当前心跳周期及执行任务。
     */
    private val heartbeatVersion = AtomicLong(0L)
    private var heartbeatJob: Job? = null

    /**
     * 创建实时会话并返回 RTC 连接信息。
     *
     * @param model 用于确定服务端能力、地址及输入规格的模型。
     * @return 服务端会话标识、状态及可用的 RTC 连接信息。
     */
    override suspend fun createSession(model: RealtimeModel): RealtimeSession {
        val payload = apiService.post(
            path = "/session",
            body = JSONObject().put("model", model.id),
        )
        try {
            return makeSession(payload, requiresConnection = true).also(::ensureSessionActive)
        } catch (error: Throwable) {
            payload.nonEmptyString("sessionUid")?.let { id ->
                withContext(NonCancellable) {
                    try {
                        withTimeout(5_000L) { closeSession(id) }
                    } catch (cleanup: Throwable) {
                        if (cleanup !== error) error.addSuppressed(cleanup)
                    }
                }
            }
            throw error
        }
    }

    /**
     * 启动指定会话的周期心跳。
     *
     * @param sessionId 服务端会话标识。
     * @param onFailure 会话续期失败时接收会话标识和错误的回调。
     */
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

    /**
     * 停止当前心跳；已经失效的迟到结果不会再触发失败回调。
     */
    override suspend fun stopHeartbeat() {
        val job = synchronized(heartbeatLock) {
            heartbeatVersion.incrementAndGet()
            heartbeatJob.also { heartbeatJob = null }
        }
        job?.cancelAndJoin()
    }

    /**
     * 关闭指定实时会话。
     *
     * @param sessionId 服务端会话标识。
     */
    override suspend fun closeSession(sessionId: String) {
        apiService.delete("/session/$sessionId")
    }

    /**
     * 在当前会话有效期间持续续期，失败时通知会话故障。
     *
     * @param context 本轮会话心跳的标识和失败通知。
     */
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

    /**
     * 向服务端续期指定会话并返回最新会话信息。
     *
     * @param sessionId 服务端会话标识。
     * @return 服务端会话标识、状态及可用的 RTC 连接信息。
     */
    private suspend fun heartbeatSession(sessionId: String): RealtimeSession =
        apiService.put("/session/$sessionId/heartbeat") { payload ->
            makeSession(payload, requiresConnection = false)
        }

    /**
     * 确认服务端会话仍处于可用状态，否则抛出会话错误。
     *
     * @param session 服务端会话及其连接信息。
     */
    private fun ensureSessionActive(session: RealtimeSession) {
        val status = session.status ?: return
        if (status == ACTIVE_STATUS) return
        throw XmaxError(
            code = XmaxErrorCode.SESSION_ERROR,
            message = session.closeReason ?: "Session is no longer active: $status",
        )
    }

    /**
     * 解析服务端会话数据，并按需校验 RTC 连接信息。
     *
     * @param payload 待解析的服务端业务 JSON 数据。
     * @param requiresConnection 是否要求响应必须包含完整的 RTC 连接信息。
     * @return 服务端会话标识、状态及可用的 RTC 连接信息。
     */
    private fun makeSession(
        payload: JSONObject,
        requiresConnection: Boolean,
    ): RealtimeSession {
        val sessionId = payload.nonEmptyString("sessionUid") ?: throw XmaxError(
            code = XmaxErrorCode.SESSION_ERROR,
            message = "Invalid session response",
        )
        val userId = payload.nonEmptyString("userUid")
        val connection = makeConnection(payload.opt("modelExtra"))
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

    /**
     * 从模型扩展信息中解析 RTC 进房配置。
     *
     * @param modelExtra 包含 RTC 进房信息的模型扩展数据。
     * @return 完整的 RTC 连接信息；扩展字段不可用时为 null。
     */
    private fun makeConnection(
        modelExtra: Any?,
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
        val userId = payload.nonEmptyString("rtc_user_id") ?: return null
        val appId = payload.nonEmptyString("rtc_app_id") ?: return null
        if (appId != RtcEngineManager.DEFAULT_APP_ID) return null
        return RealtimeSessionConnection(
            roomId = roomId,
            userId = userId,
            token = token,
            botName = payload.nonEmptyString("rtc_bot_id") ?: return null,
            appId = appId,
        )
    }

    /**
     * 读取非空字符串字段；缺失或空白时返回 null。
     *
     * @param key JSON 对象中的字段名称。
     * @return 字段的非空字符串值；缺失或空白时为 null。
     */
    private fun JSONObject.nonEmptyString(key: String): String? =
        (opt(key) as? String)?.trim()?.takeIf(String::isNotEmpty)

    /**
     * 判断心跳结果是否仍属于当前会话周期。
     *
     * @param version 用于识别当前任务或回调归属的版本号。
     * @return 任务或房间仍有效时为 true。
     */
    private fun isCurrent(version: Long): Boolean = heartbeatVersion.get() == version

    /**
     * 使指定心跳任务失效，阻止其继续更新会话或触发回调。
     *
     * @param version 用于识别当前任务或回调归属的版本号。
     * @return 本次成功使目标心跳周期失效时为 true。
     */
    private fun invalidate(version: Long): Boolean =
        heartbeatVersion.compareAndSet(version, version + 1L)

    /**
     * 关联一次会话心跳的标识、版本及失败通知。
     *
     * @param version 用于识别当前任务或回调归属的版本号。
     * @param sessionId 服务端会话标识。
     * @param onFailure 会话续期失败时接收会话标识和错误的回调。
     */
    private data class HeartbeatContext(
        val version: Long,
        val sessionId: String,
        val onFailure: RealtimeSessionHeartbeatFailureHandler,
    )

    /**
     * 提供 RealtimeSessionService 使用的构造入口、共享配置或常量。
     */
    private companion object {
        /**
         * 共享常量、默认配置与工厂入口。
         */
        const val ACTIVE_STATUS = "ACTIVE"
    }
}

/**
 * 提供可替换的心跳等待行为，便于测试周期任务。
 */
internal fun interface RealtimeHeartbeatSleeper {
    /**
     * 挂起等待下一次心跳发送时刻。
     */
    suspend fun sleep()

    /**
     * 提供 RealtimeHeartbeatSleeper 使用的构造入口、共享配置或常量。
     */
    companion object {
        /**
         * 共享常量、默认配置与工厂入口。
         */
        val live: RealtimeHeartbeatSleeper = RealtimeHeartbeatSleeper {
            delay(10_000L)
        }
    }
}
