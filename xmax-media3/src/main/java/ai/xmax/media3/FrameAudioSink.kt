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

/** 复用 ExoPlayer 解码的 PCM，按 AudioTrack 播放时钟发送，不把预缓冲音频提前推给 RTC。 */
@UnstableApi
internal class FrameAudioSink(delegate: AudioSink, private val input: () -> RealtimeExternalFrameSink?) : ForwardingAudioSink(delegate) {
    private data class Packet(val data: ByteArray, val ptsUs: Long)
    private val pending = ArrayDeque<Packet>()
    private val packetizer = PcmPacketizer { bytes, pts ->
        if (pending.size >= 100) pending.removeFirst()
        pending.addLast(Packet(bytes, pts))
    }
    private var format: Format? = null
    private var binding: RealtimeExternalFrameSink? = null

    override fun supportsFormat(format: Format): Boolean =
        format.sampleMimeType == MimeTypes.AUDIO_RAW && super.supportsFormat(format)
    override fun getFormatSupport(format: Format): Int =
        if (format.sampleMimeType == MimeTypes.AUDIO_RAW) super.getFormatSupport(format) else AudioSink.SINK_FORMAT_UNSUPPORTED

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        require(inputFormat.pcmEncoding == C.ENCODING_PCM_16BIT) { "Media3 adapter requires PCM16 audio" }
        format = inputFormat
        clearPending()
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
    }

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

    override fun playToEndOfStream() {
        super.playToEndOfStream()
        input()?.takeIf { it.isRequestingFrames }?.let(::drain)
    }
    override fun flush() { clearPending(); super.flush() }
    override fun reset() { clearPending(); super.reset() }
    override fun handleDiscontinuity() { clearPending(); super.handleDiscontinuity() }
    private fun clearPending() { pending.clear(); packetizer.reset() }
}
