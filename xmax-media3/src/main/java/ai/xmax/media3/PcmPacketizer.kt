package ai.xmax.media3

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** PCM16 的跨 buffer 重采样和 10ms 分包；保留相位，避免分块取整造成音频时钟漂移。 */
internal class PcmPacketizer(private val emit: (ByteArray, Long) -> Unit) {
    private var rate = 0
    private var channels = 0
    private var originUs = 0L
    private var inputSamples = 0L
    private var outputSamples = 0L
    private var packetSamples = 0
    private var previous = 0.0
    private val packet = ByteBuffer.allocate(960).order(ByteOrder.LITTLE_ENDIAN)

    fun reset() { rate = 0; channels = 0; inputSamples = 0; outputSamples = 0; packetSamples = 0; packet.clear() }

    fun append(data: ByteBuffer, sampleRate: Int, channelCount: Int, timestampUs: Long) {
        require(sampleRate > 0 && channelCount > 0)
        val expectedUs = originUs + inputSamples * 1_000_000L / sampleRate
        if (rate != sampleRate || channels != channelCount || kotlin.math.abs(timestampUs - expectedUs) > 30_000L) {
            reset(); rate = sampleRate; channels = channelCount; originUs = timestampUs
        }
        val input = data.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        while (input.remaining() >= channelCount * 2) {
            var sum = 0L
            repeat(channelCount) { sum += input.short }
            val current = sum.toDouble() / channelCount
            // 输出采样位置落在 [上一输入采样, 当前输入采样] 时进行线性插值。
            while (outputSamples.toDouble() * rate / 48_000 <= inputSamples) {
                val position = outputSamples.toDouble() * rate / 48_000
                val value = if (inputSamples == 0L) current else {
                    val fraction = (position - (inputSamples - 1)).coerceIn(0.0, 1.0)
                    previous + (current - previous) * fraction
                }
                packet.putShort(value.roundToInt().coerceIn(-32768, 32767).toShort())
                outputSamples++; packetSamples++
                if (packetSamples == 480) {
                    emit(packet.array().copyOf(), originUs + (outputSamples - 480) * 1_000_000 / 48_000)
                    packet.clear(); packetSamples = 0
                }
            }
            previous = current
            inputSamples++
        }
    }
}
