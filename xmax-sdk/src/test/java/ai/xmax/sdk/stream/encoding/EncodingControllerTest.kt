package ai.xmax.sdk.stream.encoding

import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import ai.xmax.sdk.foundation.rtc.VideoEncodingConfiguration
import ai.xmax.sdk.stream.room.RtcManagingStub
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

public class EncodingControllerTest {
    @Test
    public fun `configure validates and applies adaptive encoding defaults`() {
        val rtcManager = RtcManagingStub()
        val controller = EncodingController(rtcManager)

        controller.configure(RealtimeVideoFormat(1_024, 768, 30))

        assertEquals(
            listOf(
                VideoEncodingConfiguration(
                    width = 1_024,
                    height = 768,
                    frameRate = 30,
                ),
            ),
            rtcManager.encodingConfigurations,
        )
    }

    @Test
    public fun `configure rejects invalid format before calling RTC`() {
        val rtcManager = RtcManagingStub()
        val controller = EncodingController(rtcManager)

        val error = expectXmaxError {
            controller.configure(RealtimeVideoFormat(1_023, 768, 30))
        }

        assertEquals(XmaxErrorCode.INVALID_CONFIGURATION, error.code)
        assertEquals(
            "Realtime video width and height must be positive even numbers, " +
                "and fps must be greater than zero",
            error.message,
        )
        assertTrue(rtcManager.encodingConfigurations.isEmpty())
    }

    @Test
    public fun `configure preserves RTC error`() {
        val expected = XmaxError(
            code = XmaxErrorCode.RTC_ERROR,
            message = "Failed to configure RTC encoding",
        )
        val controller = EncodingController(
            RtcManagingStub(encodingError = expected),
        )

        val error = expectXmaxError {
            controller.configure(RealtimeVideoFormat(1_024, 768, 30))
        }

        assertTrue(error === expected)
    }

    private fun expectXmaxError(block: () -> Unit): XmaxError = try {
        block()
        throw AssertionError("Expected XmaxError")
    } catch (error: XmaxError) {
        error
    }
}
