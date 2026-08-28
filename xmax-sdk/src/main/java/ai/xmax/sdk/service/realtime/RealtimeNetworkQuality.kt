package ai.xmax.sdk

/** RTC 网络质量等级。 */
public enum class RealtimeNetworkQualityLevel(public val value: String) {
    UNKNOWN("Unknown"),
    EXCELLENT("Excellent"),
    GOOD("Good"),
    POOR("Poor"),
    BAD("Bad"),
    VERY_BAD("VeryBad"),
    DOWN("Down"),
}

/** 实时会话的上下行网络质量。 */
public data class RealtimeNetworkQuality(
    public val uplink: RealtimeNetworkQualityLevel,
    public val downlink: RealtimeNetworkQualityLevel,
)

/** 实时网络质量监听器。 */
public fun interface RealtimeNetworkQualityListener {
    public fun onNetworkQualityChanged(quality: RealtimeNetworkQuality)
}
