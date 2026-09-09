package ai.xmax.sdk.service.media

import ai.xmax.sdk.RealtimeModel
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaServiceTest {
    private val service = MediaService()

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

        sources.forEach { source ->
            val size = service.resolveModelInputSize(source)
            val pixels = size.width.toLong() * size.height.toLong()
            assertTrue(pixels >= service.model.minimumInputPixels)
            assertTrue(pixels <= service.model.maximumInputPixels)
            assertEquals(0, size.width % service.model.inputSizeAlignment)
            assertEquals(0, size.height % service.model.inputSizeAlignment)
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
