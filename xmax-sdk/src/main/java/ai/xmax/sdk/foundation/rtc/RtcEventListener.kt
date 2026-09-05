package ai.xmax.sdk.foundation.rtc

/** 接收 RTC 媒体和数据信令事件。 */
internal interface RtcEventListener {
    /** A confirmed terminal room failure, distinct from vendor reconnect warnings. */
    fun onRoomTerminated(roomId: String, error: ai.xmax.sdk.XmaxError) = Unit

    /** 处理远端用户的视频发布状态变化。 */
    fun onRemoteVideoPublished(
        userId: String,
        published: Boolean,
    )

    /** 处理远端视频流携带的 SEI 消息。 */
    fun onSeiMessageReceived(
        stream: RemoteStream,
        message: String,
    )
}
