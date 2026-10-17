//
//  RtcRoomFailurePolicy.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.foundation.rtc

/**
 * 声网自动重连期间不终止业务会话；明确失败、鉴权或发送错误才结束连接。
 *
 * @param reason 引起状态变化或失败的原因。
 * @return RTC 可继续重连的状态为 true，否则为 false。
 */
internal fun isRetryableRoomFailure(reason: String?): Boolean = reason == "RECONNECT"

/**
 * 判断房间状态原因是否表示必须终止连接的故障。
 *
 * @param reason 引起状态变化或失败的原因。
 * @return 需要结束当前 RTC 连接时为 true。
 */
internal fun isTerminalRoomFailure(reason: String?): Boolean = reason in setOf(
    "AGORA_CONNECTION_FAILED",
    "AGORA_SEND_FAILED",
    "INVALID_TOKEN",
    "TOKEN_EXPIRED",
)
