//
//  RtcPlatform.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.foundation.rtc

import ai.xmax.sdk.AudioFrame
import ai.xmax.sdk.CameraPosition
import ai.xmax.sdk.VideoContentMode
import ai.xmax.sdk.VideoFrame
import android.view.View

/**
 * 隔离第三方 RTC SDK 的引擎能力。
 */
internal interface RtcPlatform {
    /**
     * 应用主视频流编码配置。
     *
     * @param configuration 上行视频的尺寸、帧率、码率及编码偏好。
     * @return RTC 操作返回码；非负表示已接受，负值表示失败。
     */
    fun configureVideoEncoding(configuration: VideoEncodingConfiguration): Int

    /**
     * 推送一帧外部视频数据及其可选 SEI。
     *
     * @param frame 待处理、显示或提交的视频帧。
     * @param seiData 附加视频元数据；null 表示不附加，当前 Agora 适配不使用该字段。
     */
    fun pushExternalVideoFrame(frame: VideoFrame, seiData: ByteArray?)

    /**
     * 推送一帧 10 ms PCM 外部音频数据。
     *
     * @param frame 待播放或提交的 PCM 音频帧。
     */
    fun pushExternalAudioFrame(frame: AudioFrame)

    /**
     * 将主视频切换为 RTC 外部帧输入。
     *
     * @return RTC 操作返回码；非负表示已接受，负值表示失败。
     */
    fun useExternalVideoSource(): Int

    /**
     * 将主音频切换为 RTC 外部 PCM 输入。
     *
     * @return RTC 操作返回码；非负表示已接受，负值表示失败。
     */
    fun startExternalAudioSource(): Int

    /**
     * 停止 RTC 外部音频输入。
     *
     * @return RTC 操作返回码；非负表示已接受，负值表示失败。
     */
    fun stopExternalAudioSource(): Int

    /**
     * 切换到 RTC 内部音频源并启动麦克风采集。
     *
     * @return RTC 操作返回码；非负表示已接受，负值表示失败。
     */
    fun startAudioCapture(): Int

    /**
     * 停止 RTC 内部麦克风采集。
     *
     * @return RTC 操作返回码；非负表示已接受，负值表示失败。
     */
    fun stopAudioCapture(): Int

    /**
     * 按指定格式启动 RTC 内部摄像头采集。
     *
     * @param width 画面宽度，单位为像素。
     * @param height 画面高度，单位为像素。
     * @param frameRate 视频帧率，单位为 fps。
     * @return RTC 操作返回码；非负表示已接受，负值表示失败。
     */
    fun startVideoCapture(width: Int, height: Int, frameRate: Int): Int

    /**
     * 停止 RTC 内部摄像头采集。
     *
     * @return RTC 操作返回码；非负表示已接受，负值表示失败。
     */
    fun stopVideoCapture(): Int

    /**
     * 切换 RTC 内部采集使用的摄像头。
     *
     * @param position 需要使用的前置或后置摄像头。
     * @return RTC 操作返回码；非负表示已接受，负值表示失败。
     */
    fun switchCamera(position: CameraPosition): Int

    /**
     * 将本地视频绑定到渲染视图。
     *
     * @param view 需要绑定或解除绑定的显示视图。
     * @param contentMode 视频等比适配或等比裁切的显示方式。
     * @return RTC 操作返回码；非负表示已接受，负值表示失败。
     */
    fun bindLocalVideo(view: View, contentMode: VideoContentMode): Int

    /**
     * 解除本地视频与渲染视图的绑定。
     *
     * @return RTC 操作返回码；非负表示已接受，负值表示失败。
     */
    fun unbindLocalVideo(): Int

    /**
     * 将指定远端用户的视频绑定到视图并应用显示模式。
     *
     * @param userId 房间中的业务用户标识。
     * @param view 需要绑定或解除绑定的显示视图。
     * @param contentMode 视频等比适配或等比裁切的显示方式。
     * @return RTC 操作返回码；非负表示已接受，负值表示失败。
     */
    fun bindRemoteVideo(userId: String, view: View, contentMode: VideoContentMode): Int

    /**
     * 解除指定远端用户的视频视图绑定。
     *
     * @param userId 房间中的业务用户标识。
     * @return RTC 操作返回码；非负表示已接受，负值表示失败。
     */
    fun unbindRemoteVideo(userId: String): Int

    /**
     * 替换相机预览就绪监听器；null 表示注销。
     *
     * @param listener 接收对应事件的监听器；null 表示注销。
     */
    fun setCameraPreviewReadyListener(listener: (() -> Unit)?)

    /**
     * 替换指定远端流的视频帧监听器。
     *
     * @param streamId 远端媒体流或数据通道的标识。
     * @param listener 接收对应事件的监听器；null 表示注销。
     * @return RTC 操作返回码；非负表示已接受，负值表示失败。
     */
    fun setRemoteVideoFrameListener(
        streamId: String,
        listener: RtcRemoteVideoSink?,
    ): Int

    /**
     * 设置指定远端用户的音频播放音量。
     *
     * @param streamId 远端媒体流或数据通道的标识。
     * @param volume 远端音频播放音量，范围为 0 到 100。
     * @return RTC 操作返回码；非负表示已接受，负值表示失败。
     */
    fun setRemoteAudioVolume(streamId: String, volume: Int): Int

    /**
     * 设置 RTC 事件监听器，传入空值时清除。
     *
     * @param listener 接收对应事件的监听器；null 表示注销。
     */
    fun setEventListener(listener: RtcEventListener?)

    /**
     * 设置 RTC 质量事件监听器，传入空值时清除。
     *
     * @param listener 接收对应事件的监听器；null 表示注销。
     */
    fun setQualityListener(listener: RtcQualityListener?)

    /**
     * 创建用于加入指定 RTC 房间的操作对象。
     *
     * @param roomId RTC 房间标识。
     * @return 指定房间的 RTC 操作对象；无法创建时为 null。
     */
    fun createRoom(roomId: String): RtcRoom?
}

/**
 * 隔离第三方 RTC SDK 的房间能力。
 */
internal interface RtcRoom {
    /**
     * 替换 RTC 房间或引擎事件监听器。
     *
     * @param listener 接收对应事件的监听器。
     * @return RTC 操作返回码；非负表示已接受，负值表示失败。
     */
    fun setEventListener(
        listener: (
            roomId: String,
            joined: Boolean,
            reason: String?,
        ) -> Unit,
    ): Int

    /**
     * 使用业务用户账号加入 RTC 房间。
     *
     * @param configuration 房间标识、用户账号、App ID 和进房 Token。
     * @return RTC 操作返回码；非负表示已接受，负值表示失败。
     */
    fun join(configuration: RoomJoinConfiguration): Int

    /**
     * 离开当前 RTC 房间。
     *
     * @return RTC 操作返回码；非负表示已接受，负值表示失败。
     */
    fun leave(): Int

    /**
     * 按发布开关更新本地视频发布状态。
     *
     * @param publish true 发布媒体，false 撤销发布。
     * @return RTC 操作返回码；非负表示已接受，负值表示失败。
     */
    fun publishLocalVideo(publish: Boolean): Int

    /**
     * 按发布开关更新本地音频发布状态。
     *
     * @param publish true 发布媒体，false 撤销发布。
     * @return RTC 操作返回码；非负表示已接受，负值表示失败。
     */
    fun publishLocalAudio(publish: Boolean): Int

    /**
     * 更新远端视频流订阅状态。
     *
     * @param userId 房间中的业务用户标识。
     * @param subscribe true 订阅媒体，false 取消订阅。
     * @return RTC 操作返回码；非负表示已接受，负值表示失败。
     */
    fun subscribeRemoteVideo(userId: String, subscribe: Boolean): Int

    /**
     * 更新远端音频流订阅状态。
     *
     * @param userId 房间中的业务用户标识。
     * @param subscribe true 订阅媒体，false 取消订阅。
     * @return RTC 操作返回码；非负表示已接受，负值表示失败。
     */
    fun subscribeRemoteAudio(userId: String, subscribe: Boolean): Int

    /**
     * 取得用于订阅和渲染指定远端用户的流标识。
     *
     * @param userId 房间中的业务用户标识。
     * @return 可用于远端订阅及渲染的流标识。
     */
    fun resolveRemoteStreamId(userId: String): String

    /**
     * 向当前 RTC 房间发送消息。
     *
     * @param message 待发送或记录的消息文本。
     * @param replacingPendingMessage 合并键；同键且尚未发送的信令仅保留最新内容。
     * @return RTC 返回的消息标识或错误码。
     */
    fun sendRoomMessage(message: String, replacingPendingMessage: String? = null): Long

    /**
     * 丢弃尚未开始发送的可合并信令。
     *
     * @param key 要清理的合并键；null 表示清理全部可合并信令。
     */
    fun discardPendingMessages(key: String? = null)

    /**
     * 等待此前排队的信令真正提交给 RTC，离房或发送失败时抛出异常。
     */
    suspend fun awaitPendingMessages() = Unit

    /**
     * 销毁当前房间对象并释放其监听器与异步任务。
     */
    fun destroy()
}
