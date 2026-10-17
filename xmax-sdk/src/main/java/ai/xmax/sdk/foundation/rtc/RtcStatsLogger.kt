//
//  RtcStatsLogger.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.foundation.rtc

import ai.xmax.sdk.XmaxLogger
import ai.xmax.sdk.XmaxLoggerOption
import ai.xmax.sdk.UplinkVideoStatistics
import ai.xmax.sdk.DownlinkVideoStatistics

/**
 * 输出统一的上下行视频统计日志；不可用指标显示为 —。
 */
internal object RtcStatsLogger {
    /**
     * 按性能日志开关输出上行视频统计。
     *
     * @param stats RTC 视频统计快照。
     */
    fun logUplinkVideoStatistics(stats: UplinkVideoStatistics) {
        XmaxLogger.rtc.debug(message = { uplinkVideoStatisticsMessage(stats) }, option = XmaxLoggerOption.performance)
    }

    /**
     * 按性能日志开关输出下行视频统计。
     *
     * @param stats RTC 视频统计快照。
     */
    fun logDownlinkVideoStatistics(stats: DownlinkVideoStatistics) {
        XmaxLogger.rtc.debug(message = { downlinkVideoStatisticsMessage(stats) }, option = XmaxLoggerOption.performance)
    }

    /**
     * 生成上行视频指标的分行日志文本。
     *
     * @param stats RTC 视频统计快照。
     * @return 上行视频统计的分行文本。
     */
    internal fun uplinkVideoStatisticsMessage(stats: UplinkVideoStatistics): String =
        "视频上行统计 (Video Uplink Statistics)\n" +
            "├─ ${label("分辨率：", "Resolution: ")}${resolution(stats.width, stats.height)}\n" +
            "├─ ${label("发送码率：", "Send Bitrate: ")}${metric(stats.bitrateKbps, "kbps")}\n" +
            "├─ ${label("采集帧率：", "Capture Frame Rate: ")}${metric(stats.captureFrameRate, "fps")}\n" +
            "├─ ${label("编码帧率：", "Encode Frame Rate: ")}${metric(stats.encodeFrameRate, "fps")}\n" +
            "├─ ${label("发送帧率：", "Send Frame Rate: ")}${metric(stats.sendFrameRate, "fps")}\n" +
            "└─ ${label("视频丢包率：", "Video Packet Loss: ")}${metric(stats.packetLossPercent, "%")}"

    /**
     * 生成下行视频指标的分行日志文本。
     *
     * @param stats RTC 视频统计快照。
     * @return 下行视频统计的分行文本。
     */
    internal fun downlinkVideoStatisticsMessage(stats: DownlinkVideoStatistics): String =
        "视频下行统计 (Video Downlink Statistics)\n" +
            "├─ ${label("分辨率：", "Resolution: ")}${resolution(stats.width, stats.height)}\n" +
            "├─ ${label("接收码率：", "Receive Bitrate: ")}${metric(stats.bitrateKbps, "kbps")}\n" +
            "├─ ${label("解码帧率：", "Decode Frame Rate: ")}${metric(stats.decodeFrameRate, "fps")}\n" +
            "├─ ${label("渲染帧率：", "Render Frame Rate: ")}${metric(stats.renderFrameRate, "fps")}\n" +
            "├─ ${label("视频丢包率：", "Video Packet Loss: ")}${metric(stats.packetLossPercent, "%")}\n" +
            "└─ ${label("端到端时延：", "End-to-End Delay: ")}${metric(stats.endToEndDelayMs, "ms")}"

    /**
     * 格式化指标值与单位；缺失值显示为不可用。
     *
     * @param value 待显示的指标数值；null 表示没有可用值。
     * @param unit 指标显示使用的单位文本。
     * @return 包含数值与单位的指标文本；缺失值显示为不可用。
     */
    private fun metric(value: Int?, unit: String): String = value?.let { "$it $unit" } ?: "—"

    /**
     * 格式化视频宽高；缺失尺寸显示为不可用。
     *
     * @param width 画面宽度，单位为像素。
     * @param height 画面高度，单位为像素。
     * @return 宽高组成的分辨率文本；无有效尺寸时显示为不可用。
     */
    private fun resolution(width: Int?, height: Int?): String =
        if (width != null && height != null) "$width × $height" else "—"

    /**
     * 根据日志环境选择中文或英文文本。
     *
     * @param chinese 国内环境使用的中文文本。
     * @param english 海外环境使用的英文文本。
     * @return 符合当前日志语言配置的文本。
     */
    private fun label(chinese: String, english: String): String = XmaxLogger.localized(chinese, english)
}
