package ai.xmax.sdk.foundation.rtc

/**
 * RTC 房间加入参数。
 */
internal data class RoomJoinConfiguration(
    val roomId: String,
    val userId: String,
    val token: String,
    val appId: String = RtcEngineManager.DEFAULT_APP_ID,
)
