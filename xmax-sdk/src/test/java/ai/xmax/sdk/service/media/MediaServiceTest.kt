package ai.xmax.sdk.service.media

import ai.xmax.sdk.RealtimeModel
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaServiceTest {
    private val service = MediaService()

    @Test
    fun `bucket resolutions are preserved in both orientations`() {
        val bucketService = MediaService(RealtimeModel.X2_0_PRO)

        listOf(IntSize(1_024, 1_920), IntSize(1_920, 1_024)).forEach { size ->
            assertEquals(size, bucketService.resolveModelInputSize(size))
        }
    }

    @Test
    fun `bucket model rejects unsupported resolutions without resizing`() {
        val bucketService = MediaService(RealtimeModel.X2_0_PRO)
        val unsupportedSizes = listOf(
            IntSize(832, 1_472),
            IntSize(1_920, 1_080),
            IntSize(512, 960),
            IntSize(2_048, 3_840),
            IntSize(1_120, 1_120),
            IntSize(0, 1_920),
        )

        unsupportedSizes.forEach { size ->
            val error = assertThrows(XmaxError::class.java) {
                bucketService.resolveModelInputSize(size)
            }

            assertEquals(XmaxErrorCode.INVALID_CONFIGURATION, error.code)
        }
    }

    @Test
    fun `keeps an aligned size within model bounds`() {
        assertEquals(IntSize(704, 1280), service.resolveModelInputSize(IntSize(704, 1280)))
    }

    @Test
    fun `preserves the model default camera size`() {
        val format = RealtimeModel.X2_0.defaultCameraVideoFormat

        assertEquals(RealtimeModel.X2_0, service.model)
        assertEquals(
            IntSize(format.width, format.height),
            service.resolveModelInputSize(IntSize(format.width, format.height)),
        )
    }

    @Test
    fun `aligned results stay within model pixel bounds`() {
        val sources = listOf(
            IntSize(799, 751),
            IntSize(1_130, 1_130),
            IntSize(1_445, 1_445),
            IntSize(1_024, 1_920),
            IntSize(3_840, 2_160),
            IntSize(1, 100_000),
            IntSize(100_000, 1),
        )

        RealtimeModel.entries.filter { it.resolutionBuckets.isEmpty() }.forEach { model ->
            sources.forEach { source ->
                val modelService = MediaService(model)
                val size = modelService.resolveModelInputSize(source)
                val pixels = size.width.toLong() * size.height.toLong()
                assertTrue(pixels >= model.minimumInputPixels)
                assertTrue(pixels <= model.maximumInputPixels)
                assertEquals(0, size.width % model.inputSizeAlignment)
                assertEquals(0, size.height % model.inputSizeAlignment)
            }
        }
    }

    @Test
    fun `scales a small size up using aligned ceiling`() {
        assertEquals(IntSize(800, 800), service.resolveModelInputSize(IntSize(100, 100)))
    }

    @Test
    fun `scales a large size down using aligned floor`() {
        assertEquals(IntSize(1120, 1120), service.resolveModelInputSize(IntSize(2000, 2000)))
    }

    @Test
    fun `matches model resizing examples`() {
        assertEquals(IntSize(896, 672), service.resolveModelInputSize(IntSize(640, 480)))
        assertEquals(IntSize(1504, 832), service.resolveModelInputSize(IntSize(1920, 1080)))
        assertEquals(IntSize(1024, 768), service.resolveModelInputSize(IntSize(1010, 770)))
    }
}
