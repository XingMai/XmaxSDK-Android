package com.xmax.xlab.modules.xlrealtime.recording

import android.graphics.ImageFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/** 同一后台任务独占 MediaCodec/MediaMuxer；H.264 视频轨道，不采集或复用本地音频。 */
internal class Mp4VideoEncoder(file: File, private val format: RecordingFormat) : RecordingEncoder {
    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var codecStarted = false
    private var muxerStarted = false
    private var trackIndex = -1
    private var sampleCount = 0
    private val timeline = RecordingTimeline()
    private val bufferInfo = MediaCodec.BufferInfo()

    init {
        try {
            val mediaFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, format.width, format.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, (format.width.toLong() * format.height * 4).coerceIn(2_000_000, 20_000_000).toInt())
                setInteger(MediaFormat.KEY_FRAME_RATE, 24)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                // Baseline 避免 B 帧重排，输出 PTS 与源帧顺序保持一致。
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            }
            val name = checkNotNull(MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(mediaFormat)) {
                "当前设备不支持录制 ${format.width}×${format.height} 的 H.264 视频"
            }
            codec = MediaCodec.createByCodecName(name)
            codec!!.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer!!.setOrientationHint(format.rotationDegrees)
            codec!!.start()
            codecStarted = true
        } catch (error: Throwable) {
            try { close() } catch (cleanup: Throwable) { if (cleanup !== error) error.addSuppressed(cleanup) }
            throw error
        }
    }

    override fun append(frame: RecordingFrame) {
        check(frame.format == format) { "录制视频格式发生变化" }
        val encoder = checkNotNull(codec)
        val inputIndex = awaitInput(deadline())
        val image = checkNotNull(encoder.getInputImage(inputIndex)) { "编码器没有提供可写的 YUV 图像" }
        image.use {
            check(image.format == ImageFormat.YUV_420_888 && image.planes.size == 3) { "编码器不支持 I420 输入" }
            val crop = image.cropRect
            check(crop.width() >= frame.width && crop.height() >= frame.height && crop.left % 2 == 0 && crop.top % 2 == 0)
            val sources = arrayOf(frame.y, frame.u, frame.v)
            image.planes.forEachIndexed { index, plane ->
                val divisor = if (index == 0) 1 else 2
                copyI420Plane(
                    sources[index], plane.buffer, frame.width / divisor, frame.height / divisor,
                    plane.rowStride, plane.pixelStride,
                    (crop.top / divisor) * plane.rowStride + (crop.left / divisor) * plane.pixelStride,
                )
            }
        }
        val presentationTimeUs = timeline.next(frame.presentationTimeUs, frame.durationUs)
        encoder.queueInputBuffer(inputIndex, 0, frame.width * frame.height * 3 / 2, presentationTimeUs, 0)
        check(!drain(waitForOutput = false)) { "视频编码提前结束" }
    }

    override fun finish() {
        val encoder = checkNotNull(codec)
        val deadline = deadline()
        val index = awaitInput(deadline)
        val endTimeUs = timeline.endTimeUs
        encoder.queueInputBuffer(index, 0, 0, endTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        while (!drain(waitForOutput = true)) checkBeforeDeadline(deadline)
        check(muxerStarted && sampleCount > 0) { "没有可保存的编码视频帧" }
        // 明确最后一帧的时长，单帧录制也能获得非零时长。
        val end = MediaCodec.BufferInfo().apply { set(0, 0, endTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM) }
        muxer!!.writeSampleData(trackIndex, ByteBuffer.allocate(0), end)
        muxer!!.stop()
        muxerStarted = false
    }

    private fun awaitInput(deadline: Long): Int {
        val encoder = checkNotNull(codec)
        while (true) {
            checkBeforeDeadline(deadline)
            val index = encoder.dequeueInputBuffer(POLL_TIMEOUT_US)
            if (index >= 0) return index
            check(!drain(waitForOutput = false)) { "视频编码提前结束" }
        }
    }

    /** 先排出已编码数据再等待新输入，避免编码器的输入/输出队列相互阻塞。 */
    private fun drain(waitForOutput: Boolean): Boolean {
        val encoder = checkNotNull(codec)
        while (true) {
            when (val index = encoder.dequeueOutputBuffer(bufferInfo, if (waitForOutput) POLL_TIMEOUT_US else 0)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(!muxerStarted) { "编码器输出格式发生变化" }
                    trackIndex = muxer!!.addTrack(encoder.outputFormat)
                    muxer!!.start()
                    muxerStarted = true
                }
                else -> if (index >= 0) {
                    val end = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    try {
                        if (bufferInfo.size > 0 && bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            check(muxerStarted) { "编码器尚未提供视频轨道" }
                            val output = checkNotNull(encoder.getOutputBuffer(index))
                            output.position(bufferInfo.offset)
                            output.limit(bufferInfo.offset + bufferInfo.size)
                            muxer!!.writeSampleData(trackIndex, output, bufferInfo)
                            sampleCount++
                        }
                    } finally { encoder.releaseOutputBuffer(index, false) }
                    if (end) return true
                }
            }
        }
    }

    override fun close() {
        val encoder = codec.also { codec = null }
        val writer = muxer.also { muxer = null }
        var failure: Throwable? = null
        fun release(action: () -> Unit) {
            try { action() } catch (error: Throwable) {
                if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error)
            }
        }
        if (encoder != null) {
            if (codecStarted) release { encoder.stop() }
            release { encoder.release() }
        }
        if (writer != null) {
            if (muxerStarted) release { writer.stop() }
            release { writer.release() }
        }
        codecStarted = false
        muxerStarted = false
        failure?.let { throw it }
    }

    private fun deadline() = System.nanoTime() + CODEC_TIMEOUT_NANOS
    private fun checkBeforeDeadline(deadline: Long) {
        check(System.nanoTime() < deadline) { "视频编码超时，请重试" }
    }

    private companion object {
        const val POLL_TIMEOUT_US = 10_000L
        const val CODEC_TIMEOUT_NANOS = 5_000_000_000L
    }
}
