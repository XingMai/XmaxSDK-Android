package ai.xmax.sdk.foundation.rtc

import io.agora.rtc2.IRtcEngineEventHandler.LocalVideoStats
import io.agora.rtc2.IRtcEngineEventHandler.RemoteVideoStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RtcQualityConverterTest {
    @Test fun `video statistics use measured native units and keep zero rates`() {
        val local = RtcQualityConverter.uplinkVideoStatistics(LocalVideoStats().apply {
            encodedFrameWidth = 1024
            encodedFrameHeight = 1920
            captureFrameRate = 30
            encoderOutputFrameRate = 27
            sentFrameRate = 24
            sentBitrate = 6293
            txPacketLossRate = 2
        })
        assertEquals(1024, local.width)
        assertEquals(1920, local.height)
        assertEquals(30, local.captureFrameRate)
        assertEquals(27, local.encodeFrameRate)
        assertEquals(24, local.sendFrameRate)
        assertEquals(6293, local.bitrateKbps)
        assertEquals(2, local.packetLossPercent)

        val remote = RtcQualityConverter.downlinkVideoStatistics("bot", RemoteVideoStats().apply {
            width = 1920
            height = 1024
            decoderOutputFrameRate = 26
            rendererOutputFrameRate = 0
            receivedBitrate = 0
            packetLossRate = 0
            e2eDelay = 150
        })
        assertEquals("bot", remote.userId)
        assertEquals(1920, remote.width)
        assertEquals(1024, remote.height)
        assertEquals(26, remote.decodeFrameRate)
        assertEquals(0, remote.renderFrameRate)
        assertEquals(0, remote.bitrateKbps)
        assertEquals(0, remote.packetLossPercent)
        assertEquals(150, remote.endToEndDelayMs)
    }

    @Test fun `invalid native statistics are unavailable rather than plausible numbers`() {
        val local = RtcQualityConverter.uplinkVideoStatistics(LocalVideoStats().apply {
            encodedFrameWidth = 0
            encodedFrameHeight = -1
            captureFrameRate = -1
            encoderOutputFrameRate = -1
            sentFrameRate = -1
            sentBitrate = -1
            txPacketLossRate = 101
        })
        assertNull(local.width)
        assertNull(local.height)
        assertNull(local.captureFrameRate)
        assertNull(local.encodeFrameRate)
        assertNull(local.sendFrameRate)
        assertNull(local.bitrateKbps)
        assertNull(local.packetLossPercent)
        val remote = RtcQualityConverter.downlinkVideoStatistics("bot", RemoteVideoStats().apply {
            decoderOutputFrameRate = -1
            rendererOutputFrameRate = -1
            packetLossRate = -1
            e2eDelay = -1
        })
        assertNull(remote.decodeFrameRate)
        assertNull(remote.renderFrameRate)
        assertNull(remote.packetLossPercent)
        assertNull(remote.endToEndDelayMs)
    }

    @Test fun `Agora quality levels are mapped without treating detecting as healthy`() {
        listOf(RtcQualityLevel.UNKNOWN, RtcQualityLevel.EXCELLENT, RtcQualityLevel.GOOD,
            RtcQualityLevel.POOR, RtcQualityLevel.BAD, RtcQualityLevel.VERY_BAD,
            RtcQualityLevel.DOWN).forEachIndexed { value, expected ->
            assertEquals(expected, RtcQualityConverter.convertLevel(value))
        }
        assertEquals(RtcQualityLevel.UNKNOWN, RtcQualityConverter.convertLevel(8))
        assertEquals(RtcQualityLevel.UNKNOWN, RtcQualityConverter.convertLevel(99))
    }
}
