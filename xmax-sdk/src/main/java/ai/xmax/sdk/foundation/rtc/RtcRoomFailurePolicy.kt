package ai.xmax.sdk.foundation.rtc

/** The vendor retries join/reconnect warnings; only explicit terminal reasons end a live session. */
internal fun isRetryableRoomFailure(reason: String?): Boolean =
    reason == "JOIN_ROOM_FAILED" || reason == "RECONNECT" || reason?.substringBefore(':') == "-2001"

internal fun isTerminalRoomFailure(reason: String?): Boolean =
    reason in terminalRoomReasons || reason?.substringBefore(':')?.toIntOrNull() in terminalRoomCodes

private val terminalRoomReasons = setOf(
    "INVALID_TOKEN", "TOKEN_EXPIRED", "UPDATE_TOKEN_WITH_INVALID_TOKEN", "ROOM_FORBIDDEN",
    "USER_FORBIDDEN", "KICKED_OUT", "ROOM_DISMISS", "DUPLICATE_LOGIN",
    "WITHOUT_LICENSE_AUTHENTICATE_SDK", "SERVER_LICENSE_EXPIRED", "EXCEEDS_THE_UPPER_LIMIT",
    "LICENSE_PARAMETER_ERROR", "LICENSE_FILE_PATH_ERROR", "LICENSE_ILLEGAL", "LICENSE_EXPIRED",
    "LICENSE_INFORMATION_NOT_MATCH", "LICENSE_NOT_MATCH_WITH_CACHE", "LICENSE_FUNCTION_NOT_FOUND",
    "STATE_ABNORMAL_SERVER_STATUS",
)
private val terminalRoomCodes = setOf(-1000, -1001, -1004, -1006, -1009, -1010, -1011, -1084)
