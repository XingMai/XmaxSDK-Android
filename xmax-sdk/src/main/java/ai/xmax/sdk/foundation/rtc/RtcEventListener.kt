package ai.xmax.sdk.foundation.rtc

/** 接收 RTC 媒体和数据信令事件。 */
internal interface RtcEventListener {
    /** A confirmed terminal room failure, distinct from vendor reconnect warnings. */
    fun onRoomTerminated(roomId: String, error: ai.xmax.sdk.XmaxError) = Unit

    /** 处理指定房间内远端用户的视频发布状态变化，避免旧房间事件串入新连接。 */
    fun onRemoteVideoPublished(
        stream: RemoteStream,
        published: Boolean,
    )

    /** 接收房间内远端用户的结构化任务通知。 */
    fun onUserMessageReceived(stream: RemoteStream, message: String) = Unit

    /** 处理远端视频流携带的 SEI 消息。 */
    fun onSeiMessageReceived(
        stream: RemoteStream,
        message: String,
    )
}
