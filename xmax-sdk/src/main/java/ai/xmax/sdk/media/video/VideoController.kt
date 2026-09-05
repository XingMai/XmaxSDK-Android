package ai.xmax.sdk.media.video

import ai.xmax.sdk.cleanupResources
import ai.xmax.sdk.cleanupAfterFailure
import ai.xmax.sdk.RealtimeMediaStream
import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.RealtimeVideoTrack
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import ai.xmax.sdk.foundation.rtc.RtcManaging
import ai.xmax.sdk.media.MediaSourceControlling
import ai.xmax.sdk.rendering.video.VideoRenderBinding
import ai.xmax.sdk.rendering.video.VideoRenderRegistry
import ai.xmax.sdk.stream.StreamID
import android.net.Uri

/** 协调本地视频播放器、RTC 外部音视频源和预览资源。 */
internal class VideoController(
    private val rtcManager: RtcManaging,
    private val mediaSourceController: MediaSourceControlling,
) {
    private val stateLock = Any()
    private var activeTrack: RealtimeVideoTrack? = null

    val currentTrack: RealtimeVideoTrack?
        get() = synchronized(stateLock) { activeTrack }

    val hasAudio: Boolean
        get() = currentTrack != null && mediaSourceController.hasAudio

    suspend fun createLocalVideoStream(
        uri: Uri,
        videoFormat: RealtimeVideoFormat?,
    ): RealtimeMediaStream {
        if (currentTrack != null) {
            throw XmaxError(
                XmaxErrorCode.INVALID_CONFIGURATION,
                "Stop the current local video stream before creating another one",
            )
        }
        var track: RealtimeVideoTrack? = null
        var externalAudioStarted = false
        try {
            val configuration = mediaSourceController.prepare(uri, videoFormat)
            val localTrack = RealtimeVideoTrack(
                id = LOCAL_VIDEO_TRACK_ID,
                videoFormat = configuration.videoFormat,
            )
            track = localTrack
            rtcManager.useExternalVideoSource()
            if (configuration.hasAudio) {
                rtcManager.startExternalAudioSource()
                externalAudioStarted = true
            }
            VideoRenderRegistry.register(
                localTrack,
                VideoRenderBinding(
                    libraryName = "AndroidMedia",
                    attachHandler = mediaSourceController::attachPreview,
                    detachHandler = mediaSourceController::detachPreview,
                ),
            )
            mediaSourceController.start()
            synchronized(stateLock) { activeTrack = localTrack }
            return RealtimeMediaStream(StreamID.LOCAL.value, localTrack)
        } catch (error: Throwable) {
            cleanupAfterFailure(error,
                { mediaSourceController.stop() },
                { if (externalAudioStarted) rtcManager.stopExternalAudioSource() },
                { track?.let(VideoRenderRegistry::unregister) },
            )
            throw XmaxError.from(error)
        }
    }

    suspend fun setLocalAudioPreviewMuted(muted: Boolean) {
        if (currentTrack != null) mediaSourceController.setLocalAudioPreviewMuted(muted)
    }

    suspend fun setLocalAudioVolume(volume: Float) {
        mediaSourceController.setLocalAudioVolume(volume)
    }

    suspend fun stopLocalVideoStream() {
        val state = synchronized(stateLock) {
            val track = activeTrack
            val hasAudio = track != null && mediaSourceController.hasAudio
            activeTrack = null
            track to hasAudio
        }
        cleanupResources(
            { mediaSourceController.stop() },
            { if (state.second) rtcManager.stopExternalAudioSource() },
            { state.first?.let { VideoRenderRegistry.binding(it)?.detach() } },
            { state.first?.let(VideoRenderRegistry::unregister) },
        )
    }

    private companion object {
        const val LOCAL_VIDEO_TRACK_ID = "video0"
    }
}
