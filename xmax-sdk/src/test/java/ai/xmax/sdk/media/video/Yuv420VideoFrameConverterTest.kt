package ai.xmax.sdk.media.video

import ai.xmax.sdk.VideoPixelFormat
import ai.xmax.sdk.VideoRotation
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

public class Yuv420VideoFrameConverterTest {
    @Test
    public fun `converter preserves identity I420 planes`() {
        val frame = Yuv420VideoFrameConverter.convert(
            source = source(
                width = 4,
                height = 2,
                y = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8),
                u = byteArrayOf(10, 11),
                v = byteArrayOf(20, 21),
            ),
            outputWidth = 4,
            outputHeight = 2,
            rotation = VideoRotation.ROTATION_0,
            timestampUs = 12_345,
        )

        assertEquals(VideoPixelFormat.I420, frame.format.pixelFormat)
        assertEquals(4, frame.format.width)
        assertEquals(2, frame.format.height)
        assertEquals(12_345, frame.timestampUs)
        assertEquals(listOf(4, 2, 2), frame.planes.map { it.stride })
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), frame.planes[0].data)
        assertArrayEquals(byteArrayOf(10, 11), frame.planes[1].data)
        assertArrayEquals(byteArrayOf(20, 21), frame.planes[2].data)
    }

    @Test
    public fun `converter rotates flexible chroma planes into portrait I420`() {
        val frame = Yuv420VideoFrameConverter.convert(
            source = source(
                width = 4,
                height = 2,
                y = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8),
                u = byteArrayOf(10, 99, 11),
                v = byteArrayOf(20, 88, 21),
                chromaRowStride = 4,
                chromaPixelStride = 2,
            ),
            outputWidth = 2,
            outputHeight = 4,
            rotation = VideoRotation.ROTATION_90,
            timestampUs = 0,
        )

        assertArrayEquals(byteArrayOf(5, 1, 6, 2, 7, 3, 8, 4), frame.planes[0].data)
        assertArrayEquals(byteArrayOf(10, 11), frame.planes[1].data)
        assertArrayEquals(byteArrayOf(20, 21), frame.planes[2].data)
    }

    private fun source(
        width: Int,
        height: Int,
        y: ByteArray,
        u: ByteArray,
        v: ByteArray,
        chromaRowStride: Int = width / 2,
        chromaPixelStride: Int = 1,
    ): Yuv420VideoFrameConverter.Source = Yuv420VideoFrameConverter.Source(
        width = width,
        height = height,
        cropLeft = 0,
        cropTop = 0,
        cropWidth = width,
        cropHeight = height,
        planes = listOf(
            Yuv420VideoFrameConverter.Plane(
                buffer = ByteBuffer.wrap(y),
                rowStride = width,
                pixelStride = 1,
            ),
            Yuv420VideoFrameConverter.Plane(
                buffer = ByteBuffer.wrap(u),
                rowStride = chromaRowStride,
                pixelStride = chromaPixelStride,
            ),
            Yuv420VideoFrameConverter.Plane(
                buffer = ByteBuffer.wrap(v),
                rowStride = chromaRowStride,
                pixelStride = chromaPixelStride,
            ),
        ),
    )
}
