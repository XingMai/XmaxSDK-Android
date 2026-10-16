package ai.xmax.sdk.foundation.rtc

import ai.xmax.sdk.VideoStatistics
import ai.xmax.sdk.RemoteVideoStatistics

/** 中性的 RTC 网络质量等级。 */
internal enum class RtcQualityLevel {
    UNKNOWN,
    EXCELLENT,
    GOOD,
    POOR,
    BAD,
    VERY_BAD,
    DOWN,
}

/**
 * 接收厂商中立的视频统计、网络质量和性能回退事件。
 */
internal interface RtcQualityListener {
    fun onLocalVideoStatistics(statistics: VideoStatistics?)

    fun onRemoteVideoStatistics(statistics: RemoteVideoStatistics?)

    fun onNetworkQuality(
        uplink: RtcQualityLevel,
        downlink: RtcQualityLevel,
    )

    fun onPerformanceAlarm(
        limited: Boolean,
        suggestedWidth: Int,
        suggestedHeight: Int,
        suggestedFrameRate: Int,
    )
}
