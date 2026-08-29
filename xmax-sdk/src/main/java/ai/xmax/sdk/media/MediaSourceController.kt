package ai.xmax.sdk.media

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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 协调本地视频元数据、输出格式和平台播放器。 */
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
            playerController.stop()
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

    private fun resolveVideoFormat(
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
        val requested = requestedFormat ?: RealtimeVideoFormat(
            width = displaySize.width,
            height = displaySize.height,
            fps = DEFAULT_FRAME_RATE,
        )
        if (requested.fps <= 0) {
            throw XmaxError(
                XmaxErrorCode.INVALID_CONFIGURATION,
                "Video stream frame rate must be greater than zero",
            )
        }
        val resolvedSize = mediaService.resolveModelInputSize(
            IntSize(requested.width, requested.height),
        )
        return RealtimeVideoFormat(
            width = resolvedSize.width,
            height = resolvedSize.height,
            fps = requested.fps,
        ).also(RealtimeVideoFormat::validate)
    }

    private data class PreparedMedia(
        val metadata: MediaFileMetadata,
        val configuration: MediaSourceConfiguration,
    )

    private companion object {
        const val DEFAULT_FRAME_RATE = 24
    }
}
