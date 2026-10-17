//
//  VideoPlayerController.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.media.video

import ai.xmax.sdk.cleanupAfterFailure
import ai.xmax.sdk.cleanupResources
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
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

/**
 * 使用 Android 媒体框架循环解码共享时间轴上的本地音视频帧。
 *
 * @param context 访问 Android 系统服务或媒体资源所需的上下文。
 * @param videoFrameListener 接收解码视频帧的监听器。
 * @param audioFrameListener 接收解码音频帧的监听器。
 * @param errorListener 接收媒体错误的回调。
 * @param playbackScope 执行持续解码播放的协程作用域。
 */
internal class VideoPlayerController(
    context: Context,
    private val videoFrameListener: MediaVideoFrameListener,
    private val audioFrameListener: MediaAudioFrameListener,
    private val errorListener: (XmaxError) -> Unit,
    private val playbackScope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default,
    ),
) : VideoPlayerControlling {
    /**
     * 媒体读取所需的 Android 运行环境。
     */
    private val applicationContext = context.applicationContext

    /**
     * 解码播放状态的并发保护。
     */
    private val operationMutex = Mutex()
    private val stateLock = Any()

    /**
     * 本地音频播放及视频预览派发组件。
     */
    private val audioPreviewPlayer = LocalAudioPreviewPlayer()
    private val previewDispatcher = DecodedVideoPreviewDispatcher()

    /**
     * 当前播放配置与循环解码任务。
     */
    private var configuration: PlaybackConfiguration? = null
    private var playbackJob: Job? = null

    /**
     * 设置解码源、输出尺寸、旋转方向、帧率及播放时长。
     *
     * @param uri 应用有读取权限的媒体地址。
     * @param outputWidth 输出画面宽度，单位为像素。
     * @param outputHeight 输出画面高度，单位为像素。
     * @param rotation 应用到源画面的旋转方向。
     * @param frameRate 视频帧率，单位为 fps。
     * @param hasAudio 输入媒体是否包含可用音频。
     * @param durationUs 媒体持续时间，单位为微秒。
     */
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

    /**
     * 启动循环解码，并按统一播放时间线输出音视频帧。
     */
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
                XmaxLogger.media.error(
                    message = {
                        "本地视频播放停止 (Local Video Playback Stopped)\n" +
                            "└─ ${XmaxLogger.localized("原因：", "Reason: ")}${playbackErrorDescription(error)}"
                    },
                )
                errorListener(mediaError("Local video playback failed", error))
            }
        }
        synchronized(stateLock) { playbackJob = job }
    }

    /**
     * 设置本地预览是否静音，不改变保存的音量值。
     *
     * @param muted 是否静音。
     */
    override suspend fun setLocalAudioPreviewMuted(muted: Boolean) {
        audioPreviewPlayer.setMuted(muted)
    }

    /**
     * 设置当前本地媒体的预览音量，范围为 0 到 1，不影响提交给 RTC 的音频数据。
     *
     * @param volume 播放音量，范围为 0 到 1。
     */
    override suspend fun setLocalAudioVolume(volume: Float) {
        audioPreviewPlayer.setVolume(volume)
    }

    /**
     * 将本地媒体预览绑定到指定视图并应用显示模式。
     *
     * @param view 需要绑定或解除绑定的显示视图。
     * @param contentMode 视频等比适配或等比裁切的显示方式。
     */
    override fun attachPreview(view: XmaxVideoView, contentMode: VideoContentMode) {
        previewDispatcher.attach(view, contentMode)
    }

    /**
     * 解除指定视图的本地媒体预览绑定。
     *
     * @param view 需要绑定或解除绑定的显示视图。
     */
    override fun detachPreview(view: XmaxVideoView) {
        previewDispatcher.detach(view)
    }

    /**
     * 停止解码和音频预览，清除待发送及待显示的媒体帧。
     */
    override suspend fun stop() = operationMutex.withLock {
        val job = synchronized(stateLock) {
            playbackJob.also { playbackJob = null }
        }
        cleanupResources(
            { job?.cancel(); job?.join() },
            { audioPreviewPlayer.stop() },
            { previewDispatcher.clear() },
            { synchronized(stateLock) { configuration = null } },
        )
    }

    /**
     * 按播放时间线持续解码并提交视频帧。
     *
     * @param configuration 本地媒体解码和播放输出配置。
     * @param timeline 协调音视频帧输出时刻的播放时间线。
     */
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

    /**
     * 解码一次视频循环并按时间线提交帧。
     *
     * @param configuration 本地媒体解码和播放输出配置。
     * @param timeline 协调音视频帧输出时刻的播放时间线。
     * @param loopIndex 从零开始的媒体循环次数。
     * @return 本轮已经输出视频帧时为 true，否则为 false。
     */
    private suspend fun decodeVideoCycle(
        configuration: PlaybackConfiguration,
        timeline: MediaPlaybackTimeline,
        loopIndex: Int,
    ): Boolean {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var decodingFailure: Throwable? = null
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
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(sourceFormat, null, null, 0)
            codec.start()
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
        } catch (error: Throwable) {
            decodingFailure = error
            throw error
        } finally {
            val failure = decodingFailure
            if (failure == null) {
                cleanupResources({ codec?.stop() }, { codec?.release() }, { extractor.release() })
            } else {
                cleanupAfterFailure(failure, { codec?.stop() }, { codec?.release() }, { extractor.release() })
            }
        }
    }

    /**
     * 按播放时间线持续解码并提交音频帧。
     *
     * @param configuration 本地媒体解码和播放输出配置。
     * @param timeline 协调音视频帧输出时刻的播放时间线。
     */
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

    /**
     * 在计划时间提交视频帧并更新本地预览。
     *
     * @param scheduledFrame 视频帧及其计划输出时刻。
     */
    private suspend fun emitVideoFrame(scheduledFrame: ScheduledVideoFrame) {
        sleepUntil(scheduledFrame.targetNanoseconds)
        previewDispatcher.enqueue(scheduledFrame.frame)
        videoFrameListener(scheduledFrame.frame)
    }

    /**
     * 解码一次音频循环并按固定 PCM 包大小提交。
     *
     * @param configuration 本地媒体解码和播放输出配置。
     * @param timeline 协调音视频帧输出时刻的播放时间线。
     * @param loopIndex 从零开始的媒体循环次数。
     */
    private suspend fun decodeAudioCycle(
        configuration: PlaybackConfiguration,
        timeline: MediaPlaybackTimeline,
        loopIndex: Int,
    ) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var decodingFailure: Throwable? = null
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
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(sourceFormat, null, null, 0)
            codec.start()
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
        } catch (error: Throwable) {
            decodingFailure = error
            throw error
        } finally {
            val failure = decodingFailure
            if (failure == null) {
                cleanupResources({ codec?.stop() }, { codec?.release() }, { extractor.release() })
            } else {
                cleanupAfterFailure(failure, { codec?.stop() }, { codec?.release() }, { extractor.release() })
            }
        }
    }

    /**
     * 提交已缓冲的完整 PCM 音频包。
     *
     * @param packetizer 将 PCM 采样组合为固定长度帧的分包器。
     * @param timeline 协调音视频帧输出时刻的播放时间线。
     * @param loopIndex 从零开始的媒体循环次数。
     */
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

    /**
     * 将解码音频转换为 RTC 所需的采样率、声道数和 PCM 采样格式。
     *
     * @param bytes 需要解码或传输的字节数据。
     * @param encoding Android 音频采样编码格式。
     * @param channelCount 音频声道数。
     * @param sampleRate 音频采样率，单位为 Hz。
     * @return 符合 RTC 音频采样格式的 PCM16 数据。
     */
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

    /**
     * 挂起至指定的单调时钟时刻，支持协程取消。
     *
     * @param targetNanoseconds 等待到达的单调时钟时刻，单位为纳秒。
     */
    private suspend fun sleepUntil(targetNanoseconds: Long) {
        while (true) {
            val remaining = targetNanoseconds - SystemClock.elapsedRealtimeNanos()
            if (remaining <= 0L) return
            delay((remaining / NANOSECONDS_PER_MILLISECOND).coerceAtLeast(1L))
        }
    }

    /**
     * 复制缓冲区当前有效范围内的字节。
     *
     * @param offset 有效数据的起始偏移。
     * @param size 有效数据的字节数。
     * @return 有效数据对应的字节数组。
     */
    private fun ByteBuffer.selectedBytes(offset: Int, size: Int): ByteArray =
        duplicate().apply {
            position(offset)
            limit(offset + size)
        }.let { selected ->
            ByteArray(size).also(selected::get)
        }

    /**
     * 创建包含媒体处理原因的 SDK 错误。
     *
     * @param message 待发送或记录的消息文本。
     * @param cause 触发当前错误的底层异常。
     * @return 包含错误类别、描述和可用底层原因的 SDK 错误。
     */
    private fun mediaError(message: String, cause: Throwable? = null): XmaxError =
        XmaxError(XmaxErrorCode.MEDIA_ERROR, message, cause = cause)

    /**
     * 生成包含播放错误原因的描述文本。
     *
     * @param error 本次操作失败的异常或 SDK 错误。
     * @return 包含播放失败原因的描述文本。
     */
    private fun playbackErrorDescription(error: Throwable): String =
        generateSequence(error) { it.cause }
            .map { cause ->
                val code = (cause as? XmaxError)?.code?.let { "[$it] " }.orEmpty()
                code + (cause.message?.trim()?.takeIf(String::isNotEmpty)
                    ?: cause.javaClass.simpleName)
            }
            .distinct()
            .joinToString(" <- ")

    /**
     * 控制本地媒体解码、旋转、帧输出和循环播放的配置。
     *
     * @param uri 应用有读取权限的媒体地址。
     * @param outputWidth 输出画面宽度，单位为像素。
     * @param outputHeight 输出画面高度，单位为像素。
     * @param rotation 应用到源画面的旋转方向。
     * @param frameRate 视频帧率，单位为 fps。
     * @param hasAudio 输入媒体是否包含可用音频。
     * @param durationUs 媒体持续时间，单位为微秒。
     */
    private data class PlaybackConfiguration(
        val uri: Uri,
        val outputWidth: Int,
        val outputHeight: Int,
        val rotation: VideoRotation,
        val frameRate: Int,
        val hasAudio: Boolean,
        val durationUs: Long,
    )

    /**
     * 保存视频帧及其应提交的播放时间。
     *
     * @param frame 待处理、显示或提交的视频帧。
     * @param targetNanoseconds 等待到达的单调时钟时刻，单位为纳秒。
     */
    private data class ScheduledVideoFrame(
        val frame: VideoFrame,
        val targetNanoseconds: Long,
    )

    /**
     * 保存当前预览视图、显示模式及绑定代次。
     *
     * @param view 需要绑定或解除绑定的显示视图。
     * @param contentMode 视频等比适配或等比裁切的显示方式。
     * @param generation 用于识别当前生成请求或事件归属的标识。
     */
    private data class PreviewTarget(
        val view: XmaxVideoView?,
        val contentMode: VideoContentMode,
        val generation: Long,
    )

    /**
     * 将最新解码帧派发到预览视图，避免慢显示积压旧帧。
     */
    private inner class DecodedVideoPreviewDispatcher {
        /**
         * 待显示的最新帧及派发标记。
         */
        private val pendingFrame = AtomicReference<VideoFrame?>(null)
        private val deliveryScheduled = AtomicBoolean(false)

        /**
         * 预览视图绑定的并发保护。
         */
        private val previewLock = Any()

        /**
         * 当前预览视图、显示模式及绑定代次。
         */
        private var view: WeakReference<XmaxVideoView>? = null
        private var contentMode = VideoContentMode.FILL
        private var generation = 0L

        /**
         * 将本地解码视频预览绑定到指定视图。
         *
         * @param view 需要绑定或解除绑定的显示视图。
         * @param contentMode 视频等比适配或等比裁切的显示方式。
         */
        fun attach(view: XmaxVideoView, contentMode: VideoContentMode) {
            synchronized(previewLock) {
                this.view = WeakReference(view)
                this.contentMode = contentMode
                generation += 1L
            }
            view.prepareDecodedVideoPreview(contentMode)
        }

        /**
         * 解除指定视图的本地解码预览绑定。
         *
         * @param view 需要绑定或解除绑定的显示视图。
         */
        fun detach(view: XmaxVideoView) {
            synchronized(previewLock) {
                if (this.view?.get() === view) {
                    this.view = null
                    generation += 1L
                }
            }
            view.clearDecodedVideoPreview()
        }

        /**
         * 用最新视频帧更新待显示预览，替换尚未显示的旧帧。
         *
         * @param frame 待处理、显示或提交的视频帧。
         */
        suspend fun enqueue(frame: VideoFrame) {
            if (synchronized(previewLock) { view?.get() == null }) return
            pendingFrame.set(frame)
            scheduleDelivery(CoroutineScope(currentCoroutineContext()))
        }

        /**
         * 清空待显示预览帧并解除当前预览目标。
         */
        fun clear() {
            pendingFrame.set(null)
            val currentView = synchronized(previewLock) {
                generation += 1L
                view?.get().also { view = null }
            }
            currentView?.clearDecodedVideoPreview()
        }

        /**
         * 安排本地预览帧派发，避免重复启动派发任务。
         *
         * @param owner 运行异步派发任务的协程作用域。
         */
        private fun scheduleDelivery(owner: CoroutineScope) {
            if (owner.isActive && deliveryScheduled.compareAndSet(false, true)) {
                owner.launch { deliverLatestFrames() }
            }
        }

        /**
         * 向预览视图派发最新帧，跳过已经过时的待显示帧。
         */
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
                        XmaxLogger.media.warn(
                            message = {
                                "本地视频预览帧转换失败 (Failed to Convert Local Video Preview)\n" +
                                    "└─ ${XmaxLogger.localized("原因：", "Reason: ")}${playbackErrorDescription(error)}"
                            },
                        )
                        continue
                    }
                    var delivered = false
                    try {
                        withContext(Dispatchers.Main.immediate) {
                            val remainsAttached = synchronized(previewLock) {
                                generation == preview.generation && view?.get() === preview.view
                            }
                            if (remainsAttached) {
                                preview.view.displayDecodedVideoBitmap(bitmap, preview.contentMode)
                                delivered = true
                            }
                        }
                    } finally {
                        if (!delivered) bitmap.recycle()
                    }
                }
            } finally {
                deliveryScheduled.set(false)
                if (pendingFrame.get() != null) scheduleDelivery(CoroutineScope(currentCoroutineContext()))
            }
        }
    }

    /**
     * 提供 VideoPlayerController 使用的构造入口、共享配置或常量。
     */
    private companion object {
        /**
         * 共享常量、默认配置与工厂入口。
         */
        const val CODEC_TIMEOUT_US = 10_000L
        const val MICROSECONDS_PER_SECOND = 1_000_000L
        const val NANOSECONDS_PER_MICROSECOND = 1_000L
        const val NANOSECONDS_PER_MILLISECOND = 1_000_000L
        const val MAXIMUM_AUDIO_LATENESS_NANOSECONDS = 30_000_000L
        const val VIDEO_DECODE_BUFFER_SIZE = 2
    }
}
