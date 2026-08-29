package ai.xmax.sdk.media.image

import ai.xmax.sdk.RealtimeMediaStream
import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.RealtimeVideoTrack
import ai.xmax.sdk.VideoFrame
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import ai.xmax.sdk.foundation.rtc.RtcManaging
import ai.xmax.sdk.rendering.video.VideoRenderBinding
import ai.xmax.sdk.rendering.video.VideoRenderRegistry
import ai.xmax.sdk.stream.StreamID
import android.graphics.Bitmap
import android.net.Uri

/** 协调本地图片解码、循环帧输出和预览资源。 */
internal class ImageController(
    private val rtcManager: RtcManaging,
    private val imageSourceController: ImageSourceControlling,
) {
    private val stateLock = Any()
    private var activeTrack: RealtimeVideoTrack? = null

    val currentTrack: RealtimeVideoTrack?
        get() = synchronized(stateLock) { activeTrack }

    suspend fun createLocalImageStream(
        imageData: ByteArray,
        videoFormat: RealtimeVideoFormat?,
    ): RealtimeMediaStream = createStream {
        imageSourceController.prepare(imageData, videoFormat)
    }

    suspend fun createLocalImageStream(
        bitmap: Bitmap,
        videoFormat: RealtimeVideoFormat?,
    ): RealtimeMediaStream = createStream {
        imageSourceController.prepare(bitmap, videoFormat)
    }

    suspend fun createLocalImageStream(
        uri: Uri,
        videoFormat: RealtimeVideoFormat?,
    ): RealtimeMediaStream = createStream {
        imageSourceController.prepare(uri, videoFormat)
    }

    fun stopLocalImageStream() {
        val track = synchronized(stateLock) {
            activeTrack.also { activeTrack = null }
        }
        imageSourceController.stop()
        if (track != null) {
            VideoRenderRegistry.binding(track)?.detach()
            VideoRenderRegistry.unregister(track)
        }
    }

    private suspend fun createStream(
        prepare: suspend () -> Pair<RealtimeVideoFormat, VideoFrame>,
    ): RealtimeMediaStream {
        if (currentTrack != null) {
            throw XmaxError(
                XmaxErrorCode.INVALID_CONFIGURATION,
                "Stop the current local image stream before creating another one",
            )
        }
        var track: RealtimeVideoTrack? = null
        try {
            val source = prepare()
            val localTrack = RealtimeVideoTrack(
                id = LOCAL_VIDEO_TRACK_ID,
                videoFormat = source.first,
            )
            track = localTrack
            rtcManager.useExternalVideoSource()
            VideoRenderRegistry.register(
                localTrack,
                VideoRenderBinding(source.second),
            )
            imageSourceController.start()
            synchronized(stateLock) { activeTrack = localTrack }
            return RealtimeMediaStream(StreamID.LOCAL.value, localTrack)
        } catch (error: Throwable) {
            imageSourceController.stop()
            track?.let(VideoRenderRegistry::unregister)
            throw XmaxError.from(error)
        }
    }

    private companion object {
        const val LOCAL_VIDEO_TRACK_ID = "video0"
    }
}
