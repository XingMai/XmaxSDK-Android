package ai.xmax.sdk.foundation.rtc

import ai.xmax.sdk.AudioFrame
import ai.xmax.sdk.CameraPosition
import ai.xmax.sdk.RealtimeCameraPreviewReadyListener
import ai.xmax.sdk.VideoFrame
import ai.xmax.sdk.VideoContentMode
import android.view.View

/** 定义 RTC 引擎生命周期和房间连接能力。 */
internal interface RtcManaging {
    /** 初始化 RTC 引擎。重复调用不会重复获取引擎。 */
    suspend fun initialize()

    /** 离开房间并销毁 RTC 引擎。重复调用安全。 */
    suspend fun destroy()

    /** 应用主视频流编码配置。 */
    fun configureVideoEncoding(configuration: VideoEncodingConfiguration)

    /** 推送一帧外部视频数据及其可选 SEI。 */
    fun pushExternalVideoFrame(
        frame: VideoFrame,
        seiData: ByteArray?,
    )

    /** 推送一帧 10 ms PCM 外部音频数据。 */
    fun pushExternalAudioFrame(frame: AudioFrame)

    /** 按指定格式启动 RTC 内部摄像头采集。 */
    fun startVideoCapture(
        width: Int,
        height: Int,
        frameRate: Int,
    )

    /** 停止 RTC 内部摄像头采集。 */
    fun stopVideoCapture()

    /** 切换 RTC 内部采集使用的摄像头。 */
    fun switchCamera(position: CameraPosition)

    /** 将本地视频绑定到渲染视图。 */
    fun bindLocalVideo(
        view: View,
        contentMode: VideoContentMode,
    )

    /** 解除本地视频与渲染视图的绑定。 */
    fun unbindLocalVideo()

    /** 获取本地视频使用的 RTC 渲染库名称。 */
    val renderLibraryName: String

    /** 加入 RTC 房间，并等待服务端确认加入成功。 */
    suspend fun joinRoom(configuration: RoomJoinConfiguration)

    /** 离开当前或正在加入的 RTC 房间。重复调用安全。 */
    suspend fun leaveRoom()

    /** 发布本地视频流。 */
    fun publishLocalVideo()

    /** 停止发布本地视频流。没有活动房间时安全返回。 */
    fun unpublishLocalVideo()

    /** 发布本地音频流。 */
    fun publishLocalAudio()

    /** 停止发布本地音频流。没有活动房间时安全返回。 */
    fun unpublishLocalAudio()

    /** 更新远端视频流订阅状态。 */
    fun subscribeRemoteVideo(
        userId: String,
        subscribe: Boolean,
    )

    /** 更新远端音频流订阅状态。 */
    fun subscribeRemoteAudio(
        userId: String,
        subscribe: Boolean,
    )

    /** 设置指定远端用户的音频播放音量。 */
    fun setRemoteAudioVolume(
        volume: Int,
        userId: String,
    )

    /** 向当前 RTC 房间发送消息。 */
    fun sendRoomMessage(message: String)

    /** 设置 RTC 事件监听器，传入空值时清除。 */
    fun setEventListener(listener: RtcEventListener?)

    /** 设置 RTC 摄像头预览就绪监听器，传入空值时清除。 */
    fun setCameraPreviewReadyListener(listener: RealtimeCameraPreviewReadyListener?)

    /** 设置 RTC 质量事件监听器，传入空值时清除。 */
    fun setQualityListener(listener: RtcQualityListener?)
}
