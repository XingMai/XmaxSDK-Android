package ai.xmax.sdk.service.media

import ai.xmax.sdk.RealtimeModel
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.*
import org.junit.Test

class MediaServiceTest {
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
