//
//  MediaSourceController.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.media

import ai.xmax.sdk.cleanupAfterFailure
import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.VideoContentMode
import ai.xmax.sdk.VideoRotation
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import ai.xmax.sdk.XmaxVideoView
import ai.xmax.sdk.foundation.media.MediaFileMetadata
import ai.xmax.sdk.foundation.media.MediaFileMetadataManaging
import ai.xmax.sdk.media.video.VideoPlayerControlling
import ai.xmax.sdk.MediaServicing
import android.net.Uri
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 协调本地视频元数据、输出格式和平台播放器。
 *
 * @param metadataManager 读取媒体元数据的组件。
 * @param mediaService 解析媒体尺寸及模型输入规则的服务。
 * @param playerController 负责本地解码与播放时间线的组件。
 */
internal class MediaSourceController(
    private val metadataManager: MediaFileMetadataManaging,
    private val mediaService: MediaServicing,
    private val playerController: VideoPlayerControlling,
) : MediaSourceControlling {
    /**
     * 媒体准备和播放状态的并发保护。
     */
    private val operationMutex = Mutex()
    private val stateLock = Any()

    /**
     * 已准备媒体及播放状态。
     */
    private var preparedMedia: PreparedMedia? = null
    private var isRunning = false

    override val hasAudio: Boolean
        get() = synchronized(stateLock) { preparedMedia?.configuration?.hasAudio == true }

    /**
     * 读取视频元数据并准备保持原始比例的本地视频输入。
     *
     * @param uri 应用有读取权限的媒体地址。
     * @param videoFormat 媒体输入的尺寸、帧率及可选上行编码配置；null 表示使用媒体解析结果或来源默认值。
     * @return 可供媒体输入和解码使用的最终配置。
     */
    override suspend fun prepare(
        uri: Uri,
        videoFormat: RealtimeVideoFormat?,
    ): MediaSourceConfiguration = operationMutex.withLock {
        if (synchronized(stateLock) { preparedMedia != null }) {
            throw XmaxError(
                XmaxErrorCode.INVALID_CONFIGURATION,
                "Stop the current media source before preparing another file",
            )
        }
        try {
            val metadata = metadataManager.readMetadata(uri)
            val resolvedFormat = resolveVideoFormat(metadata, videoFormat)
            playerController.configure(
                uri = uri,
                outputWidth = resolvedFormat.width,
                outputHeight = resolvedFormat.height,
                rotation = metadata.rotation,
                frameRate = resolvedFormat.fps,
                hasAudio = metadata.hasAudio,
                durationUs = metadata.durationUs,
            )
            val configuration = MediaSourceConfiguration(resolvedFormat, metadata.hasAudio)
            synchronized(stateLock) {
                preparedMedia = PreparedMedia(metadata, configuration)
            }
            configuration
        } catch (error: Throwable) {
            throw XmaxError.from(error)
        }
    }

    /**
     * 启动已准备的视频输入，循环解码并输出音视频帧。
     */
    override suspend fun start() = operationMutex.withLock {
        synchronized(stateLock) {
            if (preparedMedia == null) {
                throw XmaxError(
                    XmaxErrorCode.INVALID_CONFIGURATION,
                    "Prepare the media file before starting it",
                )
            }
            if (isRunning) {
                throw XmaxError(
                    XmaxErrorCode.INVALID_CONFIGURATION,
                    "Local media source is already active",
                )
            }
            isRunning = true
        }
        try {
            playerController.start()
        } catch (error: Throwable) {
            synchronized(stateLock) { isRunning = false }
            cleanupAfterFailure(error, { playerController.stop() })
            throw XmaxError.from(error)
        }
    }

    /**
     * 设置本地预览是否静音，不改变保存的音量值。
     *
     * @param muted 是否静音。
     */
    override suspend fun setLocalAudioPreviewMuted(muted: Boolean) {
        if (hasAudio) playerController.setLocalAudioPreviewMuted(muted)
    }

    /**
     * 设置当前本地媒体的预览音量，范围为 0 到 1，不影响提交给 RTC 的音频数据。
     *
     * @param volume 播放音量，范围为 0 到 1。
     */
    override suspend fun setLocalAudioVolume(volume: Float) {
        playerController.setLocalAudioVolume(volume)
    }

    /**
     * 将本地媒体预览绑定到指定视图并应用显示模式。
     *
     * @param view 需要绑定或解除绑定的显示视图。
     * @param contentMode 视频等比适配或等比裁切的显示方式。
     */
    override fun attachPreview(view: XmaxVideoView, contentMode: VideoContentMode) {
        playerController.attachPreview(view, contentMode)
    }

    /**
     * 解除指定视图的本地媒体预览绑定。
     *
     * @param view 需要绑定或解除绑定的显示视图。
     */
    override fun detachPreview(view: XmaxVideoView) {
        playerController.detachPreview(view)
    }

    /**
     * 停止本地视频输出并清除已准备的媒体信息。
     */
    override suspend fun stop() = operationMutex.withLock {
        synchronized(stateLock) {
            isRunning = false
            preparedMedia = null
        }
        playerController.stop()
    }

    /**
     * 使用指定规格或旋转校正后的素材尺寸准备视频输入，保持素材比例。
     *
     * @param metadata 媒体原始尺寸、方向、时长及音轨信息。
     * @param requestedFormat 调用方指定的媒体输入规格；null 表示从媒体本身解析。
     * @return 解析或计算后的视频尺寸、帧率和编码配置。
     */
    internal fun resolveVideoFormat(
        metadata: MediaFileMetadata,
        requestedFormat: RealtimeVideoFormat?,
    ): RealtimeVideoFormat {
        val swapsDimensions = metadata.rotation == VideoRotation.ROTATION_90 ||
            metadata.rotation == VideoRotation.ROTATION_270
        val displaySize = if (swapsDimensions) {
            IntSize(metadata.height, metadata.width)
        } else {
            IntSize(metadata.width, metadata.height)
        }
        if (displaySize.width < 2 || displaySize.height < 2) {
            throw XmaxError(XmaxErrorCode.INVALID_CONFIGURATION, "Video dimensions must be at least two pixels")
        }

        val requested = requestedFormat ?: RealtimeVideoFormat(
            width = displaySize.width / 2 * 2,
            height = displaySize.height / 2 * 2,
            fps = mediaService.model.defaultFrameRate,
        )
        requested.validate()
        val aspectError = abs(requested.width.toDouble() * displaySize.height - requested.height.toDouble() * displaySize.width)
        if (aspectError > displaySize.width + displaySize.height.toDouble()) {
            throw XmaxError(
                XmaxErrorCode.INVALID_CONFIGURATION,
                "Requested video dimensions must preserve the source aspect ratio",
            )
        }
        return requested
    }

    /**
     * 保存已解析的媒体信息和最终播放配置。
     *
     * @param metadata 媒体原始尺寸、方向、时长及音轨信息。
     * @param configuration 媒体输入尺寸、帧率、旋转方向及音轨配置。
     */
    private data class PreparedMedia(
        val metadata: MediaFileMetadata,
        val configuration: MediaSourceConfiguration,
    )
}
