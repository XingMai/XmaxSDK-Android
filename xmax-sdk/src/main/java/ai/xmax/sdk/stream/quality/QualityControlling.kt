package ai.xmax.sdk.stream.quality

import ai.xmax.sdk.RealtimeNetworkQualityListener
import ai.xmax.sdk.RealtimeNetworkQuality
import ai.xmax.sdk.UplinkVideoStatisticsListener
import ai.xmax.sdk.DownlinkVideoStatisticsListener
import ai.xmax.sdk.RealtimePerformanceAlarmListener

/**
 * 定义视频统计、网络质量与设备性能告警的监听能力。
 */
internal interface QualityControlling {
    /**
     * 接收内部自动调档样本，独立于接入方的网络质量监听器。
     */
    fun setAdaptiveQualityListener(listener: ((RealtimeNetworkQuality) -> Unit)?)

    fun setUplinkVideoStatisticsEnabled(enabled: Boolean)

    fun setDownlinkVideoStatisticsUser(userId: String?)

    fun setUplinkVideoStatisticsListener(listener: UplinkVideoStatisticsListener?)

    fun setDownlinkVideoStatisticsListener(listener: DownlinkVideoStatisticsListener?)

    fun setNetworkQualityListener(listener: RealtimeNetworkQualityListener?)

    fun setPerformanceAlarmListener(listener: RealtimePerformanceAlarmListener?)
}
