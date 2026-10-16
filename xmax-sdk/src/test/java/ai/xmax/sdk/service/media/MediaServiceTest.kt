package ai.xmax.sdk.service.media

import ai.xmax.sdk.RealtimeModel
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.*
import org.junit.Test

class MediaServiceTest {
    @Test
    fun `generation canvas selects nearest aspect without changing media input`() {
        RealtimeModel.entries.forEach { model ->
            val service = MediaService(model)
            for (size in listOf(IntSize(1920, 1080), IntSize(1280, 720), IntSize(640, 480), IntSize(3840, 2160))) {
                assertEquals(IntSize(1920, 1024), service.resolveGenerationSize(size))
                assertEquals(IntSize(1024, 1920), service.resolveGenerationSize(IntSize(size.height, size.width)))
            }
            model.resolutionBuckets.forEach { assertEquals(it, service.resolveGenerationSize(it)) }
            assertTrue(service.resolveGenerationSize(IntSize(640, 640)) in model.resolutionBuckets)
            assertThrows(XmaxError::class.java) { service.resolveGenerationSize(IntSize(0, 1080)) }
        }
    }

    @Test fun `both Agora models preserve only the declared buckets`() {
        RealtimeModel.entries.forEach { model ->
            val service = MediaService(model)
            model.resolutionBuckets.forEach { assertEquals(it, service.resolveModelInputSize(it)) }
            val format = model.defaultCameraVideoFormat
            assertEquals(IntSize(format.width, format.height), service.resolveModelInputSize(IntSize(format.width, format.height)))
            listOf(IntSize(832, 1472), IntSize(1920, 1080), IntSize(512, 960),
                IntSize(2048, 3840), IntSize(1120, 1120), IntSize(0, 1920)).forEach { size ->
                val error = assertThrows(XmaxError::class.java) { service.resolveModelInputSize(size) }
                assertEquals(XmaxErrorCode.INVALID_CONFIGURATION, error.code)
            }
        }
    }
}
