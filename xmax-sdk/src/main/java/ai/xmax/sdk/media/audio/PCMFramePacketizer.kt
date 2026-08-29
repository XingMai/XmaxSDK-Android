package ai.xmax.sdk.media.audio

import ai.xmax.sdk.AudioFrame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque

/** 将解码后的 PCM 样本整理为 RTC 所需的连续 10 ms 数据包。 */
internal class PCMFramePacketizer(
    private val totalSamples: Int,
) {
    private val pendingSamples = ArrayDeque<Short>()
    private var writtenSampleCount = 0
    private var emittedSampleCount = 0

    init {
        require(totalSamples >= AudioFrame.samplesPerFrame)
        require(totalSamples % AudioFrame.samplesPerFrame == 0)
    }

    fun append(samples: ShortArray, atSample: Int) {
        if (writtenSampleCount >= totalSamples || samples.isEmpty()) return
        val normalizedStart = atSample.coerceAtLeast(0)
        while (writtenSampleCount < normalizedStart && writtenSampleCount < totalSamples) {
            pendingSamples.addLast(0)
            writtenSampleCount += 1
        }
        val skippedSamples = (writtenSampleCount - normalizedStart).coerceAtLeast(0)
        var index = skippedSamples
        while (index < samples.size && writtenSampleCount < totalSamples) {
            pendingSamples.addLast(samples[index])
            writtenSampleCount += 1
            index += 1
        }
    }

    fun finishWithSilence() {
        while (writtenSampleCount < totalSamples) {
            pendingSamples.addLast(0)
            writtenSampleCount += 1
        }
    }

    fun nextFrame(): Packet? {
        if (pendingSamples.size < AudioFrame.samplesPerFrame ||
            emittedSampleCount >= totalSamples
        ) {
            return null
        }
        val bytes = ByteBuffer.allocate(AudioFrame.samplesPerFrame * Short.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
        repeat(AudioFrame.samplesPerFrame) {
            bytes.putShort(pendingSamples.removeFirst())
        }
        return Packet(
            data = bytes.array(),
            sampleOffset = emittedSampleCount,
        ).also {
            emittedSampleCount += AudioFrame.samplesPerFrame
        }
    }

    internal data class Packet(
        val data: ByteArray,
        val sampleOffset: Int,
    )
}
