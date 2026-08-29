package ai.xmax.sdk.media.camera

import ai.xmax.sdk.CameraPosition
import ai.xmax.sdk.MediaServicing
import ai.xmax.sdk.RealtimeCameraPreviewReadyListener
import ai.xmax.sdk.RealtimeMediaStream
import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.RealtimeVideoTrack
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import ai.xmax.sdk.foundation.permissions.PermissionManaging
import ai.xmax.sdk.foundation.rtc.RtcManaging
import ai.xmax.sdk.rendering.video.VideoRenderBinding
import ai.xmax.sdk.rendering.video.VideoRenderRegistry
import ai.xmax.sdk.stream.StreamID
import android.content.Context
import androidx.compose.ui.unit.IntSize

/** 协调相机权限、RTC 内部采集和本地预览资源。 */
internal class CameraController(
    private val rtcManager: RtcManaging,
    private val permissionManager: PermissionManaging,
    private val mediaService: MediaServicing,
    private val errorListener: (XmaxError) -> Unit = {},
) {
    constructor(
        context: Context,
        rtcManager: RtcManaging,
        errorListener: (XmaxError) -> Unit = {},
    ) : this(
        rtcManager = rtcManager,
        permissionManager = ai.xmax.sdk.foundation.permissions.PermissionManager(context),
        mediaService = ai.xmax.sdk.service.media.MediaService(),
        errorListener = errorListener,
    )

    private val stateLock = Any()
    private var activeTrack: RealtimeVideoTrack? = null

    val currentTrack: RealtimeVideoTrack?
        get() = synchronized(stateLock) { activeTrack }

    fun setPreviewReadyListener(listener: RealtimeCameraPreviewReadyListener?) {
        rtcManager.setCameraPreviewReadyListener(listener)
    }

    suspend fun createLocalCameraStream(
        videoFormat: RealtimeVideoFormat,
        position: CameraPosition,
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
            rtcManager.switchCamera(position)
            rtcManager.startVideoCapture(
                width = resolvedFormat.width,
                height = resolvedFormat.height,
                frameRate = resolvedFormat.fps,
            )
            VideoRenderRegistry.register(
                track,
                VideoRenderBinding(
                    libraryName = rtcManager.renderLibraryName,
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
            }
            return RealtimeMediaStream(StreamID.LOCAL.value, track)
        } catch (error: Throwable) {
            VideoRenderRegistry.unregister(track)
            runCatching { rtcManager.stopVideoCapture() }
            throw XmaxError.from(error)
        }
    }

    suspend fun stopLocalCameraStream() {
        val track = synchronized(stateLock) {
            activeTrack.also { activeTrack = null }
        }
        if (track != null) {
            VideoRenderRegistry.unregister(track)
            runCatching { rtcManager.unbindLocalVideo() }
        }
        runCatching { rtcManager.stopVideoCapture() }
    }

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

    private fun resolveVideoFormat(videoFormat: RealtimeVideoFormat): RealtimeVideoFormat {
        videoFormat.validate()
        val size = mediaService.resolveModelInputSize(
            IntSize(videoFormat.width, videoFormat.height),
        )
        return RealtimeVideoFormat(
            width = size.width,
            height = size.height,
            fps = videoFormat.fps,
        ).also(RealtimeVideoFormat::validate)
    }

    private companion object {
        const val LOCAL_VIDEO_TRACK_ID = "video0"
    }
}
