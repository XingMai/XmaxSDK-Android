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
 */
internal class MediaSourceController(
    private val metadataManager: MediaFileMetadataManaging,
    private val mediaService: MediaServicing,
    private val playerController: VideoPlayerControlling,
) : MediaSourceControlling {
    private val operationMutex = Mutex()
    private val stateLock = Any()
    private var preparedMedia: PreparedMedia? = null
    private var isRunning = false

    override val hasAudio: Boolean
        get() = synchronized(stateLock) { preparedMedia?.configuration?.hasAudio == true }

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

    override suspend fun setLocalAudioPreviewMuted(muted: Boolean) {
        if (hasAudio) playerController.setLocalAudioPreviewMuted(muted)
    }

    override suspend fun setLocalAudioVolume(volume: Float) {
        playerController.setLocalAudioVolume(volume)
    }

    override fun attachPreview(view: XmaxVideoView, contentMode: VideoContentMode) {
        playerController.attachPreview(view, contentMode)
    }

    override fun detachPreview(view: XmaxVideoView) {
        playerController.detachPreview(view)
    }

    override suspend fun stop() = operationMutex.withLock {
        synchronized(stateLock) {
            isRunning = false
            preparedMedia = null
        }
        playerController.stop()
    }

    /**
     * 使用指定规格或旋转校正后的素材尺寸准备视频输入，保持素材比例。
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

    private data class PreparedMedia(
        val metadata: MediaFileMetadata,
        val configuration: MediaSourceConfiguration,
    )
}
