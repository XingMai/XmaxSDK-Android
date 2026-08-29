package ai.xmax.sdk.foundation.rtc

/** 隔离第三方 RTC SDK 的引擎能力。 */
internal interface RtcPlatformEngine {
    fun configureVideoEncoding(configuration: VideoEncodingConfiguration): Int

    fun setQualityListener(listener: RtcQualityListener?)

    fun createRoom(roomId: String): RtcPlatformRoom?
}

/** 隔离第三方 RTC SDK 的房间能力。 */
internal interface RtcPlatformRoom {
    fun setEventListener(
        listener: (
            roomId: String,
            joined: Boolean,
            reason: String?,
        ) -> Unit,
    ): Int

    fun join(configuration: RoomJoinConfiguration): Int

    fun leave(): Int

    fun sendRoomMessage(message: String): Long

    fun destroy()
}
