package ai.xmax.sdk.media.video

import ai.xmax.sdk.AudioFrame
import android.os.SystemClock
import kotlin.math.ceil

/** 为同一文件的音频和视频提供统一的循环播放时钟。 */
internal class MediaPlaybackTimeline(
    mediaDurationUs: Long,
    nowNanoseconds: Long = SystemClock.elapsedRealtimeNanos(),
) {
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

    fun target(loopIndex: Int, mediaOffsetUs: Long): Long =
        anchorNanoseconds + cycleDurationNanoseconds * loopIndex +
            mediaOffsetUs.coerceAtLeast(0L) * NANOSECONDS_PER_MICROSECOND

    fun target(loopIndex: Int, sampleOffset: Int): Long = target(
        loopIndex,
        sampleOffset.toLong() * MICROSECONDS_PER_SECOND / AudioFrame.sampleRate,
    )

    private companion object {
        const val START_DELAY_NANOSECONDS = 100_000_000L
        const val NANOSECONDS_PER_MICROSECOND = 1_000L
        const val NANOSECONDS_PER_SECOND = 1_000_000_000L
        const val MICROSECONDS_PER_SECOND = 1_000_000L
    }
}
