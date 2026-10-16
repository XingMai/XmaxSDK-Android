package com.xmax.xlab.modules.xlrealtime

import ai.xmax.sdk.RealtimeNetworkQuality
import ai.xmax.sdk.RealtimeNetworkQualityLevel
import ai.xmax.sdk.RemoteVideoStatistics
import ai.xmax.sdk.VideoStatistics
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xmax.xlab.R
import com.xmax.xlab.xLabText

/**
 * 只展示 RTC 实测值；开关不控制采集或日志，也不拦截面板外的视频交互。
 */
@Composable
internal fun StatisticsPanel(
    local: VideoStatistics?,
    remote: RemoteVideoStatistics?,
    quality: RealtimeNetworkQuality?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(184.dp)
            .heightIn(max = 340.dp)
            .background(Color.Black.copy(alpha = 0.64f), RoundedCornerShape(8.dp))
            .verticalScroll(rememberScrollState())
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        StatisticsHeading(xLabText(R.string.statistics_uplink))
        StatisticsRow(xLabText(R.string.statistics_resolution), statisticsResolution(local?.width, local?.height))
        StatisticsRow(xLabText(R.string.statistics_frame_rate), statisticsMetric(local?.frameRate, "fps"))
        StatisticsRow(xLabText(R.string.statistics_bitrate), statisticsMetric(local?.bitrateKbps, "kbps"))
        StatisticsRow(xLabText(R.string.statistics_loss), statisticsMetric(local?.uplinkLossPercent, "%"))
        StatisticsRow(xLabText(R.string.statistics_network), qualityText(quality?.uplink))
        StatisticsHeading(xLabText(R.string.statistics_downlink), Modifier.padding(top = 6.dp))
        StatisticsRow(xLabText(R.string.statistics_resolution), statisticsResolution(remote?.width, remote?.height))
        StatisticsRow(xLabText(R.string.statistics_frame_rate), statisticsMetric(remote?.frameRate, "fps"))
        StatisticsRow(xLabText(R.string.statistics_bitrate), statisticsMetric(remote?.bitrateKbps, "kbps"))
        StatisticsRow(xLabText(R.string.statistics_loss), statisticsMetric(remote?.downlinkLossPercent, "%"))
        StatisticsRow(xLabText(R.string.statistics_network), qualityText(quality?.downlink))
        StatisticsRow(xLabText(R.string.statistics_e2e), statisticsMetric(remote?.endToEndDelayMs, "ms"))
    }
}

@Composable
private fun StatisticsHeading(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, color = Color.White, fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun StatisticsRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Color.White.copy(alpha = 0.7f), fontSize = 10.sp, lineHeight = 14.sp)
        Text(value, color = Color.White, fontSize = 10.sp, lineHeight = 14.sp)
    }
}

/**
 * 与顶部菜单保持相同视觉尺寸，蓝色表示面板已打开。
 */
@Composable
internal fun StatisticsGlyph(selected: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val color = if (selected) Color(0xFF64B5FF) else Color.White
        listOf(0.45f, 0.85f, 0.65f).forEachIndexed { index, height ->
            val x = size.width * (0.2f + index * 0.3f)
            drawLine(color, Offset(x, size.height * 0.9f), Offset(x, size.height * (0.9f - height)),
                strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
        }
    }
}

/**
 * 未收到有效统计时显示占位符，零帧率或零码率仍按实际值显示。
 */
internal fun statisticsMetric(value: Int?, unit: String): String = value?.let { "$it $unit" } ?: "—"

internal fun statisticsResolution(width: Int?, height: Int?): String =
    if (width != null && height != null) "$width × $height" else "—"

@Composable
private fun qualityText(quality: RealtimeNetworkQualityLevel?): String = when (quality) {
    RealtimeNetworkQualityLevel.EXCELLENT -> xLabText(R.string.statistics_quality_excellent)
    RealtimeNetworkQualityLevel.GOOD -> xLabText(R.string.statistics_quality_good)
    RealtimeNetworkQualityLevel.POOR -> xLabText(R.string.statistics_quality_poor)
    RealtimeNetworkQualityLevel.BAD -> xLabText(R.string.statistics_quality_bad)
    RealtimeNetworkQualityLevel.VERY_BAD -> xLabText(R.string.statistics_quality_very_bad)
    RealtimeNetworkQualityLevel.DOWN -> xLabText(R.string.statistics_quality_down)
    else -> "—"
}
