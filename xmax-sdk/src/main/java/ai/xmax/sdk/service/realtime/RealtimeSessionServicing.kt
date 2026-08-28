package ai.xmax.sdk.service.realtime

import ai.xmax.sdk.RealtimeModel

/** 定义实时会话 API 与心跳生命周期能力。 */
internal interface RealtimeSessionServicing {
    /** 创建实时会话并返回 RTC 连接信息。 */
    suspend fun createSession(model: RealtimeModel): RealtimeSession

    /** 启动指定会话的周期心跳。 */
    fun startHeartbeat(
        sessionId: String,
        onFailure: RealtimeSessionHeartbeatFailureHandler,
    )

    /** 停止当前心跳；已经失效的迟到结果不会再触发失败回调。 */
    fun stopHeartbeat()

    /** 关闭指定实时会话。 */
    suspend fun closeSession(sessionId: String)
}
