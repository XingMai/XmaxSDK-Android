package ai.xmax.sdk.foundation.rtc

import ai.xmax.sdk.VideoFormat
import ai.xmax.sdk.VideoFrame
import ai.xmax.sdk.VideoFramePlane
import ai.xmax.sdk.VideoPixelFormat
import ai.xmax.sdk.VideoRotation
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import com.ss.bytertc.engine.VideoEncoderConfig
import com.ss.bytertc.engine.data.VideoBufferType
import com.ss.bytertc.engine.data.VideoPixelFormat as VolcVideoPixelFormat
import com.ss.bytertc.engine.data.VideoRotation as VolcVideoRotation
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

public class RtcVideoConverterTest {
    @Test
    public fun `video converter maps neutral enums`() {
        assertEquals(
            VolcVideoPixelFormat.I420,
            RtcVideoConverter.convertPixelFormat(VideoPixelFormat.I420),
        )
        assertEquals(
            VolcVideoPixelFormat.NV12,
            RtcVideoConverter.convertPixelFormat(VideoPixelFormat.NV12),
        )
        assertEquals(
            VolcVideoPixelFormat.RGBA,
            RtcVideoConverter.convertPixelFormat(VideoPixelFormat.BGRA),
        )
        assertEquals(
            VolcVideoRotation.VIDEO_ROTATION_270,
            RtcVideoConverter.convertRotation(VideoRotation.ROTATION_270),
        )
    }

    @Test
    public fun `video converter selects plane ranges and metadata`() {
        val frame = VideoFrame(
            format = VideoFormat(2, 2, VideoPixelFormat.NV12),
            timestampUs = 12_345,
            planes = listOf(
                VideoFramePlane(
                    data = byteArrayOf(99, 1, 2, 3, 4, 98),
                    stride = 2,
                    byteOffset = 1,
                    byteLength = 4,
                ),
                VideoFramePlane(
                    data = byteArrayOf(97, 5, 6, 96),
                    stride = 2,
                    byteOffset = 1,
                    byteLength = 2,
                ),
            ),
            rotation = VideoRotation.ROTATION_90,
        )

        val converted = RtcVideoConverter.convertFrame(
            frame,
            seiData = "sei".encodeToByteArray(),
        )

        assertEquals(VideoBufferType.RAW_MEMORY, converted.bufferType)
        assertEquals(VolcVideoPixelFormat.NV12, converted.pixelFormat)
        assertEquals(2, converted.width)
        assertEquals(2, converted.height)
        assertEquals(2, converted.numberOfPlanes)
        assertEquals(2, converted.planeStride[0])
        assertEquals(2, converted.planeStride[1])
        assertEquals(12_345, converted.timestampUs)
        assertEquals(VolcVideoRotation.VIDEO_ROTATION_90, converted.rotation)
        assertTrue(converted.planeData.all(ByteBuffer::isDirect))
        assertTrue(converted.seiData.isDirect)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), converted.planeData[0].bytes())
        assertArrayEquals(byteArrayOf(5, 6), converted.planeData[1].bytes())
        assertArrayEquals("sei".encodeToByteArray(), converted.seiData.bytes())
    }

    @Test
    public fun `video converter normalizes BGRA and ARGB to RGBA`() {
        val bgra = RtcVideoConverter.convertFrame(
            VideoFrame(
                format = VideoFormat(1, 1, VideoPixelFormat.BGRA),
                timestampUs = 0,
                planes = listOf(
                    VideoFramePlane(byteArrayOf(1, 2, 3, 4), stride = 4),
                ),
            ),
        )
        val argb = RtcVideoConverter.convertFrame(
            VideoFrame(
                format = VideoFormat(1, 1, VideoPixelFormat.ARGB),
                timestampUs = 0,
                planes = listOf(
                    VideoFramePlane(byteArrayOf(4, 3, 2, 1), stride = 4),
                ),
            ),
        )

        assertArrayEquals(byteArrayOf(3, 2, 1, 4), bgra.planeData[0].bytes())
        assertArrayEquals(byteArrayOf(3, 2, 1, 4), argb.planeData[0].bytes())
    }

    @Test
    public fun `video converter rejects wrong plane count`() {
        val frame = VideoFrame(
            format = VideoFormat(2, 2, VideoPixelFormat.I420),
            timestampUs = 0,
            planes = listOf(VideoFramePlane(ByteArray(4), stride = 2)),
        )

        val error = assertThrows(XmaxError::class.java) {
            RtcVideoConverter.convertFrame(frame)
        }

        assertEquals(XmaxErrorCode.INVALID_CONFIGURATION, error.code)
        assertEquals("Video frame requires 3 data planes", error.message)
    }

    @Test
    public fun `encoder configuration matches RTC fields and preference`() {
        val converted = RtcVideoConverter.makeEncoderConfiguration(
            VideoEncodingConfiguration(
                width = 1_024,
                height = 768,
                frameRate = 30,
                minimumBitrate = 100,
                maximumBitrate = 2_000,
            ),
        )

        assertEquals(1_024, converted.width)
        assertEquals(768, converted.height)
        assertEquals(30, converted.frameRate)
        assertEquals(100, converted.minBitrate)
        assertEquals(2_000, converted.maxBitrate)
        assertEquals(
            VideoEncoderConfig.EncoderPreference.MAINTAIN_FRAMERATE,
            converted.encodePreference,
        )
    }

    private fun ByteBuffer.bytes(): ByteArray = duplicate().let { buffer ->
        ByteArray(buffer.remaining()).also(buffer::get)
    }
}
