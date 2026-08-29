package ai.xmax.sdk.foundation.rtc

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

/** 接收 RTC 网络质量和性能回退事件。 */
internal interface RtcQualityListener {
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
