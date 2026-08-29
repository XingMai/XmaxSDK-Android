package ai.xmax.sdk.service.media

import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MediaServiceTest {
    private val service = MediaService()

    @Test
    fun `keeps an aligned size within model bounds`() {
        assertEquals(IntSize(704, 1280), service.resolveModelInputSize(IntSize(704, 1280)))
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
    fun `frame interpolation remains disabled before its pipeline is implemented`() {
        assertFalse(service.supportsFrameInterpolation(IntSize(704, 1280)))
    }
}
