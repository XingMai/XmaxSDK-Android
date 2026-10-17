//
//  PcmPacketizer.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.media3

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * PCM16 的跨 buffer 重采样和 10ms 分包；保留相位，避免分块取整造成音频时钟漂移。
 *
 * @param emit 接收完整音频包及其微秒时间戳的回调。
 */
internal class PcmPacketizer(private val emit: (ByteArray, Long) -> Unit) {
    /**
     * 输入采样率、声道数与媒体时间基准。
     */
    private var rate = 0
    private var channels = 0
    private var originUs = 0L

    /**
     * 重采样输入输出进度及音频包缓冲。
     */
    private var inputSamples = 0L
    private var outputSamples = 0L
    private var packetSamples = 0
    private var previous = 0.0
    private val packet = ByteBuffer.allocate(960).order(ByteOrder.LITTLE_ENDIAN)

    /**
     * 清空音频分包状态，为新的格式和媒体时间线重新计数。
     */
    fun reset() { rate = 0; channels = 0; inputSamples = 0; outputSamples = 0; packetSamples = 0; packet.clear() }

    /**
     * 将解码 PCM 数据重采样并组合为 SDK 所需的音频包。
     *
     * @param data 包含有效 PCM 数据的缓冲区。
     * @param sampleRate 音频采样率，单位为 Hz。
     * @param channelCount 音频声道数。
     * @param timestampUs 帧的单调时间戳，单位为微秒。
     */
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
            /**
             * 输出采样位置落在 [上一输入采样, 当前输入采样] 时进行线性插值。
             */
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
