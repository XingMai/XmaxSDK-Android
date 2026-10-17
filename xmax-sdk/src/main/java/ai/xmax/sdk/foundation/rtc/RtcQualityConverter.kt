//
//  RtcQualityConverter.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.foundation.rtc

import io.agora.rtc2.Constants
import ai.xmax.sdk.UplinkVideoStatistics
import ai.xmax.sdk.DownlinkVideoStatistics
import io.agora.rtc2.IRtcEngineEventHandler.LocalVideoStats
import io.agora.rtc2.IRtcEngineEventHandler.RemoteVideoStats

/**
 * 转换声网网络质量和视频统计；不可用指标不伪造为有效测量值。
 */
internal object RtcQualityConverter {
    /**
     * 将 RTC 本地视频统计转换为 SDK 上行指标。
     *
     * @param stats RTC 视频统计快照。
     * @return 已转换为 SDK 单位和字段约定的上行统计。
     */
    fun uplinkVideoStatistics(stats: LocalVideoStats): UplinkVideoStatistics = UplinkVideoStatistics(
        width = stats.encodedFrameWidth.takeIf { it > 0 },
        height = stats.encodedFrameHeight.takeIf { it > 0 },
        captureFrameRate = stats.captureFrameRate.takeIf { it >= 0 },
        encodeFrameRate = stats.encoderOutputFrameRate.takeIf { it >= 0 },
        sendFrameRate = stats.sentFrameRate.takeIf { it >= 0 },
        bitrateKbps = stats.sentBitrate.takeIf { it >= 0 },
        packetLossPercent = stats.txPacketLossRate.takeIf { it in 0..100 },
    )

    /**
     * 将 RTC 远端视频统计转换为 SDK 下行指标。
     *
     * @param userId 房间中的业务用户标识。
     * @param stats RTC 视频统计快照。
     * @return 已转换为 SDK 单位和字段约定的下行统计。
     */
    fun downlinkVideoStatistics(userId: String, stats: RemoteVideoStats): DownlinkVideoStatistics = DownlinkVideoStatistics(
        userId = userId,
        width = stats.width.takeIf { it > 0 },
        height = stats.height.takeIf { it > 0 },
        decodeFrameRate = stats.decoderOutputFrameRate.takeIf { it >= 0 },
        renderFrameRate = stats.rendererOutputFrameRate.takeIf { it >= 0 },
        bitrateKbps = stats.receivedBitrate.takeIf { it >= 0 },
        packetLossPercent = stats.packetLossRate.takeIf { it in 0..100 },
        endToEndDelayMs = stats.e2eDelay.takeIf { it >= 0 },
    )

    /**
     * 将 RTC 厂商的质量等级转换为 SDK 内部质量等级。
     *
     * @param quality RTC 厂商报告的网络质量等级。
     * @return RTC 质量值对应的内部质量等级。
     */
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
