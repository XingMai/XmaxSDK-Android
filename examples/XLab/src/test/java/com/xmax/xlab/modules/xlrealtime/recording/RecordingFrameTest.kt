package com.xmax.xlab.modules.xlrealtime.recording

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class RecordingFrameTest {
    @Test fun `timeline starts at zero preserves gaps and repairs repeated or backward timestamps`() {
        val timeline = RecordingTimeline()
        assertEquals(0L, timeline.next(1_000_000, null))
        assertEquals(40_000L, timeline.next(1_040_000, 40_000))
        assertEquals(200_000L, timeline.next(1_200_000, 40_000))
        assertEquals(240_000L, timeline.next(1_200_000, 40_000))
        assertEquals(280_000L, timeline.next(100, 40_000))
        assertEquals(320_000L, timeline.endTimeUs)
    }

    @Test fun `single frame has a nonzero duration and invalid durations use default`() {
        val timeline = RecordingTimeline()
        timeline.next(42, -10)
        assertEquals(RecordingTimeline.DEFAULT_DURATION_US, timeline.endTimeUs)
    }

    @Test fun `plane copy respects input positions row padding and destination crop offsets`() {
        val input = ByteBuffer.wrap(byteArrayOf(99, 1, 2, 3, 4)).apply { position(1) }.asReadOnlyBuffer()
        val output = ByteBuffer.allocate(12).apply { position(1) }
        copyI420Plane(input, output, 2, 2, rowStride = 5, pixelStride = 1, offset = 1)
        assertEquals(1, input.position())
        assertEquals(1, output.position())
        assertEquals(1, output.get(2).toInt())
        assertEquals(2, output.get(3).toInt())
        assertEquals(3, output.get(7).toInt())
        assertEquals(4, output.get(8).toInt())
        assertEquals(0, output.get(4).toInt())
    }

    @Test fun `interleaved UV planes share memory without overwriting each other`() {
        val chroma = ByteBuffer.allocate(10)
        val u = chroma.duplicate()
        val v = chroma.duplicate().apply { position(1) }
        copyI420Plane(ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4)), u, 2, 2, 6, 2)
        copyI420Plane(ByteBuffer.wrap(byteArrayOf(5, 6, 7, 8)), v, 2, 2, 6, 2)
        assertArrayEquals(byteArrayOf(1, 5, 2, 6, 0, 0, 3, 7, 4, 8), chroma.array())
    }

    @Test fun `truncated buffers and odd dimensions fail before encoding`() {
        assertTrue(runCatching { copyI420Plane(ByteBuffer.allocate(4), ByteBuffer.allocate(3), 2, 2, 2, 1) }.isFailure)
        assertTrue(runCatching { copyI420Plane(ByteBuffer.allocate(3), ByteBuffer.allocate(4), 2, 2, 2, 1) }.isFailure)
        assertTrue(runCatching { RecordingFormat(3, 4, 0) }.isFailure)
        assertTrue(runCatching { RecordingFormat(4, 4, 45) }.isFailure)
    }
}
