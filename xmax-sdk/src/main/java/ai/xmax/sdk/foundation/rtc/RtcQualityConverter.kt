package ai.xmax.sdk.foundation.rtc

import io.agora.rtc2.Constants
import ai.xmax.sdk.VideoStatistics
import ai.xmax.sdk.RemoteVideoStatistics
import io.agora.rtc2.IRtcEngineEventHandler.LocalVideoStats
import io.agora.rtc2.IRtcEngineEventHandler.RemoteVideoStats

/**
 * 转换声网网络质量和视频统计；不可用指标不伪造为有效测量值。
 */
internal object RtcQualityConverter {
    fun localVideoStatistics(stats: LocalVideoStats): VideoStatistics = VideoStatistics(
        width = stats.encodedFrameWidth.takeIf { it > 0 },
        height = stats.encodedFrameHeight.takeIf { it > 0 },
        frameRate = stats.sentFrameRate.takeIf { it >= 0 },
        bitrateKbps = stats.sentBitrate.takeIf { it >= 0 },
        uplinkLossPercent = stats.txPacketLossRate.takeIf { it in 0..100 },
    )

    fun remoteVideoStatistics(userId: String, stats: RemoteVideoStats): RemoteVideoStatistics = RemoteVideoStatistics(
        userId = userId,
        width = stats.width.takeIf { it > 0 },
        height = stats.height.takeIf { it > 0 },
        frameRate = stats.rendererOutputFrameRate.takeIf { it >= 0 },
        bitrateKbps = stats.receivedBitrate.takeIf { it >= 0 },
        downlinkLossPercent = stats.packetLossRate.takeIf { it in 0..100 },
        endToEndDelayMs = stats.e2eDelay.takeIf { it >= 0 },
    )

    fun convertLevel(quality: Int): RtcQualityLevel = when (quality) {
        Constants.QUALITY_EXCELLENT -> RtcQualityLevel.EXCELLENT
        Constants.QUALITY_GOOD -> RtcQualityLevel.GOOD
        Constants.QUALITY_POOR -> RtcQualityLevel.POOR
        Constants.QUALITY_BAD -> RtcQualityLevel.BAD
        Constants.QUALITY_VBAD -> RtcQualityLevel.VERY_BAD
        Constants.QUALITY_DOWN -> RtcQualityLevel.DOWN
        else -> RtcQualityLevel.UNKNOWN
    }
}
