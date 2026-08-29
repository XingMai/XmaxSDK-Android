package ai.xmax.sdk.foundation.rtc

/** 隔离第三方 RTC SDK 的引擎能力。 */
internal interface RtcPlatformEngine {
    fun configureVideoEncoding(configuration: VideoEncodingConfiguration): Int

    fun setRemoteAudioVolume(streamId: String, volume: Int): Int

    fun setEventListener(listener: RtcEventListener?)

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

    fun publishLocalVideo(publish: Boolean): Int

    fun publishLocalAudio(publish: Boolean): Int

    fun subscribeRemoteVideo(userId: String, subscribe: Boolean): Int

    fun subscribeRemoteAudio(userId: String, subscribe: Boolean): Int

    fun resolveRemoteStreamId(userId: String): String

    fun sendRoomMessage(message: String): Long

    fun destroy()
}
