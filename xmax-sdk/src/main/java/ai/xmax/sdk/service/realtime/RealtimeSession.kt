//
//  RealtimeSession.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.service.realtime

import ai.xmax.sdk.XmaxError

/**
 * Xmax 实时生成会话。
 *
 * @param id 用于区分当前对象或操作的唯一标识。
 * @param userId 房间中的业务用户标识。
 * @param status 服务端会话状态。
 * @param connection 服务端返回的 RTC 房间、用户、鉴权及结果流信息。
 * @param closeReason 服务端会话关闭原因；null 表示未提供。
 */
internal data class RealtimeSession(
    val id: String,
    val userId: String?,
    val status: String?,
    val connection: RealtimeSessionConnection?,
    val closeReason: String?,
)

/**
 * 实时会话对应的 RTC 连接参数。
 *
 * @param roomId RTC 房间标识。
 * @param userId 房间中的业务用户标识。
 * @param token RTC 进房鉴权 Token。
 * @param botName 生成结果发布者的用户标识。
 * @param appId RTC 应用标识，不是应用证书或房间 Token；默认值为 `ai.xmax.sdk.foundation.rtc.RtcEngineManager.DEFAULT_APP_ID`。
 */
internal data class RealtimeSessionConnection(
    val roomId: String,
    val userId: String,
    val token: String,
    val botName: String?,
    val appId: String = ai.xmax.sdk.foundation.rtc.RtcEngineManager.DEFAULT_APP_ID,
)

/**
 * 实时会话心跳失败回调。
 */
internal typealias RealtimeSessionHeartbeatFailureHandler = suspend (
    sessionId: String,
    error: XmaxError,
) -> Unit
