//
//  MediaPlaybackTimeline.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.media.video

import ai.xmax.sdk.AudioFrame
import android.os.SystemClock
import kotlin.math.ceil

/**
 * 为同一文件的音频和视频提供统一的循环播放时钟。
 *
 * @param mediaDurationUs 一轮媒体播放的时长，单位为微秒。
 * @param nowNanoseconds 当前单调时钟时间，单位为纳秒。
 */
internal class MediaPlaybackTimeline(
    mediaDurationUs: Long,
    nowNanoseconds: Long = SystemClock.elapsedRealtimeNanos(),
) {
    /**
     * 单轮媒体的采样数量、持续时间及播放起点。
     */
    val cycleSampleCount: Int
    val cycleDurationNanoseconds: Long
    private val anchorNanoseconds: Long

    init {
        require(mediaDurationUs > 0L) { "Media duration must be positive" }
        val rawSampleCount = ceil(
            mediaDurationUs.toDouble() * AudioFrame.sampleRate / MICROSECONDS_PER_SECOND,
        ).toLong()
        val packetCount = ceil(
            rawSampleCount.toDouble() / AudioFrame.samplesPerFrame,
        ).toLong()
        val alignedSamples = maxOf(AudioFrame.samplesPerFrame.toLong(), packetCount *
            AudioFrame.samplesPerFrame)
        require(alignedSamples <= Int.MAX_VALUE) { "Media duration is too long" }
        cycleSampleCount = alignedSamples.toInt()
        cycleDurationNanoseconds = alignedSamples * NANOSECONDS_PER_SECOND /
            AudioFrame.sampleRate
        anchorNanoseconds = nowNanoseconds + START_DELAY_NANOSECONDS
    }

    /**
     * 计算指定循环内媒体时间点对应的播放时刻。
     *
     * @param loopIndex 从零开始的媒体循环次数。
     * @param mediaOffsetUs 本轮媒体内的时间偏移，单位为微秒。
     * @return 对应帧应提交时的单调时钟纳秒数。
     */
    fun target(loopIndex: Int, mediaOffsetUs: Long): Long =
        anchorNanoseconds + cycleDurationNanoseconds * loopIndex +
            mediaOffsetUs.coerceAtLeast(0L) * NANOSECONDS_PER_MICROSECOND

    /**
     * 计算指定循环内媒体时间点对应的播放时刻。
     *
     * @param loopIndex 从零开始的媒体循环次数。
     * @param sampleOffset 音频包在媒体时间线中的起始采样位置。
     * @return 对应帧应提交时的单调时钟纳秒数。
     */
    fun target(loopIndex: Int, sampleOffset: Int): Long = target(
        loopIndex,
        sampleOffset.toLong() * MICROSECONDS_PER_SECOND / AudioFrame.sampleRate,
    )

    /**
     * 提供 MediaPlaybackTimeline 使用的构造入口、共享配置或常量。
     */
    private companion object {
        /**
         * 共享常量、默认配置与工厂入口。
         */
        const val START_DELAY_NANOSECONDS = 100_000_000L
        const val NANOSECONDS_PER_MICROSECOND = 1_000L
        const val NANOSECONDS_PER_SECOND = 1_000_000_000L
        const val MICROSECONDS_PER_SECOND = 1_000_000L
    }
}
