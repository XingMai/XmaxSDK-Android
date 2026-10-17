//
//  RtcManaging.kt
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
import ai.xmax.sdk.RealtimeCameraPreviewReadyListener
import ai.xmax.sdk.VideoFrame
import ai.xmax.sdk.VideoContentMode
import android.view.View

/**
 * 定义 RTC 引擎生命周期和房间连接能力。
 */
internal interface RtcManaging {
    /**
     * 初始化 RTC 引擎。重复调用不会重复获取引擎。
     */
    suspend fun initialize()

    /**
     * 离开房间并销毁 RTC 引擎。重复调用安全。
     */
    suspend fun destroy()

    /**
     * 应用主视频流编码配置。
     *
     * @param configuration 上行视频的尺寸、帧率、码率及编码偏好。
     */
    fun configureVideoEncoding(configuration: VideoEncodingConfiguration)

    /**
     * 推送一帧外部视频数据及其可选 SEI。
     *
     * @param frame 待处理、显示或提交的视频帧。
     * @param seiData 附加视频元数据；null 表示不附加，当前 Agora 适配不使用该字段。
     */
    fun pushExternalVideoFrame(
        frame: VideoFrame,
        seiData: ByteArray?,
    )

    /**
     * 推送一帧 10 ms PCM 外部音频数据。
     *
     * @param frame 待播放或提交的 PCM 音频帧。
     */
    fun pushExternalAudioFrame(frame: AudioFrame)

    /**
     * 将主视频切换为 RTC 外部帧输入。
     */
    fun useExternalVideoSource()

    /**
     * 将主音频切换为 RTC 外部 PCM 输入。
     */
    fun startExternalAudioSource()

    /**
     * 停止 RTC 外部音频输入。
     */
    fun stopExternalAudioSource()

    /**
     * 切换到 RTC 内部音频源并启动麦克风采集。
     */
    fun startAudioCapture()

    /**
     * 停止 RTC 内部麦克风采集。
     */
    fun stopAudioCapture()

    /**
     * 按指定格式启动 RTC 内部摄像头采集。
     *
     * @param width 画面宽度，单位为像素。
     * @param height 画面高度，单位为像素。
     * @param frameRate 视频帧率，单位为 fps。
     */
    fun startVideoCapture(
        width: Int,
        height: Int,
        frameRate: Int,
    )

    /**
     * 停止 RTC 内部摄像头采集。
     */
    fun stopVideoCapture()

    /**
     * 切换 RTC 内部采集使用的摄像头。
     *
     * @param position 需要使用的前置或后置摄像头。
     */
    fun switchCamera(position: CameraPosition)

    /**
     * 将本地视频绑定到渲染视图。
     *
     * @param view 需要绑定或解除绑定的显示视图。
     * @param contentMode 视频等比适配或等比裁切的显示方式。
     */
    fun bindLocalVideo(
        view: View,
        contentMode: VideoContentMode,
    )

    /**
     * 解除本地视频与渲染视图的绑定。
     */
    fun unbindLocalVideo()

    /**
     * 将指定远端视频流绑定到渲染视图。
     *
     * @param stream 由房间和用户标识确定的远端媒体流。
     * @param view 需要绑定或解除绑定的显示视图。
     * @param contentMode 视频等比适配或等比裁切的显示方式。
     */
    fun bindRemoteVideo(
        stream: RemoteStream,
        view: View,
        contentMode: VideoContentMode,
    )

    /**
     * 解除指定远端视频流的渲染绑定。
     *
     * @param stream 由房间和用户标识确定的远端媒体流。
     */
    fun unbindRemoteVideo(stream: RemoteStream)

    /**
     * 加入 RTC 房间，并等待服务端确认加入成功。
     *
     * @param configuration 房间标识、用户账号、App ID 和进房 Token。
     */
    suspend fun joinRoom(configuration: RoomJoinConfiguration)

    /**
     * 离开当前或正在加入的 RTC 房间。重复调用安全。
     */
    suspend fun leaveRoom()

    /**
     * 发布本地视频流。
     */
    fun publishLocalVideo()

    /**
     * 停止发布本地视频流。没有活动房间时安全返回。
     */
    fun unpublishLocalVideo()

    /**
     * 发布本地音频流。
     */
    fun publishLocalAudio()

    /**
     * 停止发布本地音频流。没有活动房间时安全返回。
     */
    fun unpublishLocalAudio()

    /**
     * 更新远端视频流订阅状态。
     *
     * @param userId 房间中的业务用户标识。
     * @param subscribe true 订阅媒体，false 取消订阅。
     */
    fun subscribeRemoteVideo(
        userId: String,
        subscribe: Boolean,
    )

    /**
     * 更新远端音频流订阅状态。
     *
     * @param userId 房间中的业务用户标识。
     * @param subscribe true 订阅媒体，false 取消订阅。
     */
    fun subscribeRemoteAudio(
        userId: String,
        subscribe: Boolean,
    )

    /**
     * 设置指定远端用户的音频播放音量。
     *
     * @param volume 远端音频播放音量，范围为 0 到 100。
     * @param userId 房间中的业务用户标识。
     */
    fun setRemoteAudioVolume(
        volume: Int,
        userId: String,
    )

    /**
     * 向当前 RTC 房间发送消息。
     *
     * @param message 待发送或记录的消息文本。
     */
    fun sendRoomMessage(message: String)

    /**
     * 不阻塞主线程地等待已经入队的房间信令提交完成。
     */
    suspend fun awaitPendingMessages() = Unit

    /**
     * 设置 RTC 事件监听器，传入空值时清除。
     *
     * @param listener 接收对应事件的监听器；null 表示注销。
     */
    fun setEventListener(listener: RtcEventListener?)

    /**
     * 设置 RTC 摄像头预览就绪监听器，传入空值时清除。
     *
     * @param listener 接收对应事件的监听器；null 表示注销。
     */
    fun setCameraPreviewReadyListener(listener: RealtimeCameraPreviewReadyListener?)

    /**
     * 为当前生成注册持续帧接收器；清除时同时停止该接收器的远端渲染。
     *
     * @param stream 由房间和用户标识确定的远端媒体流。
     * @param listener 接收对应事件的监听器；null 表示注销。
     */
    fun setRemoteVideoFrameListener(
        stream: RemoteStream,
        listener: RtcRemoteVideoSink?,
    )

    /**
     * 设置 RTC 质量事件监听器，传入空值时清除。
     *
     * @param listener 接收对应事件的监听器；null 表示注销。
     */
    fun setQualityListener(listener: RtcQualityListener?)
}
