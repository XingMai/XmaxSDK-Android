package ai.xmax.sdk

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

public class MediaModelTest {
    @Test
    public fun `media enum values match cross-platform contract`() {
        assertEquals(
            listOf("i420", "nv12", "nv21", "rgba", "bgra", "argb"),
            VideoPixelFormat.entries.map(VideoPixelFormat::value),
        )
        assertEquals(
            listOf(0, 90, 180, 270),
            VideoRotation.entries.map(VideoRotation::degrees),
        )
    }

    @Test
    public fun `audio frame uses external audio contract and owns data`() {
        val source = ByteArray(960) { 1 }
        val frame = AudioFrame(source, timestampUs = 10_000)
        source[0] = 2

        assertEquals(48_000, AudioFrame.sampleRate)
        assertEquals(1, AudioFrame.channelCount)
        assertEquals(480, AudioFrame.samplesPerFrame)
        assertEquals(10_000, frame.timestampUs)
        assertEquals(1, frame.data[0].toInt())
    }

    @Test
    public fun `video format rejects non-positive dimensions`() {
        val error = assertThrows(XmaxError::class.java) {
            VideoFormat(width = 0, height = 720, pixelFormat = VideoPixelFormat.NV12)
        }

        assertEquals(XmaxErrorCode.INVALID_CONFIGURATION, error.code)
        assertEquals("Video width and height must be positive integers", error.message)
    }

    @Test
    public fun `video frame plane uses remaining data and owns bytes`() {
        val source = ByteArray(24)
        val plane = VideoFramePlane(source, stride = 8, byteOffset = 4)
        source[4] = 9

        assertEquals(4, plane.byteOffset)
        assertEquals(20, plane.byteLength)
        assertEquals(0, plane.data[4].toInt())
    }

    @Test
    public fun `video frame plane rejects invalid range and stride`() {
        val rangeError = assertThrows(XmaxError::class.java) {
            VideoFramePlane(
                data = ByteArray(8),
                stride = 4,
                byteOffset = 4,
                byteLength = 5,
            )
        }
        assertEquals("Video frame plane range is invalid", rangeError.message)

        val strideError = assertThrows(XmaxError::class.java) {
            VideoFramePlane(data = ByteArray(8), stride = 0)
        }
        assertEquals(
            "Video frame plane stride must be a positive integer",
            strideError.message,
        )
    }

    @Test
    public fun `video frame stores neutral data and updates metadata`() {
        val format = VideoFormat(2, 2, VideoPixelFormat.BGRA)
        val plane = VideoFramePlane(ByteArray(16) { 0x7F }, stride = 8)
        val reuseId = UUID.randomUUID()
        val sourcePlanes = mutableListOf(plane)
        val frame = VideoFrame(
            format = format,
            timestampUs = 33_333,
            planes = sourcePlanes,
            rotation = VideoRotation.ROTATION_90,
            bufferReuseId = reuseId,
        )
        sourcePlanes.clear()

        assertEquals(listOf(plane), frame.planes)
        assertEquals(VideoRotation.ROTATION_90, frame.rotation)
        assertEquals(reuseId, frame.bufferReuseId)

        val updated = frame.updating(66_666, VideoRotation.ROTATION_180)
        assertEquals(format, updated.format)
        assertEquals(66_666, updated.timestampUs)
        assertEquals(VideoRotation.ROTATION_180, updated.rotation)
        assertEquals(reuseId, updated.bufferReuseId)
        assertEquals(frame.planes, updated.planes)
    }

    @Test
    public fun `video frame rejects negative timestamp and empty planes`() {
        val format = VideoFormat(1, 1, VideoPixelFormat.RGBA)
        val plane = VideoFramePlane(ByteArray(4), stride = 4)

        assertThrows(XmaxError::class.java) {
            VideoFrame(format, timestampUs = -1, planes = listOf(plane))
        }
        assertThrows(XmaxError::class.java) {
            VideoFrame(format, timestampUs = 0, planes = emptyList())
        }
    }
}
