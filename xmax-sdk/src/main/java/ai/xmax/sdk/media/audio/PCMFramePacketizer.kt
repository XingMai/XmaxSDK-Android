//
//  PCMFramePacketizer.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.media.audio

import ai.xmax.sdk.AudioFrame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque

/**
 * 将解码后的 PCM 样本整理为 RTC 所需的连续 10 ms 数据包。
 *
 * @param totalSamples 需要处理的总采样数。
 */
internal class PCMFramePacketizer(
    private val totalSamples: Int,
) {
    /**
     * 音频采样缓冲与输入输出进度。
     */
    private val pendingSamples = ArrayDeque<Short>()
    private var writtenSampleCount = 0
    private var emittedSampleCount = 0

    init {
        require(totalSamples >= AudioFrame.samplesPerFrame)
        require(totalSamples % AudioFrame.samplesPerFrame == 0)
    }

    /**
     * 按媒体采样位置缓冲 PCM16 数据并组成固定长度音频包。
     *
     * @param samples 待分包的 PCM16 音频采样。
     * @param atSample 该批数据在媒体时间线中的起始采样位置。
     */
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

    /**
     * 用静音补齐尾部不足一帧的 PCM 数据。
     */
    fun finishWithSilence() {
        while (writtenSampleCount < totalSamples) {
            pendingSamples.addLast(0)
            writtenSampleCount += 1
        }
    }

    /**
     * 取出下一份完整的 PCM 音频包，数据不足时返回 null。
     *
     * @return 下一份完整音频包；数据不足时为 null。
     */
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

    /**
     * 一份可提交的 PCM 音频数据及其时间位置。
     *
     * @param data 待读取、传输或持有的二进制数据。
     * @param sampleOffset 音频包在媒体时间线中的起始采样位置。
     */
    internal data class Packet(
        val data: ByteArray,
        val sampleOffset: Int,
    )
}
