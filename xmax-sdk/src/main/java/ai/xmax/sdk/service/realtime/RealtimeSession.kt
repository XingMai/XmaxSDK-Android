package ai.xmax.sdk.service.realtime

import ai.xmax.sdk.XmaxError

/** Xmax 实时生成会话。 */
internal data class RealtimeSession(
    val id: String,
    val userId: String?,
    val status: String?,
    val connection: RealtimeSessionConnection?,
    val closeReason: String?,
)

/** 实时会话对应的 RTC 连接参数。 */
internal data class RealtimeSessionConnection(
    val roomId: String,
    val userId: String,
    val token: String,
    val botName: String?,
)

/** 实时会话心跳失败回调。 */
internal typealias RealtimeSessionHeartbeatFailureHandler = suspend (
    sessionId: String,
    error: XmaxError,
) -> Unit
