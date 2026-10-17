//
//  RealtimeSessionServicing.kt
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

/**
 * 定义实时会话 API 与心跳生命周期能力。
 */
internal interface RealtimeSessionServicing {
    /**
     * 创建实时会话并返回 RTC 连接信息。
     *
     * @param model 用于确定服务端能力、地址及输入规格的模型。
     * @return 服务端会话标识、状态及可用的 RTC 连接信息。
     */
    suspend fun createSession(model: RealtimeModel): RealtimeSession

    /**
     * 启动指定会话的周期心跳。
     *
     * @param sessionId 服务端会话标识。
     * @param onFailure 会话续期失败时接收会话标识和错误的回调。
     */
    fun startHeartbeat(
        sessionId: String,
        onFailure: RealtimeSessionHeartbeatFailureHandler,
    )

    /**
     * 停止当前心跳；已经失效的迟到结果不会再触发失败回调。
     */
    suspend fun stopHeartbeat()

    /**
     * 关闭指定实时会话。
     *
     * @param sessionId 服务端会话标识。
     */
    suspend fun closeSession(sessionId: String)
}
