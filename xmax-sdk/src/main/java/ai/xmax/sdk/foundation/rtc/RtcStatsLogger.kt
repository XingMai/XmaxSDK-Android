package ai.xmax.sdk.foundation.rtc

import ai.xmax.sdk.XmaxLogger
import ai.xmax.sdk.XmaxLoggerOption
import ai.xmax.sdk.UplinkVideoStatistics
import ai.xmax.sdk.DownlinkVideoStatistics

/**
 * 输出统一的上下行视频统计日志；不可用指标显示为 —。
 */
internal object RtcStatsLogger {
    fun logUplinkVideoStatistics(stats: UplinkVideoStatistics) {
        XmaxLogger.rtc.debug(message = { uplinkVideoStatisticsMessage(stats) }, option = XmaxLoggerOption.performance)
    }

    fun logDownlinkVideoStatistics(stats: DownlinkVideoStatistics) {
        XmaxLogger.rtc.debug(message = { downlinkVideoStatisticsMessage(stats) }, option = XmaxLoggerOption.performance)
    }

    internal fun uplinkVideoStatisticsMessage(stats: UplinkVideoStatistics): String =
        "视频上行统计 (Video Uplink Statistics)\n" +
            "├─ ${label("分辨率：", "Resolution: ")}${resolution(stats.width, stats.height)}\n" +
            "├─ ${label("发送码率：", "Send Bitrate: ")}${metric(stats.bitrateKbps, "kbps")}\n" +
            "├─ ${label("采集帧率：", "Capture Frame Rate: ")}${metric(stats.captureFrameRate, "fps")}\n" +
            "├─ ${label("编码帧率：", "Encode Frame Rate: ")}${metric(stats.encodeFrameRate, "fps")}\n" +
            "├─ ${label("发送帧率：", "Send Frame Rate: ")}${metric(stats.sendFrameRate, "fps")}\n" +
            "└─ ${label("视频丢包率：", "Video Packet Loss: ")}${metric(stats.packetLossPercent, "%")}"

    internal fun downlinkVideoStatisticsMessage(stats: DownlinkVideoStatistics): String =
        "视频下行统计 (Video Downlink Statistics)\n" +
            "├─ ${label("分辨率：", "Resolution: ")}${resolution(stats.width, stats.height)}\n" +
            "├─ ${label("接收码率：", "Receive Bitrate: ")}${metric(stats.bitrateKbps, "kbps")}\n" +
            "├─ ${label("解码帧率：", "Decode Frame Rate: ")}${metric(stats.decodeFrameRate, "fps")}\n" +
            "├─ ${label("渲染帧率：", "Render Frame Rate: ")}${metric(stats.renderFrameRate, "fps")}\n" +
            "├─ ${label("视频丢包率：", "Video Packet Loss: ")}${metric(stats.packetLossPercent, "%")}\n" +
            "└─ ${label("端到端时延：", "End-to-End Delay: ")}${metric(stats.endToEndDelayMs, "ms")}"

    private fun metric(value: Int?, unit: String): String = value?.let { "$it $unit" } ?: "—"

    private fun resolution(width: Int?, height: Int?): String =
        if (width != null && height != null) "$width × $height" else "—"

    private fun label(chinese: String, english: String): String = XmaxLogger.localized(chinese, english)
}
