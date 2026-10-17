//
//  RealtimeNetworkQuality.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

/**
 * RTC 网络质量等级。
 *
 * @param value 需要规范化、校验或显示的文本。
 */
public enum class RealtimeNetworkQualityLevel(public val value: String) {
    /**
     * 尚无可用网络质量样本。
     */
    UNKNOWN("Unknown"),

    /**
     * 网络质量极佳。
     */
    EXCELLENT("Excellent"),

    /**
     * 网络质量良好。
     */
    GOOD("Good"),

    /**
     * 网络质量一般，可能影响实时传输。
     */
    POOR("Poor"),

    /**
     * 网络质量较差，需要降低传输负载。
     */
    BAD("Bad"),

    /**
     * 网络质量极差，实时传输明显受限。
     */
    VERY_BAD("VeryBad"),

    /**
     * 网络不可用或传输已中断。
     */
    DOWN("Down"),
}

/**
 * 实时会话的上下行网络质量。
 *
 * @param uplink 上行网络质量等级。
 * @param downlink 下行网络质量等级。
 */
public data class RealtimeNetworkQuality(
    public val uplink: RealtimeNetworkQualityLevel,
    public val downlink: RealtimeNetworkQualityLevel,
)

/**
 * 实时网络质量监听器。
 */
public fun interface RealtimeNetworkQualityListener {
    /**
     * 通知当前上下行网络质量变化。
     *
     * @param quality 当前网络质量样本。
     */
    public fun onNetworkQualityChanged(quality: RealtimeNetworkQuality)
}
