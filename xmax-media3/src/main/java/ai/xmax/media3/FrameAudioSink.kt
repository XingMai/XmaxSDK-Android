//
//  FrameAudioSink.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.media3

import ai.xmax.sdk.RealtimeExternalFrameSink
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer
import java.util.ArrayDeque

/**
 * 复用 ExoPlayer 解码的 PCM，按 AudioTrack 播放时钟发送，不把预缓冲音频提前推给 RTC。
 *
 * @param delegate 负责实际音频播放的接收器。
 * @param input 取得当前外部音视频帧接收器的函数。
 */
@UnstableApi
internal class FrameAudioSink(delegate: AudioSink, private val input: () -> RealtimeExternalFrameSink?) : ForwardingAudioSink(delegate) {
    /**
     * 一份可提交的 PCM 音频数据及其时间位置。
     *
     * @param data 待读取、传输或持有的二进制数据。
     * @param ptsUs 音频包的媒体呈现时间，单位为微秒。
     */
    private data class Packet(val data: ByteArray, val ptsUs: Long)

    /**
     * 待提交 PCM 包及固定长度分包器。
     */
    private val pending = ArrayDeque<Packet>()
    private val packetizer = PcmPacketizer { bytes, pts ->
        if (pending.size >= 100) pending.removeFirst()
        pending.addLast(Packet(bytes, pts))
    }

    /**
     * 当前音频格式和外部帧接收器。
     */
    private var format: Format? = null
    private var binding: RealtimeExternalFrameSink? = null

    /**
     * 判断音频接收器是否支持指定音频格式。
     *
     * @param format 音频采样编码、采样率和声道格式。
     * @return 支持当前音频格式时为 true。
     */
    override fun supportsFormat(format: Format): Boolean =
        format.sampleMimeType == MimeTypes.AUDIO_RAW && super.supportsFormat(format)

    /**
     * 取得音频接收器对指定格式的支持等级。
     *
     * @param format 音频采样编码、采样率和声道格式。
     * @return Media3 的音频格式支持等级常量。
     */
    override fun getFormatSupport(format: Format): Int =
        if (format.sampleMimeType == MimeTypes.AUDIO_RAW) super.getFormatSupport(format) else AudioSink.SINK_FORMAT_UNSUPPORTED

    /**
     * 设置 PCM16 音频格式和播放缓冲，并清空上一格式的待发送数据。
     *
     * @param inputFormat 输入音频的采样编码、采样率和声道配置。
     * @param specifiedBufferSize 播放器要求的音频缓冲大小，单位为字节。
     * @param outputChannels 输出声道映射；null 表示保留默认声道映射。
     */
    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        require(inputFormat.pcmEncoding == C.ENCODING_PCM_16BIT) { "Media3 adapter requires PCM16 audio" }
        format = inputFormat
        clearPending()
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
    }

    /**
     * 将解码音频交给播放接收器，并缓冲可提交的外部 PCM 数据。
     *
     * @param buffer 包含待处理数据的字节缓冲区。
     * @param presentationTimeUs 音视频数据的媒体呈现时间，单位为微秒。
     * @param encodedAccessUnitCount 缓冲区内编码音频访问单元的数量。
     * @return 缓冲区已由音频接收器完整消费时为 true。
     */
    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        val start = buffer.position()
        val copy = buffer.duplicate()
        val consumed = super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        val sink = input()?.takeIf { it.isRequestingFrames }
        if (binding !== sink) { clearPending(); binding = sink }
        val f = format
        if (sink != null && f != null && buffer.position() > start) {
            copy.limit(buffer.position())
            packetizer.append(copy, f.sampleRate, f.channelCount,
                presentationTimeUs + start.toLong() / (2 * f.channelCount) * 1_000_000 / f.sampleRate)
            drain(sink)
        }
        return consumed
    }

    /**
     * 按音频播放位置提交到期的 PCM 包，丢弃已严重过时的包。
     *
     * @param sink SDK 提供的外部音视频帧接收器。
     */
    private fun drain(sink: RealtimeExternalFrameSink) {
        val positionUs = super.getCurrentPositionUs(false)
        if (positionUs == Long.MIN_VALUE) return
        while (pending.isNotEmpty() && pending.first.ptsUs <= positionUs + 10_000) {
            val packet = pending.removeFirst()
            val ageUs = positionUs - packet.ptsUs
            if (ageUs > 100_000) continue
            sink.pushAudio(packet.data, (SystemClock.elapsedRealtimeNanos() / 1000 - ageUs).coerceAtLeast(0))
        }
    }

    /**
     * 提交已缓冲的音频尾部并完成播放。
     */
    override fun playToEndOfStream() {
        super.playToEndOfStream()
        input()?.takeIf { it.isRequestingFrames }?.let(::drain)
    }

    /**
     * 清空音频缓冲并重置播放进度。
     */
    override fun flush() { clearPending(); super.flush() }

    /**
     * 重置音频接收器并清除待发送的 PCM 数据。
     */
    override fun reset() { clearPending(); super.reset() }

    /**
     * 在音频时间线不连续时清空待提交的旧音频。
     */
    override fun handleDiscontinuity() { clearPending(); super.handleDiscontinuity() }

    /**
     * 清空待发送的 PCM 数据和分包状态。
     */
    private fun clearPending() { pending.clear(); packetizer.reset() }
}
