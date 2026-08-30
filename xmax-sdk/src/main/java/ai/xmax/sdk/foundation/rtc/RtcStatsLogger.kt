package ai.xmax.sdk.foundation.rtc

import ai.xmax.sdk.XmaxLogger
import ai.xmax.sdk.XmaxLoggerOption
import com.ss.bytertc.engine.SysStats
import com.ss.bytertc.engine.type.LocalStreamStats
import com.ss.bytertc.engine.type.NetworkQuality
import com.ss.bytertc.engine.type.NetworkQualityStats
import com.ss.bytertc.engine.type.PerformanceAlarmReason
import com.ss.bytertc.engine.type.RemoteStreamStats
import com.ss.bytertc.engine.type.SourceWantedData
import java.util.Locale

/** 将火山 RTC 运行统计输出为统一的 Xmax 调试日志。 */
internal object RtcStatsLogger {
    private const val CATEGORY = "RTC"

    fun logLocalStreamStats(stats: LocalStreamStats) {
        XmaxLogger.debug(
            { localStreamStatsMessage(stats) },
            category = CATEGORY,
            option = XmaxLoggerOption.performance,
        )
    }

    fun logRemoteStreamStats(stats: RemoteStreamStats) {
        XmaxLogger.debug(
            { remoteStreamStatsMessage(stats) },
            category = CATEGORY,
            option = XmaxLoggerOption.performance,
        )
    }

    fun logNetworkQuality(
        localQuality: NetworkQualityStats,
        remoteQualities: Array<out NetworkQualityStats>,
    ) {
        XmaxLogger.debug(
            { networkQualityMessage(localQuality, remoteQualities) },
            category = CATEGORY,
            option = XmaxLoggerOption.performance,
        )
    }

    fun logSystemStats(stats: SysStats) {
        XmaxLogger.debug(
            { systemStatsMessage(stats) },
            category = CATEGORY,
            option = XmaxLoggerOption.performance,
        )
    }

    fun logPerformanceAlarm(reason: PerformanceAlarmReason, data: SourceWantedData) {
        XmaxLogger.debug(
            { performanceAlarmMessage(reason, data) },
            category = CATEGORY,
            option = XmaxLoggerOption.performance,
        )
    }

    internal fun localStreamStatsMessage(stats: LocalStreamStats): String {
        val video = stats.videoStats
        return "本地视频发送 (Local Video Uplink)\n" +
            "├─ 分辨率：${video.encodedFrameWidth} × ${video.encodedFrameHeight}\n" +
            "├─ 发送码率：${video.sentKBitrate} kbps\n" +
            "├─ 采集帧率：${video.inputFrameRate} fps\n" +
            "├─ 编码帧率：${video.encoderOutputFrameRate} fps\n" +
            "├─ 发送帧率：${video.sentFrameRate} fps\n" +
            "├─ 视频丢包率：${percentage(video.videoLossRate.toDouble())}\n" +
            "├─ 网络往返时延：${video.rtt} ms\n" +
            "└─ 网络抖动：${video.jitter} ms"
    }

    internal fun remoteStreamStatsMessage(stats: RemoteStreamStats): String {
        val video = stats.videoStats
        return "远端视频接收 (Remote Video Downlink)\n" +
            "├─ 分辨率：${video.width} × ${video.height}\n" +
            "├─ 接收码率：${video.receivedKBitrate} kbps\n" +
            "├─ 解码帧率：${video.decoderOutputFrameRate} fps\n" +
            "├─ 渲染帧率：${video.rendererOutputFrameRate} fps\n" +
            "├─ 视频丢包率：${percentage(video.videoLossRate.toDouble())}\n" +
            "├─ 网络往返时延：${video.rtt} ms\n" +
            "├─ 卡顿次数：${video.stallCount} 次\n" +
            "├─ 卡顿时长：${video.stallDuration} ms\n" +
            "└─ 端到端时延：${video.e2eDelay} ms"
    }

    internal fun networkQualityMessage(
        localQuality: NetworkQualityStats,
        remoteQualities: Array<out NetworkQualityStats>,
    ): String {
        val hasRemoteQuality = remoteQualities.isNotEmpty()
        val lines = mutableListOf(
            "网络质量 (Network Quality Metrics)",
            "${if (hasRemoteQuality) "├─" else "└─"} 本地发送（上行）",
            "${if (hasRemoteQuality) "│  " else "   "}├─ 质量：${networkQualityName(localQuality.txQuality)}",
            "${if (hasRemoteQuality) "│  " else "   "}└─ ${networkMetrics(localQuality, true)}",
        )
        remoteQualities.forEachIndexed { index, quality ->
            val isLast = index == remoteQualities.lastIndex
            val branch = if (isLast) "└─" else "├─"
            val indent = if (isLast) "   " else "│  "
            lines += "$branch 远端接收 ${quality.uid.orEmpty()}（下行）"
            lines += "$indent├─ 质量：${networkQualityName(quality.rxQuality)}"
            lines += "$indent└─ ${networkMetrics(quality, false)}"
        }
        return lines.joinToString("\n")
    }

    internal fun systemStatsMessage(stats: SysStats): String {
        val cpu = "应用 ${percentage(stats.cpuAppUsage)}，" +
            "系统 ${percentage(stats.cpuTotalUsage)}，${stats.cpuCores} 核"
        val memory = "应用 ${format("%.0f", stats.memoryUsage)} MB，" +
            "应用占用 ${format("%.2f", stats.memoryRatio)}%，" +
            "系统占用 ${format("%.2f", stats.totalMemoryRatio)}%"
        return "性能统计 (System Performance Metrics)\n" +
            "├─ CPU：$cpu\n" +
            "└─ 内存：$memory"
    }

    internal fun performanceAlarmMessage(
        reason: PerformanceAlarmReason,
        data: SourceWantedData,
    ): String {
        val state = performanceAlarmName(reason)
        return if (data.width > 0 && data.height > 0 && data.frameRate > 0) {
            "性能告警 (Performance Alert)\n" +
                "├─ 状态：$state\n" +
                "└─ 建议：${data.width} × ${data.height}，${data.frameRate} fps"
        } else {
            "性能告警 (Performance Alert)\n└─ 状态：$state"
        }
    }

    private fun networkMetrics(quality: NetworkQualityStats, includesRtt: Boolean): String {
        val metrics = mutableListOf("丢包 ${percentage(quality.fractionLost)}")
        if (includesRtt) metrics += "RTT ${quality.rtt} ms"
        metrics += "带宽 ${format("%.0f", quality.totalBandwidth / 1_000.0)} kbps"
        return "指标：${metrics.joinToString("，")}"
    }

    private fun networkQualityName(quality: Int): String = when (quality) {
        NetworkQuality.NETWORK_QUALITY_EXCELLENT -> "极好"
        NetworkQuality.NETWORK_QUALITY_GOOD -> "良好"
        NetworkQuality.NETWORK_QUALITY_POOR -> "较差"
        NetworkQuality.NETWORK_QUALITY_BAD -> "差"
        NetworkQuality.NETWORK_QUALITY_VERY_BAD -> "极差"
        NetworkQuality.NETWORK_QUALITY_DOWN -> "断网"
        else -> "未知"
    }

    private fun performanceAlarmName(reason: PerformanceAlarmReason): String = when (reason) {
        PerformanceAlarmReason.BANDWIDTH_FALLBACKED -> "网络受限"
        PerformanceAlarmReason.BANDWIDTH_RESUMED -> "网络恢复"
        PerformanceAlarmReason.PERFORMANCE_FALLBACKED -> "设备性能受限"
        PerformanceAlarmReason.PERFORMANCE_RESUMED -> "设备性能恢复"
    }

    private fun percentage(value: Double): String = format("%.2f%%", value * 100.0)

    private fun format(pattern: String, value: Double): String =
        String.format(Locale.US, pattern, value)
}
