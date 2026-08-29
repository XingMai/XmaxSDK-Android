package ai.xmax.sdk.media.video

import ai.xmax.sdk.AudioFrame
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaPlaybackTimelineTest {
    @Test
    fun `timeline aligns cycles to complete audio packets`() {
        val timeline = MediaPlaybackTimeline(
            mediaDurationUs = 1_001_000L,
            nowNanoseconds = 1_000L,
        )

        assertEquals(48_480, timeline.cycleSampleCount)
        assertEquals(1_010_000_000L, timeline.cycleDurationNanoseconds)
        assertEquals(
            100_001_000L,
            timeline.target(loopIndex = 0, mediaOffsetUs = 0L),
        )
        assertEquals(
            1_110_001_000L,
            timeline.target(loopIndex = 1, mediaOffsetUs = 0L),
        )
        assertEquals(
            110_001_000L,
            timeline.target(loopIndex = 0, sampleOffset = AudioFrame.samplesPerFrame),
        )
    }
}
