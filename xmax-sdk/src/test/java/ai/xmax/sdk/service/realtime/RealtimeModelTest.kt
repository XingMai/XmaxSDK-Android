package ai.xmax.sdk

import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

public class RealtimeModelTest {
    @Test
    public fun `only CN and US Agora models are exposed with matching endpoints`() {
        assertEquals(listOf("x2.1.1-preview-cn", "x2.1.1-preview-us"), RealtimeModel.entries.map { it.id })
        assertEquals(RealtimeModel.X2_1_1_PREVIEW_CN, RealtimeConfiguration().model)
        assertEquals("https://cloud.xmax.22duck.cn/open/api/v1", RealtimeModel.X2_1_1_PREVIEW_CN.baseUrl)
        assertEquals("https://api.xmax.ai/open/api/v1", RealtimeModel.X2_1_1_PREVIEW_US.baseUrl)
        RealtimeModel.entries.forEach { model ->
            assertEquals(model.id.replaceFirst("x", "X"), model.displayName)
            assertEquals(listOf(IntSize(1024, 1920), IntSize(1920, 1024)), model.resolutionBuckets)
            assertEquals(RealtimeVideoFormat(1024, 1920, 30), model.defaultCameraVideoFormat)
            assertEquals(600_000, model.minimumInputPixels)
            assertEquals(2_100_000, model.maximumInputPixels)
            assertEquals(32, model.inputSizeAlignment)
        }
    }

    @Test
    public fun `realtime context normalizes prompt and optional reference path`() {
        val context = RealtimeContext(
            prompt = "  replace the character  ",
            referencePath = "   ",
        )

        assertEquals("replace the character", context.prompt)
        assertNull(context.referencePath)
        assertEquals(context, context.copy())
    }

    @Test
    public fun `video format validates positive even dimensions and fps`() {
        RealtimeVideoFormat(width = 704, height = 1_280, fps = 24).validate()

        val error = assertThrows(XmaxError::class.java) {
            RealtimeVideoFormat(width = 703, height = 1_280, fps = 24).validate()
        }

        assertEquals(XmaxErrorCode.INVALID_CONFIGURATION, error.code)
    }

    @Test
    public fun `video format validates explicit bitrate ranges`() {
        RealtimeVideoFormat(
            width = 832,
            height = 1_472,
            fps = 24,
            minimumBitrate = 1_500,
            maximumBitrate = 3_000,
            encoderPreference = RealtimeVideoEncoderPreference.MAINTAIN_FRAMERATE,
        ).validate()

        listOf(
            -1 to null,
            null to -1,
            null to 0,
            3_000 to 1_500,
        ).forEach { (minimum, maximum) ->
            val error = assertThrows(XmaxError::class.java) {
                RealtimeVideoFormat(
                    width = 832,
                    height = 1_472,
                    fps = 24,
                    minimumBitrate = minimum,
                    maximumBitrate = maximum,
                ).validate()
            }
            assertEquals(XmaxErrorCode.INVALID_CONFIGURATION, error.code)
        }
    }

    @Test
    public fun `realtime state and quality models retain values`() {
        val state = RealtimeState(
            connectionState = RealtimeConnectionState.GENERATING,
            sessionId = "session-1",
            taskId = "task-1",
        )
        val quality = RealtimeNetworkQuality(
            uplink = RealtimeNetworkQualityLevel.EXCELLENT,
            downlink = RealtimeNetworkQualityLevel.GOOD,
        )
        val alarm = RealtimePerformanceAlarm(
            status = RealtimePerformanceStatus.LIMITED,
            suggestedVideoFormat = RealtimeVideoFormat(540, 960, 15),
        )

        assertEquals("Generating", state.connectionState.value)
        assertEquals("session-1", state.sessionId)
        assertEquals("Excellent", quality.uplink.value)
        assertEquals(15, alarm.suggestedVideoFormat?.fps)
    }

    @Test
    public fun `video track updates dynamic metadata atomically`() {
        val initialFormat = RealtimeVideoFormat(704, 1_280, 24)
        val updatedFormat = RealtimeVideoFormat(832, 1_472, 24)
        val track = RealtimeVideoTrack(
            id = "video0",
            videoFormat = initialFormat,
            position = CameraPosition.FRONT,
        )

        track.updateVideoFormat(updatedFormat)
        track.updatePosition(CameraPosition.BACK)
        val stream = RealtimeMediaStream("local", track)

        assertSame(track, stream.videoTrack)
        assertEquals(updatedFormat, track.videoFormat)
        assertEquals(CameraPosition.BACK, track.position)
    }

    @Test
    public fun `error model preserves existing errors and normalizes conversion`() {
        val existing = XmaxError(XmaxErrorCode.TIMEOUT, "timeout")
        assertSame(existing, XmaxError.from(existing))

        val converted = XmaxError.from(IllegalStateException("  failed  "))
        assertEquals(XmaxErrorCode.INTERNAL_ERROR, converted.code)
        assertEquals("failed", converted.message)
    }
}
