//
//  RtcQualityListener.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.foundation.rtc

import ai.xmax.sdk.UplinkVideoStatistics
import ai.xmax.sdk.DownlinkVideoStatistics

/**
 * 中性的 RTC 网络质量等级。
 */
internal enum class RtcQualityLevel {
    /**
     * 尚无可用网络质量样本。
     */
    UNKNOWN,

    /**
     * 网络质量极佳。
     */
    EXCELLENT,

    /**
     * 网络质量良好。
     */
    GOOD,

    /**
     * 网络质量一般，可能影响实时传输。
     */
    POOR,

    /**
     * 网络质量较差，需要降低传输负载。
     */
    BAD,

    /**
     * 网络质量极差，实时传输明显受限。
     */
    VERY_BAD,

    /**
     * 网络不可用或传输已中断。
     */
    DOWN,
}

/**
 * 接收厂商中立的视频统计、网络质量和性能回退事件。
 */
internal interface RtcQualityListener {
    /**
     * 接收上行统计快照；null 表示当前没有有效统计。
     *
     * @param statistics 当前统计快照；null 表示没有可用统计。
     */
    fun onUplinkVideoStatistics(statistics: UplinkVideoStatistics?)

    /**
     * 接收下行统计快照；null 表示当前没有有效统计。
     *
     * @param statistics 当前统计快照；null 表示没有可用统计。
     */
    fun onDownlinkVideoStatistics(statistics: DownlinkVideoStatistics?)

    /**
     * 接收上下行网络质量样本。
     *
     * @param uplink 上行网络质量等级。
     * @param downlink 下行网络质量等级。
     */
    fun onNetworkQuality(
        uplink: RtcQualityLevel,
        downlink: RtcQualityLevel,
    )

    /**
     * 接收设备性能受限状态及建议的视频规格。
     *
     * @param limited 设备是否受到性能限制。
     * @param suggestedWidth 建议的视频宽度，单位为像素。
     * @param suggestedHeight 建议的视频高度，单位为像素。
     * @param suggestedFrameRate 建议的视频帧率，单位为 fps。
     */
    fun onPerformanceAlarm(
        limited: Boolean,
        suggestedWidth: Int,
        suggestedHeight: Int,
        suggestedFrameRate: Int,
    )
}
