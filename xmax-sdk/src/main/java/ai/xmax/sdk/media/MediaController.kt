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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 统一协调本地媒体所有权以及 RTC Engine 生命周期。 */
internal class MediaController(
    private val rtcManager: RtcManaging,
    private val cameraController: CameraController,
) : MediaControlling {
    private val operationMutex = Mutex()

    override val currentTrack: RealtimeVideoTrack?
        get() = cameraController.currentTrack

    override fun setCameraPreviewReadyListener(listener: RealtimeCameraPreviewReadyListener?) {
        cameraController.setPreviewReadyListener(listener)
    }

    override suspend fun createLocalCameraStream(
        videoFormat: RealtimeVideoFormat,
        position: CameraPosition,
    ): RealtimeMediaStream = operationMutex.withLock {
        if (currentTrack != null) {
            throw XmaxError(
                code = XmaxErrorCode.INVALID_CONFIGURATION,
                message = "Stop the current local camera stream before creating another one",
            )
        }
        try {
            rtcManager.initialize()
            cameraController.createLocalCameraStream(videoFormat, position)
        } catch (error: Throwable) {
            rtcManager.destroy()
            throw XmaxError.from(error)
        }
    }

    override suspend fun stopLocalCameraStream() {
        operationMutex.withLock {
            cameraController.stopLocalCameraStream()
            rtcManager.destroy()
        }
    }

    override suspend fun switchCamera(): RealtimeMediaStream = operationMutex.withLock {
        cameraController.switchCamera()
    }

    override suspend fun stopLocalStream() {
        stopLocalCameraStream()
    }
}
