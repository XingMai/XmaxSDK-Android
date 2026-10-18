//
//  MediaController.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.media

import ai.xmax.sdk.cleanupResources
import ai.xmax.sdk.cleanupAfterFailure
import ai.xmax.sdk.CameraPosition
import ai.xmax.sdk.RealtimeCameraPreviewReadyListener
import ai.xmax.sdk.RealtimeMediaStream
import ai.xmax.sdk.RealtimeReferenceVideo
import ai.xmax.sdk.RealtimeVideoSampleMethod
import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.RealtimeVideoTrack
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import ai.xmax.sdk.foundation.rtc.RtcManaging
import ai.xmax.sdk.media.camera.CameraController
import ai.xmax.sdk.media.image.ImageController
import ai.xmax.sdk.media.interaction.InteractionController
import ai.xmax.sdk.media.interaction.InteractionFrame
import ai.xmax.sdk.media.video.NetworkVideoController
import ai.xmax.sdk.media.video.VideoController
import ai.xmax.sdk.RealtimeExternalVideoSource
import ai.xmax.sdk.media.external.ExternalVideoController
import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.withLock

/**
 * 统一协调本地媒体所有权以及 RTC Engine 生命周期。
 *
 * @param rtcManager 提供 RTC 采集、发布、订阅和渲染能力的管理器。
 * @param cameraController 本地相机输入控制器。
 * @param imageController 本地图片输入控制器。
 * @param videoController 本地视频输入控制器。
 * @param externalController 接入方自采集视频输入控制器。
 * @param networkVideoController 服务端读取网络视频的输入控制器。
 * @param interactionController 提交触控轨迹的控制器。
 */
internal class MediaController(
    private val rtcManager: RtcManaging,
    private val cameraController: CameraController,
    private val imageController: ImageController? = null,
    private val videoController: VideoController? = null,
    private val externalController: ExternalVideoController? = null,
    private val networkVideoController: NetworkVideoController? = null,
    private val interactionController: InteractionController = InteractionController(),
) : MediaControlling {
    /**
     * 本地媒体操作与状态访问的并发保护。
     */
    private val operationMutex = Mutex()
    private val stateLock = Any()

    /**
     * 当前媒体来源及对外提供的输入快照。
     */
    private var activeSource: LocalMediaKind? = null

    override val currentTrack: RealtimeVideoTrack?
        get() = when (synchronized(stateLock) { activeSource }) {
            LocalMediaKind.CAMERA -> cameraController.currentTrack
            LocalMediaKind.IMAGE -> imageController?.currentTrack
            LocalMediaKind.EXTERNAL -> externalController?.currentTrack
            LocalMediaKind.VIDEO -> videoController?.currentTrack
            LocalMediaKind.NETWORK_VIDEO -> networkVideoController?.currentTrack
            null -> null
        }

    override val currentVideoFormat: RealtimeVideoFormat?
        get() = currentTrack?.videoFormat

    override val hasAudio: Boolean
        get() = when (synchronized(stateLock) { activeSource }) {
            LocalMediaKind.CAMERA -> cameraController.useMicrophone
            LocalMediaKind.EXTERNAL -> externalController?.hasAudio == true
            LocalMediaKind.VIDEO -> videoController?.hasAudio == true
            else -> false
        }

    override val networkVideoReference: RealtimeReferenceVideo?
        get() = if (synchronized(stateLock) { activeSource == LocalMediaKind.NETWORK_VIDEO }) {
            networkVideoController?.reference
        } else null

    override val networkVideoFinishHandler: (() -> Unit)?
        get() = networkVideoController?.onFinish

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
    override suspend fun createNetworkVideoStream(
        uri: Uri,
        videoFormat: RealtimeVideoFormat,
        sampleMethod: RealtimeVideoSampleMethod,
        onFinish: (() -> Unit)?,
    ): RealtimeMediaStream = createSource(LocalMediaKind.NETWORK_VIDEO) {
        checkNotNull(networkVideoController).create(uri, videoFormat, sampleMethod, onFinish)
    }

    /**
     * 当前媒体来源及对外提供的输入快照。
     */
    override val isExternalVideo: Boolean
        get() = synchronized(stateLock) { activeSource == LocalMediaKind.EXTERNAL }

    /**
     * 设置 RTC 摄像头预览就绪监听器，传入空值时清除。
     *
     * @param listener 接收对应事件的监听器；null 表示注销。
     */
    override fun setCameraPreviewReadyListener(listener: RealtimeCameraPreviewReadyListener?) {
        cameraController.setPreviewReadyListener(listener)
    }

    /**
     * 为指定生成任务启用交互轨迹提交。
     *
     * @param taskId 用于关联本次生成请求和结果流的任务标识。
     * @param videoFormat 视频尺寸、帧率及上行编码参数。
     */
    override suspend fun startInteraction(
        taskId: String,
        videoFormat: RealtimeVideoFormat,
    ) {
        interactionController.startInteraction(taskId, videoFormat)
    }

    /**
     * 停止当前任务的交互轨迹提交并丢弃未发送的数据。
     */
    override suspend fun stopInteraction() {
        interactionController.stopInteraction()
    }

    /**
     * 提交当前显示画面对应的触控轨迹。
     *
     * @param frame 当前触控轨迹及显示画面尺寸。
     */
    override fun submitInteraction(frame: InteractionFrame) {
        interactionController.submitInteraction(frame)
    }

    /**
     * 创建并启动相机输入；接入方须先取得所需权限，输入尺寸会按模型规则调整。
     * 麦克风默认关闭；启用后在连接时开始采集、断开时停止，不进行本地回放。
     *
     * @param videoFormat 视频尺寸、帧率及上行编码参数。
     * @param position 需要使用的前置或后置摄像头。
     * @param useMicrophone 是否启用麦克风；启用前须由应用取得录音权限。
     * @return 可用于预览或生成的媒体流及其视频轨道。
     */
    override suspend fun createLocalCameraStream(
        videoFormat: RealtimeVideoFormat,
        position: CameraPosition,
        useMicrophone: Boolean,
    ): RealtimeMediaStream = createSource(LocalMediaKind.CAMERA) {
        cameraController.createLocalCameraStream(videoFormat, position, useMicrophone)
    }

    /**
     * 启动当前相机源的麦克风采集。
     */
    override fun startMicrophoneCapture() {
        if (synchronized(stateLock) { activeSource } == LocalMediaKind.CAMERA) {
            cameraController.startMicrophoneCapture()
        }
    }

    /**
     * 停止当前相机源的麦克风采集。
     */
    override fun stopMicrophoneCapture() {
        if (synchronized(stateLock) { activeSource } == LocalMediaKind.CAMERA) {
            cameraController.stopMicrophoneCapture()
        }
    }

    /**
     * 解码图片字节并创建保持媒体比例、持续输出图片帧的本地流。
     * 使用方向校正后的图片尺寸（受模型面积上限约束并按 4 对齐）及模型默认帧率。
     * 模型生成画布独立选择，服务端等比适配并裁去补边后输出。
     *
     * @param imageData 可解码的图片文件字节，不是裸像素数据。
     * @return 可用于预览或生成的媒体流及其视频轨道。
     */
    override suspend fun createLocalImageStream(
        imageData: ByteArray,
    ): RealtimeMediaStream = createSource(LocalMediaKind.IMAGE) {
        requiredImageController().createLocalImageStream(imageData)
    }

    /**
     * 从 Bitmap 创建保持媒体比例的本地图片流。
     * 使用 Bitmap 尺寸（受模型面积上限约束并按 4 对齐）及模型默认帧率，模型生成画布独立选择。
     *
     * @param bitmap 待处理或显示的 Android 图片。
     * @return 可用于预览或生成的媒体流及其视频轨道。
     */
    override suspend fun createLocalImageStream(
        bitmap: Bitmap,
    ): RealtimeMediaStream = createSource(LocalMediaKind.IMAGE) {
        requiredImageController().createLocalImageStream(bitmap)
    }

    /**
     * 从可读取的 URI 创建本地图片流；读取权限由接入方保证。
     * 使用方向校正后的图片尺寸（受模型面积上限约束并按 4 对齐）及模型默认帧率。
     * 模型生成画布独立选择，服务端等比适配并裁去补边后输出。
     *
     * @param uri 应用有读取权限的媒体地址。
     * @return 可用于预览或生成的媒体流及其视频轨道。
     */
    override suspend fun createLocalImageStream(
        uri: Uri,
    ): RealtimeMediaStream = createSource(LocalMediaKind.IMAGE) {
        requiredImageController().createLocalImageStream(uri)
    }

    /**
     * 停止当前相机输入并释放其资源；当前源不是相机时不执行释放。
     */
    override suspend fun stopLocalCameraStream() {
        stopSource(LocalMediaKind.CAMERA)
    }

    /**
     * 停止当前图片流并释放其资源；当前源不是图片时不执行释放。
     */
    override suspend fun stopLocalImageStream() {
        stopSource(LocalMediaKind.IMAGE)
    }

    /**
     * 当前媒体来源及对外提供的输入快照。
     */
    override val isLocalVideo: Boolean
        get() = synchronized(stateLock) { activeSource == LocalMediaKind.VIDEO }

    override val isLocalImage: Boolean
        get() = synchronized(stateLock) { activeSource == LocalMediaKind.IMAGE }

    /**
     * 从 URI 创建本地视频流；存在音轨时同时提供视频文件的音频输入。
     * 使用方向校正后的素材尺寸（受模型面积上限约束并按 4 对齐）和模型默认帧率。
     * SDK 单独选择生成画布，服务端等比适配并裁边输出。
     *
     * @param uri 应用有读取权限的媒体地址。
     * @return 可用于预览或生成的媒体流及其视频轨道。
     */
    override suspend fun createLocalVideoStream(
        uri: Uri,
    ): RealtimeMediaStream = createSource(LocalMediaKind.VIDEO) {
        requiredVideoController().createLocalVideoStream(uri)
    }

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
    override suspend fun createExternalVideoStream(
        source: RealtimeExternalVideoSource,
        videoFormat: RealtimeVideoFormat?,
    ): RealtimeMediaStream =
        createSource(LocalMediaKind.EXTERNAL) { checkNotNull(externalController).create(source, videoFormat) }

    /**
     * 解绑外部输入，停止前须断开生成连接；不关闭接入方的摄像头或播放器。
     */
    override suspend fun stopExternalVideoStream() { stopSource(LocalMediaKind.EXTERNAL) }

    /**
     * 切流不销毁引擎；失败时保留媒体类型，供后续重试或完整清理正确回收资源。
     *
     * @param videoFormat 媒体输入的尺寸、帧率及可选上行编码配置；null 表示使用媒体解析结果或来源默认值。
     * @param targetSource 创建替换用外部视频源的挂起函数。
     * @return 可用于预览或生成的媒体流及其视频轨道。
     */
    override suspend fun replaceExternalVideoStream(
        videoFormat: RealtimeVideoFormat?,
        targetSource: suspend () -> RealtimeExternalVideoSource,
    ): RealtimeMediaStream = operationMutex.withLock {
        check(isExternalVideo) { "The current local media source is not an external video" }
        val controller = checkNotNull(externalController)
        controller.stop()
        val source = targetSource()
        currentCoroutineContext().ensureActive()
        controller.create(source, videoFormat)
    }

    /**
     * 停止当前视频流并释放解码与预览资源；当前源不是视频时不执行释放。
     */
    override suspend fun stopLocalVideoStream() {
        stopSource(LocalMediaKind.VIDEO)
    }

    /**
     * 设置本地预览是否静音，不改变保存的音量值。
     *
     * @param muted 是否静音。
     */
    override suspend fun setLocalAudioPreviewMuted(muted: Boolean) {
        if (synchronized(stateLock) { activeSource } == LocalMediaKind.EXTERNAL) {
            externalController?.setAudio(muted = muted)
        }
        if (synchronized(stateLock) { activeSource } == LocalMediaKind.VIDEO) {
            videoController?.setLocalAudioPreviewMuted(muted)
        }
    }

    /**
     * 设置当前本地媒体的预览音量，范围为 0 到 1，不影响提交给 RTC 的音频数据。
     *
     * @param volume 播放音量，范围为 0 到 1。
     */
    override suspend fun setLocalAudioVolume(volume: Float) {
        videoController?.setLocalAudioVolume(volume)
        externalController?.setAudio(volume = volume)
    }

    /**
     * 切换当前相机输入的方向，并更新本地轨道信息。
     *
     * @return 可用于预览或生成的媒体流及其视频轨道。
     */
    override suspend fun switchCamera(): RealtimeMediaStream = operationMutex.withLock {
        if (synchronized(stateLock) { activeSource } != LocalMediaKind.CAMERA) {
            throw XmaxError(
                XmaxErrorCode.INVALID_CONFIGURATION,
                "The current local media source is not a camera",
            )
        }
        cameraController.switchCamera()
    }

    /**
     * 停止当前本地媒体源并释放其资源。
     */
    override suspend fun stopLocalStream() = operationMutex.withLock {
        synchronized(stateLock) { activeSource }?.let { stopSourceLocked(it) }
        Unit
    }

    /**
     * 判断媒体流是否由当前控制器创建并持有。
     *
     * @param stream 当前本地媒体或远端结果媒体流。
     * @return 当前控制器持有该媒体流时为 true。
     */
    override fun owns(stream: RealtimeMediaStream): Boolean =
        stream.videoTrack != null && stream.videoTrack === currentTrack

    /**
     * 创建指定类型的本地媒体源并记录其归属。
     *
     * @param kind 本次操作或媒体源的类型。
     * @param create 创建本地媒体流的挂起函数。
     * @return 可用于预览或生成的媒体流及其视频轨道。
     */
    private suspend fun createSource(
        kind: LocalMediaKind,
        create: suspend () -> RealtimeMediaStream,
    ): RealtimeMediaStream = operationMutex.withLock {
        if (currentTrack != null) {
            throw XmaxError(
                XmaxErrorCode.INVALID_CONFIGURATION,
                "Stop the current local media stream before creating another one",
            )
        }
        try {
            rtcManager.initialize()
            create().also {
                synchronized(stateLock) { activeSource = kind }
            }
        } catch (error: Throwable) {
            cleanupAfterFailure(error,
                { stopController(kind) },
                { rtcManager.destroy() },
                { synchronized(stateLock) { activeSource = null } },
            )
            throw XmaxError.from(error)
        }
    }

    /**
     * 停止指定类型的本地媒体源；当前源类型不符时不执行。
     *
     * @param kind 本次操作或媒体源的类型。
     */
    private suspend fun stopSource(kind: LocalMediaKind) = operationMutex.withLock {
        if (synchronized(stateLock) { activeSource } == kind) stopSourceLocked(kind)
    }

    /**
     * 释放指定类型的当前媒体源及关联轨道。
     *
     * @param kind 本次操作或媒体源的类型。
     */
    private suspend fun stopSourceLocked(kind: LocalMediaKind) {
        cleanupResources(
            { stopController(kind) },
            { rtcManager.destroy() },
            { synchronized(stateLock) { activeSource = null } },
        )
    }

    /**
     * 释放指定媒体类型对应的控制器资源。
     *
     * @param kind 本次操作或媒体源的类型。
     */
    private suspend fun stopController(kind: LocalMediaKind) {
        when (kind) {
            LocalMediaKind.CAMERA -> cameraController.stopLocalCameraStream()
            LocalMediaKind.IMAGE -> imageController?.stopLocalImageStream()
            LocalMediaKind.VIDEO -> videoController?.stopLocalVideoStream()
            LocalMediaKind.EXTERNAL -> externalController?.stop()
            LocalMediaKind.NETWORK_VIDEO -> networkVideoController?.stop()
        }
    }

    /**
     * 取得图片控制器，未配置时报告不支持该输入的错误。
     *
     * @return 已配置的图片输入控制器。
     */
    private fun requiredImageController(): ImageController = imageController ?: throw XmaxError(
        XmaxErrorCode.INTERNAL_ERROR,
        "Local image media is unavailable",
    )

    /**
     * 取得视频控制器，未配置时报告不支持该输入的错误。
     *
     * @return 已配置的视频输入控制器。
     */
    private fun requiredVideoController(): VideoController = videoController ?: throw XmaxError(
        XmaxErrorCode.INTERNAL_ERROR,
        "Local video media is unavailable",
    )

    /**
     * 当前管理器所持有的本地媒体输入类型。
     */
    private enum class LocalMediaKind {
        /**
         * 由服务端直接读取的网络视频输入。
         */
        NETWORK_VIDEO,

        /**
         * 由接入方提供帧的外部媒体输入。
         */
        EXTERNAL,

        /**
         * 设备摄像头输入。
         */
        CAMERA,

        /**
         * 静态图片输入。
         */
        IMAGE,

        /**
         * 本地视频输入。
         */
        VIDEO,
    }
}
