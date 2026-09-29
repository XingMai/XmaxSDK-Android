package ai.xmax.sdk.foundation.rtc

import ai.xmax.sdk.XmaxLogger
import ai.xmax.sdk.XmaxLoggerOption
import com.ss.bytertc.engine.type.LocalStreamStats
import com.ss.bytertc.engine.type.NetworkQuality
import com.ss.bytertc.engine.type.NetworkQualityStats
import com.ss.bytertc.engine.type.PerformanceAlarmReason
import com.ss.bytertc.engine.type.RemoteStreamStats
import com.ss.bytertc.engine.type.SourceWantedData
import java.util.Locale

/** 将火山 RTC 运行统计输出为统一的 Xmax 调试日志。 */
internal object RtcStatsLogger {
    fun logLocalStreamStats(stats: LocalStreamStats) {
        XmaxLogger.rtc.debug(
            message = { localStreamStatsMessage(stats) },
            option = XmaxLoggerOption.performance,
        )
    }

    fun logRemoteStreamStats(stats: RemoteStreamStats) {
        XmaxLogger.rtc.debug(
            message = { remoteStreamStatsMessage(stats) },
            option = XmaxLoggerOption.performance,
        )
    }

    fun logNetworkQuality(
        localQuality: NetworkQualityStats,
        remoteQualities: Array<out NetworkQualityStats>,
    ) {
        XmaxLogger.rtc.debug(
            message = { networkQualityMessage(localQuality, remoteQualities) },
            option = XmaxLoggerOption.performance,
        )
    }

    fun logPerformanceAlarm(reason: PerformanceAlarmReason, data: SourceWantedData) {
        XmaxLogger.rtc.debug(
            message = { performanceAlarmMessage(reason, data) },
            option = XmaxLoggerOption.performance,
        )
    }

    internal fun localStreamStatsMessage(stats: LocalStreamStats): String {
        val video = stats.videoStats
        return "本地视频发送 (Local Video Uplink)\n" +
            "├─ ${label("分辨率：", "Resolution: ")}${video.encodedFrameWidth} × ${video.encodedFrameHeight}\n" +
            "├─ ${label("发送码率：", "Send Bitrate: ")}${video.sentKBitrate} kbps\n" +
            "├─ ${label("采集帧率：", "Capture Frame Rate: ")}${video.inputFrameRate} fps\n" +
            "├─ ${label("编码帧率：", "Encode Frame Rate: ")}${video.encoderOutputFrameRate} fps\n" +
            "├─ ${label("发送帧率：", "Send Frame Rate: ")}${video.sentFrameRate} fps\n" +
            "├─ ${label("视频丢包率：", "Video Packet Loss: ")}${percentage(video.videoLossRate.toDouble())}\n" +
            "├─ ${label("网络往返时延：", "Round-Trip Time: ")}${video.rtt} ms\n" +
            "└─ ${label("网络抖动：", "Network Jitter: ")}${video.jitter} ms"
    }

    internal fun remoteStreamStatsMessage(stats: RemoteStreamStats): String {
        val video = stats.videoStats
        return "远端视频接收 (Remote Video Downlink)\n" +
            "├─ ${label("分辨率：", "Resolution: ")}${video.width} × ${video.height}\n" +
            "├─ ${label("接收码率：", "Receive Bitrate: ")}${video.receivedKBitrate} kbps\n" +
            "├─ ${label("解码帧率：", "Decode Frame Rate: ")}${video.decoderOutputFrameRate} fps\n" +
            "├─ ${label("渲染帧率：", "Render Frame Rate: ")}${video.rendererOutputFrameRate} fps\n" +
            "├─ ${label("视频丢包率：", "Video Packet Loss: ")}${percentage(video.videoLossRate.toDouble())}\n" +
            "├─ ${label("网络往返时延：", "Round-Trip Time: ")}${video.rtt} ms\n" +
            "├─ ${label("卡顿次数：", "Stall Count: ")}${video.stallCount}${label(" 次", "")}\n" +
            "├─ ${label("卡顿时长：", "Stall Duration: ")}${video.stallDuration} ms\n" +
            "└─ ${label("端到端时延：", "End-to-End Delay: ")}${video.e2eDelay} ms"
    }

    internal fun networkQualityMessage(
        localQuality: NetworkQualityStats,
        remoteQualities: Array<out NetworkQualityStats>,
    ): String {
        val hasRemoteQuality = remoteQualities.isNotEmpty()
        val lines = mutableListOf(
            "网络质量 (Network Quality Metrics)",
            "${if (hasRemoteQuality) "├─" else "└─"} ${label("本地发送（上行）", "Local Uplink")}",
            "${if (hasRemoteQuality) "│  " else "   "}├─ " +
                "${label("质量：", "Quality: ")}${networkQualityName(localQuality.txQuality)}",
            "${if (hasRemoteQuality) "│  " else "   "}└─ ${networkMetrics(localQuality, true)}",
        )
        remoteQualities.forEachIndexed { index, quality ->
            val isLast = index == remoteQualities.lastIndex
            val branch = if (isLast) "└─" else "├─"
            val indent = if (isLast) "   " else "│  "
            lines += "$branch ${label("远端接收 ${quality.uid.orEmpty()}（下行）", "Remote Downlink ${quality.uid.orEmpty()}")}"
            lines += "$indent├─ ${label("质量：", "Quality: ")}${networkQualityName(quality.rxQuality)}"
            lines += "$indent└─ ${networkMetrics(quality, false)}"
        }
        return lines.joinToString("\n")
    }

    internal fun performanceAlarmMessage(
        reason: PerformanceAlarmReason,
        data: SourceWantedData,
    ): String {
        val state = performanceAlarmName(reason)
        return if (data.width > 0 && data.height > 0 && data.frameRate > 0) {
            "性能告警 (Performance Alert)\n" +
                "├─ ${label("状态：", "Status: ")}$state\n" +
                "└─ ${label("建议：", "Recommendation: ")}${data.width} × ${data.height}" +
                "${label("，", ", ")}${data.frameRate} fps"
        } else {
            "性能告警 (Performance Alert)\n└─ ${label("状态：", "Status: ")}$state"
        }
    }

    private fun networkMetrics(quality: NetworkQualityStats, includesRtt: Boolean): String {
        val metrics = mutableListOf("${label("丢包", "Packet Loss")} ${percentage(quality.fractionLost)}")
        if (includesRtt) metrics += "RTT ${quality.rtt} ms"
        metrics += "${label("带宽", "Bandwidth")} ${format("%.0f", quality.totalBandwidth / 1_000.0)} kbps"
        return "${label("指标：", "Metrics: ")}${metrics.joinToString(label("，", ", "))}"
    }

    private fun networkQualityName(quality: Int): String = when (quality) {
        NetworkQuality.NETWORK_QUALITY_EXCELLENT -> label("极好", "Excellent")
        NetworkQuality.NETWORK_QUALITY_GOOD -> label("良好", "Good")
        NetworkQuality.NETWORK_QUALITY_POOR -> label("较差", "Poor")
        NetworkQuality.NETWORK_QUALITY_BAD -> label("差", "Bad")
        NetworkQuality.NETWORK_QUALITY_VERY_BAD -> label("极差", "Very Bad")
        NetworkQuality.NETWORK_QUALITY_DOWN -> label("断网", "Disconnected")
        else -> label("未知", "Unknown")
    }

    private fun performanceAlarmName(reason: PerformanceAlarmReason): String = when (reason) {
        PerformanceAlarmReason.BANDWIDTH_FALLBACKED -> label("网络受限", "Bandwidth Limited")
        PerformanceAlarmReason.BANDWIDTH_RESUMED -> label("网络恢复", "Bandwidth Recovered")
        PerformanceAlarmReason.PERFORMANCE_FALLBACKED -> label("设备性能受限", "Device Performance Limited")
        PerformanceAlarmReason.PERFORMANCE_RESUMED -> label("设备性能恢复", "Device Performance Recovered")
    }

    private fun label(chinese: String, english: String): String = XmaxLogger.localized(chinese, english)

    private fun percentage(value: Double): String = format("%.2f%%", value * 100.0)

    private fun format(pattern: String, value: Double): String =
        String.format(Locale.US, pattern, value)
}
