package ai.xmax.sdk.media.video

import ai.xmax.sdk.AudioFrame
import ai.xmax.sdk.VideoContentMode
import ai.xmax.sdk.VideoFrame
import ai.xmax.sdk.VideoRotation
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import ai.xmax.sdk.XmaxLogger
import ai.xmax.sdk.XmaxVideoView
import ai.xmax.sdk.media.MediaAudioFrameListener
import ai.xmax.sdk.media.MediaVideoFrameListener
import ai.xmax.sdk.media.audio.LocalAudioPreviewPlayer
import ai.xmax.sdk.media.audio.PCMFramePacketizer
import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** 使用 Android 媒体框架循环解码共享时间轴上的本地音视频帧。 */
internal class VideoPlayerController(
    context: Context,
    private val videoFrameListener: MediaVideoFrameListener,
    private val audioFrameListener: MediaAudioFrameListener,
    private val errorListener: (XmaxError) -> Unit,
    private val playbackScope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default,
    ),
) : VideoPlayerControlling {
    private val applicationContext = context.applicationContext
    private val operationMutex = Mutex()
    private val stateLock = Any()
    private val audioPreviewPlayer = LocalAudioPreviewPlayer()
    private val previewDispatcher = DecodedVideoPreviewDispatcher()
    private var configuration: PlaybackConfiguration? = null
    private var playbackJob: Job? = null

    override suspend fun configure(
        uri: Uri,
        outputWidth: Int,
        outputHeight: Int,
        rotation: VideoRotation,
        frameRate: Int,
        hasAudio: Boolean,
        durationUs: Long,
    ) = operationMutex.withLock {
        if (outputWidth <= 0 || outputHeight <= 0 ||
            outputWidth % 2 != 0 || outputHeight % 2 != 0 ||
            frameRate <= 0 || durationUs <= 0L ||
            synchronized(stateLock) { configuration != null || playbackJob != null }
        ) {
            throw mediaError("Video player configuration is invalid")
        }
        applicationContext.contentResolver.openAssetFileDescriptor(uri, "r")?.use { Unit }
            ?: throw mediaError("The media file cannot be opened")
        synchronized(stateLock) {
            configuration = PlaybackConfiguration(
                uri = uri,
                outputWidth = outputWidth,
                outputHeight = outputHeight,
                rotation = rotation,
                frameRate = frameRate,
                hasAudio = hasAudio,
                durationUs = durationUs,
            )
        }
    }

    override suspend fun start() = operationMutex.withLock {
        val resolvedConfiguration = synchronized(stateLock) {
            configuration?.takeIf { playbackJob == null }
        } ?: throw mediaError("Configure the video player before starting it")
        if (resolvedConfiguration.hasAudio) audioPreviewPlayer.start()
        val timeline = MediaPlaybackTimeline(resolvedConfiguration.durationUs)
        val job = playbackScope.launch {
            try {
                coroutineScope {
                    val jobs = mutableListOf(
                        launch {
                            produceVideoFrames(resolvedConfiguration, timeline)
                        },
                    )
                    if (resolvedConfiguration.hasAudio) {
                        jobs += launch {
                            produceAudioFrames(resolvedConfiguration, timeline)
                        }
                    }
                    jobs.joinAll()
                }
            } catch (_: CancellationException) {
                Unit
            } catch (error: Throwable) {
                XmaxLogger.error(
                    {
                        "本地视频播放停止 (Local Video Playback Stopped)\n" +
                            "└─ 原因：${playbackErrorDescription(error)}"
                    },
                    category = "Media",
                )
                errorListener(mediaError("Local video playback failed", error))
            }
        }
        synchronized(stateLock) { playbackJob = job }
    }

    override suspend fun setLocalAudioPreviewMuted(muted: Boolean) {
        audioPreviewPlayer.setMuted(muted)
    }

    override suspend fun setLocalAudioVolume(volume: Float) {
        audioPreviewPlayer.setVolume(volume)
    }

    override fun attachPreview(view: XmaxVideoView, contentMode: VideoContentMode) {
        previewDispatcher.attach(view, contentMode)
    }

    override fun detachPreview(view: XmaxVideoView) {
        previewDispatcher.detach(view)
    }

    override suspend fun stop() = operationMutex.withLock {
        val job = synchronized(stateLock) {
            playbackJob.also { playbackJob = null }
        }
        job?.cancel()
        job?.join()
        audioPreviewPlayer.stop()
        previewDispatcher.clear()
        synchronized(stateLock) { configuration = null }
    }

    private suspend fun produceVideoFrames(
        configuration: PlaybackConfiguration,
        timeline: MediaPlaybackTimeline,
    ) {
        var loopIndex = 0
        while (true) {
            coroutineContext.ensureActive()
            if (!decodeVideoCycle(configuration, timeline, loopIndex)) {
                throw mediaError("The media file produced no video frames")
            }
            loopIndex += 1
            sleepUntil(timeline.target(loopIndex, 0L))
        }
    }

    private suspend fun decodeVideoCycle(
        configuration: PlaybackConfiguration,
        timeline: MediaPlaybackTimeline,
        loopIndex: Int,
    ): Boolean {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var yieldedFrame = false
        try {
            extractor.setDataSource(applicationContext, configuration.uri, null)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index)
                    .getString(MediaFormat.KEY_MIME)
                    ?.startsWith("video/") == true
            } ?: throw mediaError("The media file does not contain a video track")
            extractor.selectTrack(trackIndex)
            val sourceFormat = extractor.getTrackFormat(trackIndex)
            val mime = sourceFormat.getString(MediaFormat.KEY_MIME)
                ?: throw mediaError("The media video track has no MIME type")
            sourceFormat.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
            )
            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(sourceFormat, null, null, 0)
                start()
            }
            val firstPresentationTimeUs = extractor.sampleTime.coerceAtLeast(0L)
            val frameIntervalUs = MICROSECONDS_PER_SECOND / configuration.frameRate
            val frameIntervalNanoseconds = frameIntervalUs * NANOSECONDS_PER_MICROSECOND
            val bufferInfo = MediaCodec.BufferInfo()
            val pendingFrames = ArrayDeque<ScheduledVideoFrame>(VIDEO_DECODE_BUFFER_SIZE)
            var inputEnded = false
            var outputEnded = false
            var lastYieldedTimeUs = Long.MIN_VALUE

            while (!outputEnded) {
                coroutineContext.ensureActive()
                if (!inputEnded) {
                    val inputIndex = codec.dequeueInputBuffer(0L)
                    if (inputIndex >= 0) {
                        val input = codec.getInputBuffer(inputIndex)
                            ?: throw mediaError("The video decoder input buffer is unavailable")
                        val sampleSize = extractor.readSampleData(input, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputEnded = true
                        } else {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                sampleSize,
                                extractor.sampleTime.coerceAtLeast(0L),
                                extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC,
                            )
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED,
                    MediaCodec.INFO_TRY_AGAIN_LATER,
                    -> Unit
                    else -> if (outputIndex >= 0) {
                        var frame: VideoFrame? = null
                        val relativeTimeUs = (
                            bufferInfo.presentationTimeUs - firstPresentationTimeUs
                            ).coerceAtLeast(0L)
                        val hasFrameIntervalElapsed = lastYieldedTimeUs == Long.MIN_VALUE ||
                            relativeTimeUs - lastYieldedTimeUs >= frameIntervalUs * 3 / 4
                        val target = timeline.target(loopIndex, relativeTimeUs)
                        val isOnTime = SystemClock.elapsedRealtimeNanos() - target <=
                            frameIntervalNanoseconds
                        if (bufferInfo.size > 0 && hasFrameIntervalElapsed && isOnTime) {
                            val image = codec.getOutputImage(outputIndex)
                                ?: throw mediaError("The video decoder did not provide a YUV image")
                            frame = try {
                                Yuv420VideoFrameConverter.convert(
                                    image = image,
                                    outputWidth = configuration.outputWidth,
                                    outputHeight = configuration.outputHeight,
                                    rotation = configuration.rotation,
                                    timestampUs = target / NANOSECONDS_PER_MICROSECOND,
                                )
                            } finally {
                                image.close()
                            }
                            lastYieldedTimeUs = relativeTimeUs
                            yieldedFrame = true
                        }
                        outputEnded = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(outputIndex, false)
                        if (frame != null) {
                            pendingFrames.addLast(ScheduledVideoFrame(frame, target))
                            if (pendingFrames.size >= VIDEO_DECODE_BUFFER_SIZE) {
                                emitVideoFrame(pendingFrames.removeFirst())
                            }
                        }
                    }
                }
            }
            while (pendingFrames.isNotEmpty()) {
                emitVideoFrame(pendingFrames.removeFirst())
            }
            return yieldedFrame
        } finally {
            runCatching { codec?.stop() }
            codec?.release()
            extractor.release()
        }
    }

    private suspend fun produceAudioFrames(
        configuration: PlaybackConfiguration,
        timeline: MediaPlaybackTimeline,
    ) {
        var loopIndex = 0
        while (true) {
            coroutineContext.ensureActive()
            decodeAudioCycle(configuration, timeline, loopIndex)
            loopIndex += 1
        }
    }

    private suspend fun emitVideoFrame(scheduledFrame: ScheduledVideoFrame) {
        sleepUntil(scheduledFrame.targetNanoseconds)
        previewDispatcher.enqueue(scheduledFrame.frame)
        videoFrameListener(scheduledFrame.frame)
    }

    private suspend fun decodeAudioCycle(
        configuration: PlaybackConfiguration,
        timeline: MediaPlaybackTimeline,
        loopIndex: Int,
    ) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(applicationContext, configuration.uri, null)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index)
                    .getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: throw mediaError("The media file does not contain an audio track")
            extractor.selectTrack(trackIndex)
            val sourceFormat = extractor.getTrackFormat(trackIndex)
            val mime = sourceFormat.getString(MediaFormat.KEY_MIME)
                ?: throw mediaError("The media audio track has no MIME type")
            sourceFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(sourceFormat, null, null, 0)
                start()
            }
            val firstPresentationTimeUs = extractor.sampleTime.coerceAtLeast(0L)
            val packetizer = PCMFramePacketizer(timeline.cycleSampleCount)
            val bufferInfo = MediaCodec.BufferInfo()
            var inputEnded = false
            var outputEnded = false
            var outputSampleRate = sourceFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var outputChannelCount = sourceFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var outputEncoding = AudioFormat.ENCODING_PCM_16BIT

            while (!outputEnded) {
                coroutineContext.ensureActive()
                if (!inputEnded) {
                    val inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val input = codec.getInputBuffer(inputIndex)
                            ?: throw mediaError("The audio decoder input buffer is unavailable")
                        val sampleSize = extractor.readSampleData(input, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputEnded = true
                        } else {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                sampleSize,
                                extractor.sampleTime.coerceAtLeast(0L),
                                0,
                            )
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outputFormat = codec.outputFormat
                        outputSampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        outputChannelCount = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        outputEncoding = if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        } else {
                            AudioFormat.ENCODING_PCM_16BIT
                        }
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER,
                    -> Unit
                    else -> if (outputIndex >= 0) {
                        val output = codec.getOutputBuffer(outputIndex)
                        if (bufferInfo.size > 0 && output != null) {
                            val bytes = output.selectedBytes(bufferInfo.offset, bufferInfo.size)
                            val samples = convertToRtcSamples(
                                bytes = bytes,
                                encoding = outputEncoding,
                                channelCount = outputChannelCount,
                                sampleRate = outputSampleRate,
                            )
                            val relativePresentationUs =
                                (bufferInfo.presentationTimeUs - firstPresentationTimeUs)
                                    .coerceAtLeast(0L)
                            val sampleOffset = (
                                relativePresentationUs * AudioFrame.sampleRate /
                                    MICROSECONDS_PER_SECOND
                                ).toInt()
                            packetizer.append(samples, sampleOffset)
                            emitAvailableAudioFrames(packetizer, timeline, loopIndex)
                        }
                        outputEnded = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
            packetizer.finishWithSilence()
            emitAvailableAudioFrames(packetizer, timeline, loopIndex)
        } finally {
            runCatching { codec?.stop() }
            codec?.release()
            extractor.release()
        }
    }

    private suspend fun emitAvailableAudioFrames(
        packetizer: PCMFramePacketizer,
        timeline: MediaPlaybackTimeline,
        loopIndex: Int,
    ) {
        while (true) {
            val packet = packetizer.nextFrame() ?: return
            val target = timeline.target(loopIndex, packet.sampleOffset)
            val now = SystemClock.elapsedRealtimeNanos()
            if (now < target) {
                sleepUntil(target)
            } else if (now - target > MAXIMUM_AUDIO_LATENESS_NANOSECONDS) {
                continue
            }
            val frame = AudioFrame(
                data = packet.data,
                timestampUs = target / NANOSECONDS_PER_MICROSECOND,
            )
            audioPreviewPlayer.enqueue(frame)
            audioFrameListener(frame)
        }
    }

    private fun convertToRtcSamples(
        bytes: ByteArray,
        encoding: Int,
        channelCount: Int,
        sampleRate: Int,
    ): ShortArray {
        if (channelCount <= 0 || sampleRate <= 0) {
            throw mediaError("The decoded audio format is invalid")
        }
        val interleaved = when (encoding) {
            AudioFormat.ENCODING_PCM_16BIT -> {
                val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                ShortArray(bytes.size / Short.SIZE_BYTES) { buffer.short }
            }
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                ShortArray(bytes.size / Float.SIZE_BYTES) {
                    (buffer.float.coerceIn(-1f, 1f) * Short.MAX_VALUE).roundToInt().toShort()
                }
            }
            else -> throw mediaError("The decoded audio PCM format is unsupported")
        }
        val sourceFrameCount = interleaved.size / channelCount
        if (sourceFrameCount == 0) return ShortArray(0)
        val mono = ShortArray(sourceFrameCount) { frameIndex ->
            var sum = 0L
            repeat(channelCount) { channelIndex ->
                sum += interleaved[frameIndex * channelCount + channelIndex]
            }
            (sum / channelCount).toShort()
        }
        if (sampleRate == AudioFrame.sampleRate) return mono
        val outputCount = (sourceFrameCount.toLong() * AudioFrame.sampleRate / sampleRate)
            .toInt().coerceAtLeast(1)
        return ShortArray(outputCount) { outputIndex ->
            val sourcePosition = outputIndex.toDouble() * sampleRate / AudioFrame.sampleRate
            val leftIndex = sourcePosition.toInt().coerceAtMost(mono.lastIndex)
            val rightIndex = (leftIndex + 1).coerceAtMost(mono.lastIndex)
            val fraction = sourcePosition - leftIndex
            (mono[leftIndex] + (mono[rightIndex] - mono[leftIndex]) * fraction)
                .roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }
    }

    private suspend fun sleepUntil(targetNanoseconds: Long) {
        while (true) {
            val remaining = targetNanoseconds - SystemClock.elapsedRealtimeNanos()
            if (remaining <= 0L) return
            delay((remaining / NANOSECONDS_PER_MILLISECOND).coerceAtLeast(1L))
        }
    }

    private fun ByteBuffer.selectedBytes(offset: Int, size: Int): ByteArray =
        duplicate().apply {
            position(offset)
            limit(offset + size)
        }.let { selected ->
            ByteArray(size).also(selected::get)
        }

    private fun mediaError(message: String, cause: Throwable? = null): XmaxError =
        XmaxError(XmaxErrorCode.MEDIA_ERROR, message, cause = cause)

    private fun playbackErrorDescription(error: Throwable): String =
        generateSequence(error) { it.cause }
            .map { cause ->
                val code = (cause as? XmaxError)?.code?.let { "[$it] " }.orEmpty()
                code + (cause.message?.trim()?.takeIf(String::isNotEmpty)
                    ?: cause.javaClass.simpleName)
            }
            .distinct()
            .joinToString(" <- ")

    private data class PlaybackConfiguration(
        val uri: Uri,
        val outputWidth: Int,
        val outputHeight: Int,
        val rotation: VideoRotation,
        val frameRate: Int,
        val hasAudio: Boolean,
        val durationUs: Long,
    )

    private data class ScheduledVideoFrame(
        val frame: VideoFrame,
        val targetNanoseconds: Long,
    )

    private data class PreviewTarget(
        val view: XmaxVideoView?,
        val contentMode: VideoContentMode,
        val generation: Long,
    )

    private inner class DecodedVideoPreviewDispatcher {
        private val pendingFrame = AtomicReference<VideoFrame?>(null)
        private val deliveryScheduled = AtomicBoolean(false)
        private val previewLock = Any()
        private var view: WeakReference<XmaxVideoView>? = null
        private var contentMode = VideoContentMode.FILL
        private var generation = 0L

        fun attach(view: XmaxVideoView, contentMode: VideoContentMode) {
            synchronized(previewLock) {
                this.view = WeakReference(view)
                this.contentMode = contentMode
                generation += 1L
            }
            view.prepareDecodedVideoPreview(contentMode)
        }

        fun detach(view: XmaxVideoView) {
            synchronized(previewLock) {
                if (this.view?.get() === view) {
                    this.view = null
                    generation += 1L
                }
            }
            view.clearDecodedVideoPreview()
        }

        fun enqueue(frame: VideoFrame) {
            if (synchronized(previewLock) { view?.get() == null }) return
            pendingFrame.set(frame)
            scheduleDelivery()
        }

        fun clear() {
            pendingFrame.set(null)
            val currentView = synchronized(previewLock) {
                generation += 1L
                view?.get().also { view = null }
            }
            currentView?.clearDecodedVideoPreview()
        }

        private fun scheduleDelivery() {
            if (deliveryScheduled.compareAndSet(false, true)) {
                playbackScope.launch { deliverLatestFrames() }
            }
        }

        private suspend fun deliverLatestFrames() {
            try {
                while (true) {
                    val frame = pendingFrame.getAndSet(null) ?: return
                    val preview = synchronized(previewLock) {
                        PreviewTarget(view?.get(), contentMode, generation)
                    }
                    if (preview.view == null) continue
                    val bitmap = try {
                        Yuv420VideoFrameConverter.makePreviewBitmap(frame)
                    } catch (error: Throwable) {
                        XmaxLogger.warn(
                            {
                                "本地视频预览帧转换失败 (Failed to Convert Local Video Preview)\n" +
                                    "└─ 原因：${playbackErrorDescription(error)}"
                            },
                            category = "Media",
                        )
                        continue
                    }
                    withContext(Dispatchers.Main.immediate) {
                        val remainsAttached = synchronized(previewLock) {
                            generation == preview.generation && view?.get() === preview.view
                        }
                        if (remainsAttached) {
                            preview.view.displayDecodedVideoBitmap(bitmap, preview.contentMode)
                        } else {
                            bitmap.recycle()
                        }
                    }
                }
            } finally {
                deliveryScheduled.set(false)
                if (pendingFrame.get() != null) scheduleDelivery()
            }
        }
    }

    private companion object {
        const val CODEC_TIMEOUT_US = 10_000L
        const val MICROSECONDS_PER_SECOND = 1_000_000L
        const val NANOSECONDS_PER_MICROSECOND = 1_000L
        const val NANOSECONDS_PER_MILLISECOND = 1_000_000L
        const val MAXIMUM_AUDIO_LATENESS_NANOSECONDS = 30_000_000L
        const val VIDEO_DECODE_BUFFER_SIZE = 2
    }
}
