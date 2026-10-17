//
//  RtcEventListener.kt
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
 * 接收 RTC 媒体和数据信令事件。
 */
internal interface RtcEventListener {
    /**
     * 报告无法继续使用的 RTC 房间故障；正常自动重连不触发该通知。
     *
     * @param roomId RTC 房间标识。
     * @param error 本次操作失败的异常或 SDK 错误。
     */
    fun onRoomTerminated(roomId: String, error: ai.xmax.sdk.XmaxError) = Unit

    /**
     * 处理指定房间内远端用户的视频发布状态变化，避免旧房间事件串入新连接。
     *
     * @param stream 由房间和用户标识确定的远端媒体流。
     * @param published 远端视频是否处于发布状态。
     */
    fun onRemoteVideoPublished(
        stream: RemoteStream,
        published: Boolean,
    )

    /**
     * 接收房间内远端用户的结构化任务通知。
     *
     * @param stream 由房间和用户标识确定的远端媒体流。
     * @param message 待发送或记录的消息文本。
     */
    fun onUserMessageReceived(stream: RemoteStream, message: String) = Unit

    /**
     * 处理远端视频流携带的 SEI 消息。
     *
     * @param stream 由房间和用户标识确定的远端媒体流。
     * @param message 待发送或记录的消息文本。
     */
    fun onSeiMessageReceived(
        stream: RemoteStream,
        message: String,
    )
}
