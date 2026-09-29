package ai.xmax.sdk.media.external

import ai.xmax.sdk.*
import ai.xmax.sdk.foundation.rtc.RtcManaging
import ai.xmax.sdk.render.video.VideoRenderBinding
import ai.xmax.sdk.render.video.VideoRenderRegistry
import ai.xmax.sdk.stream.StreamID
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 外部源只借用播放器；沿用 SDK 的轨道所有权、模型规格和媒体错误清理。 */
internal class ExternalVideoController(
    private val rtc: RtcManaging,
    private val mediaService: MediaServicing,
    private val requestingFrames: () -> Boolean,
    private val videoListener: (VideoFrame) -> Unit,
    private val audioListener: (AudioFrame) -> Unit,
    private val errorListener: (XmaxError) -> Unit,
) {
    private val lock = Any()
    private var source: RealtimeExternalVideoSource? = null
    private var sink: Sink? = null
    @Volatile var currentTrack: RealtimeVideoTrack? = null
        private set
    @Volatile var hasAudio = false
        private set
    private var volume = 0.45f
    private var muted = false

    suspend fun create(
        source: RealtimeExternalVideoSource,
        videoFormat: RealtimeVideoFormat? = null,
    ): RealtimeMediaStream = withContext(Dispatchers.Main.immediate) {
        check(this@ExternalVideoController.source == null)
        val requested = videoFormat ?: source.videoFormat.copy(fps = mediaService.model.defaultFrameRate)
        if (requested.fps <= 0) {
            throw XmaxError(XmaxErrorCode.INVALID_CONFIGURATION, "Video stream frame rate must be greater than zero")
        }
        val size = mediaService.resolveModelInputSize(IntSize(requested.width, requested.height))
        val format = requested.copy(width = size.width, height = size.height).also(RealtimeVideoFormat::validate)
        val track = RealtimeVideoTrack("external0", format)
        val input = Sink(format, source.hasAudio)
        this@ExternalVideoController.source = source
        synchronized(lock) { sink = input }
        try {
            rtc.useExternalVideoSource()
            hasAudio = source.hasAudio
            if (hasAudio) rtc.startExternalAudioSource()
            VideoRenderRegistry.register(track, VideoRenderBinding(
                attachHandler = { view, mode -> source.attachPreview(view, mode) },
                detachHandler = { view -> source.detachPreview(view) },
            ))
            currentTrack = track
            source.setPreviewAudio(volume, muted)
            source.start(input)
            RealtimeMediaStream(StreamID.LOCAL.value, track)
        } catch (error: Throwable) {
            cleanupAfterFailure(error, { stop() })
            throw XmaxError.from(error)
        }
    }

    suspend fun setAudio(volume: Float? = null, muted: Boolean? = null): Unit = withContext(Dispatchers.Main.immediate) {
        volume?.let { this@ExternalVideoController.volume = it }
        muted?.let { this@ExternalVideoController.muted = it }
        source?.setPreviewAudio(this@ExternalVideoController.volume, this@ExternalVideoController.muted)
    }

    suspend fun stop(): Unit = withContext(Dispatchers.Main.immediate) {
        synchronized(lock) { sink = null }
        val previousSource = source
        val previousTrack = currentTrack
        val hadAudio = hasAudio
        source = null
        currentTrack = null
        hasAudio = false
        muted = false
        cleanupResources(
            { previousSource?.setPreviewAudio(volume, false) },
            { previousSource?.stop() },
            { previousTrack?.let { VideoRenderRegistry.binding(it)?.detach() } },
            { previousTrack?.let(VideoRenderRegistry::unregister) },
            { if (hadAudio) rtc.stopExternalAudioSource() },
        )
    }

    private inner class Sink(
        override val videoFormat: RealtimeVideoFormat,
        private val audioEnabled: Boolean,
    ) : RealtimeExternalFrameSink {
        private var lastVideoUs = -1L
        private var lastAudioUs = -1L
        override val isRequestingFrames: Boolean
            get() = synchronized(lock) { sink === this && requestingFrames() }

        override fun pushVideo(rgba: ByteArray, timestampUs: Long) = submit {
            if (timestampUs <= lastVideoUs) return@submit
            require(rgba.size.toLong() == videoFormat.width.toLong() * videoFormat.height * 4)
            lastVideoUs = timestampUs
            videoListener(VideoFrame(
                VideoFormat(videoFormat.width, videoFormat.height, VideoPixelFormat.RGBA),
                timestampUs,
                listOf(VideoFramePlane(rgba, videoFormat.width * 4)),
            ))
        }

        override fun pushAudio(pcm: ByteArray, timestampUs: Long) = submit {
            if (!audioEnabled || timestampUs <= lastAudioUs) return@submit
            require(pcm.size == 960)
            lastAudioUs = timestampUs
            audioListener(AudioFrame(pcm, timestampUs))
        }

        override fun reportError(error: Throwable) {
            if (synchronized(lock) { sink === this }) {
                errorListener(XmaxError(XmaxErrorCode.MEDIA_ERROR, "External media source failed", cause = error))
            }
        }

        private fun submit(action: () -> Unit) {
            try {
                synchronized(lock) {
                    if (sink !== this || !requestingFrames()) return
                    action()
                }
            } catch (error: Exception) { reportError(error) }
        }
    }
}
