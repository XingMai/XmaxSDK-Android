package ai.xmax.sdk.foundation.rtc

import ai.xmax.sdk.DownlinkVideoStatistics
import ai.xmax.sdk.UplinkVideoStatistics
import ai.xmax.sdk.XmaxEnvironment
import ai.xmax.sdk.XmaxLogger
import ai.xmax.sdk.XmaxLoggerOption
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class RtcStatsLoggerTest {
    @After
    fun tearDown() {
        XmaxLogger.configure(XmaxLoggerOption.none)
    }

    @Test
    fun `uplink log preserves each standard metric in Chinese`() {
        XmaxLogger.configure(XmaxLoggerOption.none, XmaxEnvironment.CHINA)
        val statistics = UplinkVideoStatistics(
            width = 1024,
            height = 1920,
            captureFrameRate = 30,
            encodeFrameRate = 27,
            sendFrameRate = 24,
            bitrateKbps = 6293,
            packetLossPercent = 2,
        )

        assertEquals(
            "视频上行统计 (Video Uplink Statistics)\n" +
                "├─ 分辨率：1024 × 1920\n" +
                "├─ 发送码率：6293 kbps\n" +
                "├─ 采集帧率：30 fps\n" +
                "├─ 编码帧率：27 fps\n" +
                "├─ 发送帧率：24 fps\n" +
                "└─ 视频丢包率：2 %",
            RtcStatsLogger.uplinkVideoStatisticsMessage(statistics),
        )
    }

    @Test
    fun `downlink log distinguishes unavailable and zero metrics in English`() {
        XmaxLogger.configure(XmaxLoggerOption.none, XmaxEnvironment.GLOBAL)
        val statistics = DownlinkVideoStatistics(
            userId = "bot",
            width = 1920,
            decodeFrameRate = 26,
            renderFrameRate = 0,
            bitrateKbps = 0,
            packetLossPercent = 0,
        )

        assertEquals(
            "视频下行统计 (Video Downlink Statistics)\n" +
                "├─ Resolution: —\n" +
                "├─ Receive Bitrate: 0 kbps\n" +
                "├─ Decode Frame Rate: 26 fps\n" +
                "├─ Render Frame Rate: 0 fps\n" +
                "├─ Video Packet Loss: 0 %\n" +
                "└─ End-to-End Delay: —",
            RtcStatsLogger.downlinkVideoStatisticsMessage(statistics),
        )
    }

    @Test
    fun `missing uplink metrics are not logged as measurements`() {
        XmaxLogger.configure(XmaxLoggerOption.none, XmaxEnvironment.GLOBAL)

        assertEquals(
            "视频上行统计 (Video Uplink Statistics)\n" +
                "├─ Resolution: —\n" +
                "├─ Send Bitrate: —\n" +
                "├─ Capture Frame Rate: —\n" +
                "├─ Encode Frame Rate: —\n" +
                "├─ Send Frame Rate: —\n" +
                "└─ Video Packet Loss: —",
            RtcStatsLogger.uplinkVideoStatisticsMessage(UplinkVideoStatistics()),
        )
    }
}
