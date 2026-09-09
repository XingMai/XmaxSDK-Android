package ai.xmax.sdk.stream.encoding

import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.RealtimeVideoEncoderPreference
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import ai.xmax.sdk.foundation.rtc.VideoEncodingConfiguration
import ai.xmax.sdk.stream.room.RtcManagingStub
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

public class EncodingControllerTest {
    @Test
    public fun `configure computes bitrate defaults from size and frame rate`() {
        val rtcManager = RtcManagingStub()
        val controller = EncodingController(rtcManager)

        controller.configure(RealtimeVideoFormat(1_024, 768, 30))

        assertEquals(
            listOf(
                VideoEncodingConfiguration(
                    width = 1_024,
                    height = 768,
                    frameRate = 30,
                    minimumBitrate = 1_516,
                    maximumBitrate = 3_033,
                ),
            ),
            rtcManager.encodingConfigurations,
        )
    }

    @Test
    public fun `configure merges explicit bitrates and encoder preference`() {
        val rtcManager = RtcManagingStub()
        val controller = EncodingController(rtcManager)
        data class Case(
            val minimum: Int?,
            val maximum: Int?,
            val preference: RealtimeVideoEncoderPreference,
            val expectedMinimum: Int,
            val expectedMaximum: Int,
            val expectedPreference: VideoEncodingConfiguration.EncoderPreference,
        )
        val cases = listOf(
            Case(
                minimum = 1_500,
                maximum = 3_000,
                preference = RealtimeVideoEncoderPreference.AUTO,
                expectedMinimum = 1_500,
                expectedMaximum = 3_000,
                expectedPreference = VideoEncodingConfiguration.EncoderPreference.AUTO,
            ),
            Case(
                minimum = 0,
                maximum = 500,
                preference = RealtimeVideoEncoderPreference.MAINTAIN_QUALITY,
                expectedMinimum = 0,
                expectedMaximum = 500,
                expectedPreference = VideoEncodingConfiguration.EncoderPreference.MAINTAIN_QUALITY,
            ),
            Case(
                minimum = null,
                maximum = 4_000,
                preference = RealtimeVideoEncoderPreference.MAINTAIN_FRAMERATE,
                expectedMinimum = 1_805,
                expectedMaximum = 4_000,
                expectedPreference = VideoEncodingConfiguration.EncoderPreference.MAINTAIN_FRAMERATE,
            ),
            Case(
                minimum = 1_500,
                maximum = null,
                preference = RealtimeVideoEncoderPreference.AUTO,
                expectedMinimum = 1_500,
                expectedMaximum = 3_611,
                expectedPreference = VideoEncodingConfiguration.EncoderPreference.AUTO,
            ),
            Case(
                minimum = null,
                maximum = null,
                preference = RealtimeVideoEncoderPreference.MAINTAIN_QUALITY,
                expectedMinimum = 1_805,
                expectedMaximum = 3_611,
                expectedPreference = VideoEncodingConfiguration.EncoderPreference.MAINTAIN_QUALITY,
            ),
        )

        cases.forEach { case ->
            controller.configure(
                RealtimeVideoFormat(
                    width = 832,
                    height = 1_472,
                    fps = 24,
                    minimumBitrate = case.minimum,
                    maximumBitrate = case.maximum,
                    encoderPreference = case.preference,
                ),
            )
            assertEquals(
                VideoEncodingConfiguration(
                    width = 832,
                    height = 1_472,
                    frameRate = 24,
                    minimumBitrate = case.expectedMinimum,
                    maximumBitrate = case.expectedMaximum,
                    encoderPreference = case.expectedPreference,
                ),
                rtcManager.encodingConfigurations.last(),
            )
        }
    }

    @Test
    public fun `configure interpolates and extrapolates upload bitrate`() {
        val rtcManager = RtcManagingStub()
        val controller = EncodingController(rtcManager)
        data class Case(val width: Int, val height: Int, val fps: Int, val minimum: Int, val maximum: Int)
        val cases = listOf(
            Case(1_920, 1_080, 30, 3_150, 6_300),
            Case(1_920, 1_080, 24, 2_722, 5_444),
            Case(832, 1_472, 24, 1_805, 3_611),
            Case(1_472, 832, 24, 1_805, 3_611),
            Case(1_024, 1_920, 30, 3_016, 6_031),
            Case(1_024, 1_920, 24, 2_606, 5_212),
            Case(1_024, 768, 24, 1_310, 2_620),
            Case(1_920, 1_080, 120, 9_560, 13_000),
            Case(3_840, 2_160, 30, 12_600, 25_200),
            Case(120, 120, 30, 77, 154),
            Case(1_920, 1_080, 1, 166, 333),
            Case(2, 2, 1, 1, 2),
        )

        cases.forEach { case ->
            controller.configure(RealtimeVideoFormat(case.width, case.height, case.fps))
            val configuration = rtcManager.encodingConfigurations.last()
            assertEquals(case.minimum, configuration.minimumBitrate)
            assertEquals(case.maximum, configuration.maximumBitrate)
        }
    }

    @Test
    public fun `configure rejects invalid explicit and merged bitrate ranges`() {
        val rtcManager = RtcManagingStub()
        val controller = EncodingController(rtcManager)
        val cases = listOf(
            -1 to null,
            null to -1,
            null to 0,
            3_000 to 1_500,
            4_000 to null,
            null to 1_000,
        )

        cases.forEach { (minimum, maximum) ->
            expectXmaxError {
                controller.configure(
                    RealtimeVideoFormat(
                        width = 832,
                        height = 1_472,
                        fps = 24,
                        minimumBitrate = minimum,
                        maximumBitrate = maximum,
                    ),
                )
            }
        }
        assertTrue(rtcManager.encodingConfigurations.isEmpty())
    }

    @Test
    public fun `computed bitrates remain ordered and monotonic across frame rates`() {
        val rtcManager = RtcManagingStub()
        val controller = EncodingController(rtcManager)

        listOf(120 to 120, 320 to 240, 832 to 1_472, 1_920 to 1_080, 3_840 to 2_160).forEach { (width, height) ->
            var previousMinimum = 0
            var previousMaximum = 0
            listOf(1, 9, 10, 11, 14, 15, 16, 24, 29, 30, 31, 59, 60, 61, 120).forEach { fps ->
                controller.configure(RealtimeVideoFormat(width, height, fps))
                val configuration = rtcManager.encodingConfigurations.last()
                assertTrue(configuration.minimumBitrate > 0)
                assertTrue(configuration.maximumBitrate > configuration.minimumBitrate)
                assertTrue(configuration.minimumBitrate >= previousMinimum)
                assertTrue(configuration.maximumBitrate >= previousMaximum)
                previousMinimum = configuration.minimumBitrate
                previousMaximum = configuration.maximumBitrate
            }
        }
    }

    @Test
    public fun `configure rejects bitrate overflow before calling RTC`() {
        val rtcManager = RtcManagingStub()
        val controller = EncodingController(rtcManager)

        val error = expectXmaxError {
            controller.configure(
                RealtimeVideoFormat(
                    width = Int.MAX_VALUE - 1,
                    height = Int.MAX_VALUE - 1,
                    fps = Int.MAX_VALUE,
                ),
            )
        }

        assertEquals(XmaxErrorCode.INVALID_CONFIGURATION, error.code)
        assertTrue(rtcManager.encodingConfigurations.isEmpty())
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
