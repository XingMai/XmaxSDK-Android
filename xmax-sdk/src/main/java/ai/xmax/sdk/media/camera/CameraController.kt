//
//  CameraController.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.media.camera

import ai.xmax.sdk.CameraPosition
import ai.xmax.sdk.MediaServicing
import ai.xmax.sdk.RealtimeCameraPreviewReadyListener
import ai.xmax.sdk.RealtimeMediaStream
import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.RealtimeVideoTrack
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import ai.xmax.sdk.cleanupAfterFailure
import ai.xmax.sdk.cleanupResources
import ai.xmax.sdk.foundation.permissions.PermissionManaging
import ai.xmax.sdk.foundation.rtc.RtcManaging
import ai.xmax.sdk.render.video.VideoRenderBinding
import ai.xmax.sdk.render.video.VideoRenderRegistry
import ai.xmax.sdk.stream.StreamID
import android.content.Context
import androidx.compose.ui.unit.IntSize

/**
 * 协调相机权限、RTC 内部采集和本地预览资源。
 *
 * @param rtcManager 提供 RTC 采集、发布、订阅和渲染能力的管理器。
 * @param permissionManager 检查 Android 相机及录音权限的组件。
 * @param mediaService 解析媒体尺寸及模型输入规则的服务。
 * @param errorListener 接收媒体错误的回调。
 */
internal class CameraController(
    private val rtcManager: RtcManaging,
    private val permissionManager: PermissionManaging,
    private val mediaService: MediaServicing,
    private val errorListener: (XmaxError) -> Unit = {},
) {
    /**
     * 创建 CameraController，并配置其依赖和运行参数。
     *
     * @param context 访问 Android 系统服务或媒体资源所需的上下文。
     * @param rtcManager 提供 RTC 采集、发布、订阅和渲染能力的管理器。
     * @param mediaService 解析媒体尺寸及模型输入规则的服务。
     * @param errorListener 接收媒体错误的回调。
     */
    constructor(
        context: Context,
        rtcManager: RtcManaging,
        mediaService: MediaServicing = ai.xmax.sdk.service.media.MediaService(),
        errorListener: (XmaxError) -> Unit = {},
    ) : this(
        rtcManager = rtcManager,
        permissionManager = ai.xmax.sdk.foundation.permissions.PermissionManager(context),
        mediaService = mediaService,
        errorListener = errorListener,
    )

    /**
     * 相机输入状态的并发保护。
     */
    private val stateLock = Any()

    /**
     * 活动相机轨道与麦克风采集配置。
     */
    private var activeTrack: RealtimeVideoTrack? = null
    private var storedUseMicrophone = false
    private var isMicrophoneCapturing = false

    /**
     * 供调用方读取的媒体配置快照。
     */
    val currentTrack: RealtimeVideoTrack?
        get() = synchronized(stateLock) { activeTrack }

    val useMicrophone: Boolean
        get() = synchronized(stateLock) { activeTrack != null && storedUseMicrophone }

    /**
     * 替换相机预览就绪监听器；null 表示注销。
     *
     * @param listener 接收对应事件的监听器；null 表示注销。
     */
    fun setPreviewReadyListener(listener: RealtimeCameraPreviewReadyListener?) {
        rtcManager.setCameraPreviewReadyListener(listener)
    }

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
    ): RealtimeMediaStream {
        if (currentTrack != null) {
            throw XmaxError(
                code = XmaxErrorCode.INVALID_CONFIGURATION,
                message = "Stop the current local camera stream before creating another one",
            )
        }

        val resolvedFormat = resolveVideoFormat(videoFormat)
        val track = RealtimeVideoTrack(
            id = LOCAL_VIDEO_TRACK_ID,
            videoFormat = resolvedFormat,
            position = position,
        )
        try {
            permissionManager.ensureCameraPermission()
            if (useMicrophone) permissionManager.ensureMicrophonePermission()
            rtcManager.switchCamera(position)
            rtcManager.startVideoCapture(
                width = resolvedFormat.width,
                height = resolvedFormat.height,
                frameRate = resolvedFormat.fps,
            )
            VideoRenderRegistry.register(
                track,
                VideoRenderBinding(
                    attachHandler = { view, contentMode ->
                        try {
                            view.prepareRtcVideoRendering()
                            rtcManager.bindLocalVideo(view.rtcRenderView, contentMode)
                        } catch (error: Throwable) {
                            errorListener(XmaxError.from(error))
                            throw error
                        }
                    },
                    detachHandler = { rtcManager.unbindLocalVideo() },
                ),
            )
            synchronized(stateLock) {
                activeTrack = track
                storedUseMicrophone = useMicrophone
            }
            return RealtimeMediaStream(StreamID.LOCAL.value, track)
        } catch (error: Throwable) {
            cleanupAfterFailure(error,
                { VideoRenderRegistry.unregister(track) },
                { rtcManager.stopVideoCapture() },
            )
            throw XmaxError.from(error)
        }
    }

    /**
     * 启动当前相机源的麦克风采集。
     */
    fun startMicrophoneCapture() {
        val shouldStart = synchronized(stateLock) {
            if (activeTrack == null || !storedUseMicrophone || isMicrophoneCapturing) {
                false
            } else {
                isMicrophoneCapturing = true
                true
            }
        }
        if (shouldStart) rtcManager.startAudioCapture()
    }

    /**
     * 停止当前相机源的麦克风采集。
     */
    fun stopMicrophoneCapture() {
        if (!synchronized(stateLock) { isMicrophoneCapturing }) return
        rtcManager.stopAudioCapture()
        synchronized(stateLock) { isMicrophoneCapturing = false }
    }

    /**
     * 停止当前相机输入并释放其资源；当前源不是相机时不执行释放。
     */
    suspend fun stopLocalCameraStream() {
        val track = synchronized(stateLock) {
            activeTrack.also {
                activeTrack = null
                storedUseMicrophone = false
            }
        }
        try {
            cleanupResources(
                { stopMicrophoneCapture() },
                { track?.let(VideoRenderRegistry::unregister) },
                { if (track != null) rtcManager.unbindLocalVideo() },
                { rtcManager.stopVideoCapture() },
            )
        } finally {
            synchronized(stateLock) { isMicrophoneCapturing = false }
        }
    }

    /**
     * 切换前后摄像头，并更新当前相机轨道的方向。
     *
     * @return 可用于预览或生成的媒体流及其视频轨道。
     */
    suspend fun switchCamera(): RealtimeMediaStream {
        val track = currentTrack
        val position = track?.position
        if (track == null || track.videoFormat == null || position == null) {
            throw XmaxError(
                code = XmaxErrorCode.RTC_ERROR,
                message = "Local camera preview is not started",
            )
        }

        val nextPosition = when (position) {
            CameraPosition.FRONT -> CameraPosition.BACK
            CameraPosition.BACK -> CameraPosition.FRONT
        }
        return try {
            rtcManager.switchCamera(nextPosition)
            track.updatePosition(nextPosition)
            RealtimeMediaStream(StreamID.LOCAL.value, track)
        } catch (error: Throwable) {
            throw XmaxError.from(error)
        }
    }

    /**
     * 使用模型规则校验或调整摄像头输入规格。
     *
     * @param videoFormat 视频尺寸、帧率及上行编码参数。
     * @return 解析或计算后的视频尺寸、帧率和编码配置。
     */
    private fun resolveVideoFormat(videoFormat: RealtimeVideoFormat): RealtimeVideoFormat {
        videoFormat.validate()
        val size = mediaService.resolveModelInputSize(
            IntSize(videoFormat.width, videoFormat.height),
        )
        return videoFormat.copy(width = size.width, height = size.height)
            .also(RealtimeVideoFormat::validate)
    }

    /**
     * 提供 CameraController 使用的构造入口、共享配置或常量。
     */
    private companion object {
        /**
         * 共享常量、默认配置与工厂入口。
         */
        const val LOCAL_VIDEO_TRACK_ID = "video0"
    }
}
