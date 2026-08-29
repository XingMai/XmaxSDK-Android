package ai.xmax.sdk

import ai.xmax.sdk.foundation.rtc.RtcManager
import ai.xmax.sdk.media.MediaController
import ai.xmax.sdk.media.camera.CameraController
import android.content.Context

/** 实时生成业务公共入口；当前阶段提供本地相机预览能力。 */
internal class XmaxRealtimeManager(
    override val options: RealtimeConfiguration,
    context: Context,
) : XmaxRealtimeManaging {
    private val listenerLock = Any()
    private var errorListener: RealtimeErrorListener? = null
    private val rtcManager = RtcManager(context)
    private val mediaController = MediaController(
        rtcManager = rtcManager,
        cameraController = CameraController(
            context = context,
            rtcManager = rtcManager,
            errorListener = ::reportError,
        ),
    )

    override suspend fun setErrorListener(listener: RealtimeErrorListener?) {
        synchronized(listenerLock) {
            errorListener = listener
        }
    }

    override suspend fun setCameraPreviewReadyListener(
        listener: RealtimeCameraPreviewReadyListener?,
    ) {
        mediaController.setCameraPreviewReadyListener(listener)
    }

    override suspend fun createLocalCameraStream(
        videoFormat: RealtimeVideoFormat,
        position: CameraPosition,
    ): RealtimeMediaStream = try {
        mediaController.createLocalCameraStream(videoFormat, position)
    } catch (error: Throwable) {
        throw reportError(error)
    }

    override suspend fun stopLocalCameraStream() {
        mediaController.stopLocalCameraStream()
    }

    override suspend fun switchCamera(): RealtimeMediaStream = try {
        mediaController.switchCamera()
    } catch (error: Throwable) {
        throw reportError(error)
    }

    override suspend fun close() {
        mediaController.stopLocalStream()
        setCameraPreviewReadyListener(null)
        setErrorListener(null)
    }

    private fun reportError(error: Throwable): XmaxError {
        val xmaxError = XmaxError.from(error)
        runCatching {
            synchronized(listenerLock) { errorListener }?.onError(xmaxError)
        }
        return xmaxError
    }
}
