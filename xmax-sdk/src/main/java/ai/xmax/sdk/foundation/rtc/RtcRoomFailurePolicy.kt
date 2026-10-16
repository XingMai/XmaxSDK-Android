package ai.xmax.sdk.foundation.rtc

/**
 * 声网自动重连期间不终止业务会话；明确失败、鉴权或发送错误才结束连接。
 */
internal fun isRetryableRoomFailure(reason: String?): Boolean = reason == "RECONNECT"

internal fun isTerminalRoomFailure(reason: String?): Boolean = reason in setOf(
    "AGORA_CONNECTION_FAILED",
    "AGORA_SEND_FAILED",
    "INVALID_TOKEN",
    "TOKEN_EXPIRED",
)
