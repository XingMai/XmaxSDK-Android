package ai.xmax.sdk.media.video

import ai.xmax.sdk.VideoRotation
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
public class NativeYuv420ConverterTest {
    @Test
    public fun nativeConverterRotatesFlexibleChromaIntoPortraitI420() {
        val source = Yuv420VideoFrameConverter.Source(
            width = 4,
            height = 2,
            cropLeft = 0,
            cropTop = 0,
            cropWidth = 4,
            cropHeight = 2,
            planes = listOf(
                directPlane(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), 4, 1),
                directPlane(byteArrayOf(10, 99, 11, 0), 4, 2),
                directPlane(byteArrayOf(20, 88, 21, 0), 4, 2),
            ),
        )
        val y = ByteArray(8)
        val u = ByteArray(2)
        val v = ByteArray(2)

        assertTrue(
            NativeYuv420Converter.convert(
                source = source,
                outputWidth = 2,
                outputHeight = 4,
                rotation = VideoRotation.ROTATION_90,
                y = y,
                u = u,
                v = v,
            ),
        )
        assertArrayEquals(byteArrayOf(5, 1, 6, 2, 7, 3, 8, 4), y)
        assertArrayEquals(byteArrayOf(10, 11), u)
        assertArrayEquals(byteArrayOf(20, 21), v)
    }

    @Test
    public fun nativePreviewConverterProducesOpaqueBlackAndWhitePixels() {
        val pixels = IntArray(2)

        assertTrue(
            NativeYuv420Converter.convertI420ToArgb(
                y = byteArrayOf(16, 235.toByte()),
                yStride = 2,
                u = byteArrayOf(128.toByte()),
                uStride = 1,
                v = byteArrayOf(128.toByte()),
                vStride = 1,
                width = 2,
                height = 1,
                pixels = pixels,
            ),
        )
        assertEquals(0xFF000000.toInt(), pixels[0])
        assertEquals(0xFFFFFFFF.toInt(), pixels[1])
    }

    @Test
    public fun nativeCropScaleAndRotationMatchesKotlinFallback() {
        val width = 8
        val height = 6
        val y = ByteArray(width * height) { it.toByte() }
        val u = ByteArray(width * height / 2) { (it + 64).toByte() }
        val v = ByteArray(width * height / 2) { (it + 96).toByte() }
        val nativeSource = source(width, height, y, u, v, direct = true)
        val fallbackSource = source(width, height, y, u, v, direct = false)

        VideoRotation.entries.forEach { rotation ->
            val nativeFrame = Yuv420VideoFrameConverter.convert(
                source = nativeSource,
                outputWidth = 4,
                outputHeight = 6,
                rotation = rotation,
                timestampUs = 0,
            )
            val fallbackFrame = Yuv420VideoFrameConverter.convert(
                source = fallbackSource,
                outputWidth = 4,
                outputHeight = 6,
                rotation = rotation,
                timestampUs = 0,
            )

            nativeFrame.planes.zip(fallbackFrame.planes).forEach { (native, fallback) ->
                assertArrayEquals(fallback.data, native.data)
            }
        }
    }

    private fun directPlane(
        bytes: ByteArray,
        rowStride: Int,
        pixelStride: Int,
    ): Yuv420VideoFrameConverter.Plane = Yuv420VideoFrameConverter.Plane(
        buffer = ByteBuffer.allocateDirect(bytes.size).apply {
            put(bytes)
            flip()
        },
        rowStride = rowStride,
        pixelStride = pixelStride,
    )

    private fun source(
        width: Int,
        height: Int,
        y: ByteArray,
        u: ByteArray,
        v: ByteArray,
        direct: Boolean,
    ): Yuv420VideoFrameConverter.Source = Yuv420VideoFrameConverter.Source(
        width = width,
        height = height,
        cropLeft = 2,
        cropTop = 0,
        cropWidth = 6,
        cropHeight = 6,
        planes = listOf(
            plane(y, width, 1, direct),
            plane(u, width, 2, direct),
            plane(v, width, 2, direct),
        ),
    )

    private fun plane(
        bytes: ByteArray,
        rowStride: Int,
        pixelStride: Int,
        direct: Boolean,
    ): Yuv420VideoFrameConverter.Plane = if (direct) {
        directPlane(bytes, rowStride, pixelStride)
    } else {
        Yuv420VideoFrameConverter.Plane(
            buffer = ByteBuffer.wrap(bytes),
            rowStride = rowStride,
            pixelStride = pixelStride,
        )
    }

}
