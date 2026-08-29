package ai.xmax.sdk.media

import ai.xmax.sdk.CameraPosition
import ai.xmax.sdk.RealtimeCameraPreviewReadyListener
import ai.xmax.sdk.RealtimeMediaStream
import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.RealtimeVideoTrack
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import ai.xmax.sdk.foundation.rtc.RtcManaging
import ai.xmax.sdk.media.camera.CameraController
import ai.xmax.sdk.media.image.ImageController
import ai.xmax.sdk.media.interaction.InteractionController
import ai.xmax.sdk.media.interaction.InteractionFrame
import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 统一协调本地媒体所有权以及 RTC Engine 生命周期。 */
internal class MediaController(
    private val rtcManager: RtcManaging,
    private val cameraController: CameraController,
    private val imageController: ImageController? = null,
    private val interactionController: InteractionController = InteractionController(),
) : MediaControlling {
    private val operationMutex = Mutex()
    private val stateLock = Any()
    private var activeSource: LocalMediaKind? = null

    override val currentTrack: RealtimeVideoTrack?
        get() = when (synchronized(stateLock) { activeSource }) {
            LocalMediaKind.CAMERA -> cameraController.currentTrack
            LocalMediaKind.IMAGE -> imageController?.currentTrack
            null -> null
        }

    override val currentVideoFormat: RealtimeVideoFormat?
        get() = currentTrack?.videoFormat

    override val hasAudio: Boolean
        get() = false

    override fun setCameraPreviewReadyListener(listener: RealtimeCameraPreviewReadyListener?) {
        cameraController.setPreviewReadyListener(listener)
    }

    override suspend fun startInteraction(
        taskId: String,
        videoFormat: RealtimeVideoFormat,
    ) {
        interactionController.startInteraction(taskId, videoFormat)
    }

    override suspend fun stopInteraction() {
        interactionController.stopInteraction()
    }

    override fun submitInteraction(frame: InteractionFrame) {
        interactionController.submitInteraction(frame)
    }

    override suspend fun createLocalCameraStream(
        videoFormat: RealtimeVideoFormat,
        position: CameraPosition,
    ): RealtimeMediaStream = createSource(LocalMediaKind.CAMERA) {
        cameraController.createLocalCameraStream(videoFormat, position)
    }

    override suspend fun createLocalImageStream(
        imageData: ByteArray,
        videoFormat: RealtimeVideoFormat?,
    ): RealtimeMediaStream = createSource(LocalMediaKind.IMAGE) {
        requiredImageController().createLocalImageStream(imageData, videoFormat)
    }

    override suspend fun createLocalImageStream(
        bitmap: Bitmap,
        videoFormat: RealtimeVideoFormat?,
    ): RealtimeMediaStream = createSource(LocalMediaKind.IMAGE) {
        requiredImageController().createLocalImageStream(bitmap, videoFormat)
    }

    override suspend fun createLocalImageStream(
        uri: Uri,
        videoFormat: RealtimeVideoFormat?,
    ): RealtimeMediaStream = createSource(LocalMediaKind.IMAGE) {
        requiredImageController().createLocalImageStream(uri, videoFormat)
    }

    override suspend fun stopLocalCameraStream() {
        stopSource(LocalMediaKind.CAMERA)
    }

    override suspend fun stopLocalImageStream() {
        stopSource(LocalMediaKind.IMAGE)
    }

    override suspend fun switchCamera(): RealtimeMediaStream = operationMutex.withLock {
        if (synchronized(stateLock) { activeSource } != LocalMediaKind.CAMERA) {
            throw XmaxError(
                XmaxErrorCode.INVALID_CONFIGURATION,
                "The current local media source is not a camera",
            )
        }
        cameraController.switchCamera()
    }

    override suspend fun stopLocalStream() {
        synchronized(stateLock) { activeSource }?.let { stopSource(it) }
    }

    override fun owns(stream: RealtimeMediaStream): Boolean =
        stream.videoTrack != null && stream.videoTrack === currentTrack

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
            val resolvedError = XmaxError.from(error)
            try {
                rtcManager.destroy()
            } catch (cleanupError: Throwable) {
                resolvedError.addSuppressed(cleanupError)
            } finally {
                synchronized(stateLock) { activeSource = null }
            }
            throw resolvedError
        }
    }

    private suspend fun stopSource(kind: LocalMediaKind) {
        operationMutex.withLock {
            if (synchronized(stateLock) { activeSource } != kind) return@withLock
            try {
                when (kind) {
                    LocalMediaKind.CAMERA -> cameraController.stopLocalCameraStream()
                    LocalMediaKind.IMAGE -> imageController?.stopLocalImageStream()
                }
            } finally {
                try {
                    rtcManager.destroy()
                } finally {
                    synchronized(stateLock) { activeSource = null }
                }
            }
        }
    }

    private fun requiredImageController(): ImageController = imageController ?: throw XmaxError(
        XmaxErrorCode.INTERNAL_ERROR,
        "Local image media is unavailable",
    )

    private enum class LocalMediaKind {
        CAMERA,
        IMAGE,
    }
}
