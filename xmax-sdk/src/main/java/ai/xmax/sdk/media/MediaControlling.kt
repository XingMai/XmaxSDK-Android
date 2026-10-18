//
//  MediaControlling.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.media

import ai.xmax.sdk.CameraPosition
import ai.xmax.sdk.RealtimeCameraPreviewReadyListener
import ai.xmax.sdk.RealtimeMediaStream
import ai.xmax.sdk.RealtimeReferenceVideo
import ai.xmax.sdk.RealtimeVideoSampleMethod
import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.RealtimeVideoTrack
import ai.xmax.sdk.media.interaction.InteractionControlling
import ai.xmax.sdk.RealtimeExternalVideoSource
import android.graphics.Bitmap
import android.net.Uri

/**
 * 定义媒体层向 Core 暴露的本地媒体能力。
 */
internal interface MediaControlling : InteractionControlling {
    /**
     * 当前媒体轨道、规格、音轨及来源信息。
     */
    val currentTrack: RealtimeVideoTrack?

    val currentVideoFormat: RealtimeVideoFormat?

    val hasAudio: Boolean

    val networkVideoReference: RealtimeReferenceVideo?

    val networkVideoFinishHandler: (() -> Unit)?

    /**
     * 创建由服务端直接读取 URL 的网络视频源；仅支持 HTTP/HTTPS。
     * 本地预览静音播放一次，不发布本地音视频；生成首帧就绪后订阅远端音频。
     * [onFinish] 在当前任务收到服务端完成事件且首帧就绪后于主线程调用一次，
     * 不代表远端尾帧已经显示完毕。创建源不会开始生成；资源由 [close] 统一释放。
     *
     * @param uri 应用有读取权限的媒体地址。
     * @param videoFormat 视频尺寸、帧率及上行编码参数。
     * @param sampleMethod 服务端读取网络视频时采用的采样方式。
     * @param onFinish 网络视频任务完成的通知；null 表示不接收完成通知。
     * @return 可用于预览或生成的媒体流及其视频轨道。
     */
    suspend fun createNetworkVideoStream(
        uri: Uri,
        videoFormat: RealtimeVideoFormat,
        sampleMethod: RealtimeVideoSampleMethod,
        onFinish: (() -> Unit)?,
    ): RealtimeMediaStream

    /**
     * 当前媒体轨道、规格、音轨及来源信息。
     */
    val isExternalVideo: Boolean

    /**
     * 当前是否为本地解码后通过 RTC 上传的视频文件。
     */
    val isLocalVideo: Boolean get() = false

    /**
     * 当前是否为持续通过 RTC 上传图片帧的本地图片。
     */
    val isLocalImage: Boolean get() = false

    /**
     * 设置 RTC 摄像头预览就绪监听器，传入空值时清除。
     *
     * @param listener 接收对应事件的监听器；null 表示注销。
     */
    fun setCameraPreviewReadyListener(listener: RealtimeCameraPreviewReadyListener?)

    /**
     * 创建并启动相机输入；接入方须先取得所需权限，输入尺寸会按模型规则调整。
     * 麦克风默认关闭；启用后在连接时开始采集、断开时停止，不进行本地回放。
     *
     * @param videoFormat 视频尺寸、帧率及上行编码参数。
     * @param position 需要使用的前置或后置摄像头。
     * @param useMicrophone 是否启用麦克风；启用前须由应用取得录音权限；默认值为 `false`。
     * @return 可用于预览或生成的媒体流及其视频轨道。
     */
    suspend fun createLocalCameraStream(
        videoFormat: RealtimeVideoFormat,
        position: CameraPosition,
        useMicrophone: Boolean = false,
    ): RealtimeMediaStream

    /**
     * 启动当前相机源的麦克风采集。
     */
    fun startMicrophoneCapture()

    /**
     * 停止当前相机源的麦克风采集。
     */
    fun stopMicrophoneCapture()

    /**
     * 停止当前相机输入并释放其资源；当前源不是相机时不执行释放。
     */
    suspend fun stopLocalCameraStream()

    /**
     * 解码图片字节并创建保持媒体比例、持续输出图片帧的本地流。
     * 使用方向校正后的图片尺寸（受模型面积上限约束并按 4 对齐）及模型默认帧率。
     * 模型生成画布独立选择，服务端等比适配并裁去补边后输出。
     *
     * @param imageData 可解码的图片文件字节，不是裸像素数据。
     * @return 可用于预览或生成的媒体流及其视频轨道。
     */
    suspend fun createLocalImageStream(
        imageData: ByteArray,
    ): RealtimeMediaStream

    /**
     * 从 Bitmap 创建保持媒体比例的本地图片流。
     * 使用 Bitmap 尺寸（受模型面积上限约束并按 4 对齐）及模型默认帧率，模型生成画布独立选择。
     *
     * @param bitmap 待处理或显示的 Android 图片。
     * @return 可用于预览或生成的媒体流及其视频轨道。
     */
    suspend fun createLocalImageStream(
        bitmap: Bitmap,
    ): RealtimeMediaStream

    /**
     * 从可读取的 URI 创建本地图片流；读取权限由接入方保证。
     * 使用方向校正后的图片尺寸（受模型面积上限约束并按 4 对齐）及模型默认帧率。
     * 模型生成画布独立选择，服务端等比适配并裁去补边后输出。
     *
     * @param uri 应用有读取权限的媒体地址。
     * @return 可用于预览或生成的媒体流及其视频轨道。
     */
    suspend fun createLocalImageStream(
        uri: Uri,
    ): RealtimeMediaStream

    /**
     * 停止当前图片流并释放其资源；当前源不是图片时不执行释放。
     */
    suspend fun stopLocalImageStream()

    /**
     * 从 URI 创建本地视频流；存在音轨时同时提供视频文件的音频输入。
     * 使用方向校正后的素材尺寸（受模型面积上限约束并按 4 对齐）和模型默认帧率。
     * SDK 单独选择生成画布，服务端等比适配并裁边输出。
     *
     * @param uri 应用有读取权限的媒体地址。
     * @return 可用于预览或生成的媒体流及其视频轨道。
     */
    suspend fun createLocalVideoStream(
        uri: Uri,
    ): RealtimeMediaStream

    /**
     * 绑定接入方自行采集、解码或处理的视频源；SDK 不负责摄像头或播放器的启停和释放。
     * 默认仅视频，音频和 SDK 容器内的本地预览由 source 按需提供。
     * 未指定 videoFormat 时使用源视频尺寸和模型默认帧率；最终尺寸按模型规则校验、对齐。
     * 外部源须按 sink.videoFormat 输出帧。
     *
     * @param source 接入方负责启停及释放的外部音视频源。
     * @param videoFormat 媒体输入的尺寸、帧率及可选上行编码配置；null 表示使用媒体解析结果或来源默认值。
     * @return 可用于预览或生成的媒体流及其视频轨道。
     */
    suspend fun createExternalVideoStream(
        source: RealtimeExternalVideoSource,
        videoFormat: RealtimeVideoFormat?,
    ): RealtimeMediaStream

    /**
     * 解绑外部输入，停止前须断开生成连接；不关闭接入方的摄像头或播放器。
     */
    suspend fun stopExternalVideoStream()

    /**
     * 只替换外部输入，保留引擎；准备回调执行前已解绑旧供帧入口。
     *
     * @param videoFormat 媒体输入的尺寸、帧率及可选上行编码配置；null 表示使用媒体解析结果或来源默认值。
     * @param targetSource 创建替换用外部视频源的挂起函数。
     * @return 可用于预览或生成的媒体流及其视频轨道。
     */
    suspend fun replaceExternalVideoStream(
        videoFormat: RealtimeVideoFormat?,
        targetSource: suspend () -> RealtimeExternalVideoSource,
    ): RealtimeMediaStream

    /**
     * 停止当前视频流并释放解码与预览资源；当前源不是视频时不执行释放。
     */
    suspend fun stopLocalVideoStream()

    /**
     * 设置本地预览是否静音，不改变保存的音量值。
     *
     * @param muted 是否静音。
     */
    suspend fun setLocalAudioPreviewMuted(muted: Boolean)

    /**
     * 设置当前本地媒体的预览音量，范围为 0 到 1，不影响提交给 RTC 的音频数据。
     *
     * @param volume 播放音量，范围为 0 到 1。
     */
    suspend fun setLocalAudioVolume(volume: Float)

    /**
     * 切换当前相机输入的方向，并更新本地轨道信息。
     *
     * @return 可用于预览或生成的媒体流及其视频轨道。
     */
    suspend fun switchCamera(): RealtimeMediaStream

    /**
     * 停止当前本地媒体源并释放其资源。
     */
    suspend fun stopLocalStream()

    /**
     * 判断媒体流是否由当前控制器创建并持有。
     *
     * @param stream 当前本地媒体或远端结果媒体流。
     * @return 当前控制器持有该媒体流时为 true。
     */
    fun owns(stream: RealtimeMediaStream): Boolean
}
