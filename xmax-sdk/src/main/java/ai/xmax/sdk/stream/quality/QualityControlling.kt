package ai.xmax.sdk.stream.quality

import ai.xmax.sdk.RealtimeNetworkQualityListener
import ai.xmax.sdk.VideoStatisticsListener
import ai.xmax.sdk.RemoteVideoStatisticsListener
import ai.xmax.sdk.RealtimePerformanceAlarmListener

/**
 * 定义视频统计、网络质量与设备性能告警的监听能力。
 */
internal interface QualityControlling {
    fun setLocalVideoStatisticsEnabled(enabled: Boolean)

    fun setRemoteVideoStatisticsUser(userId: String?)

    fun setLocalVideoStatisticsListener(listener: VideoStatisticsListener?)

    fun setRemoteVideoStatisticsListener(listener: RemoteVideoStatisticsListener?)

    fun setNetworkQualityListener(listener: RealtimeNetworkQualityListener?)

    fun setPerformanceAlarmListener(listener: RealtimePerformanceAlarmListener?)
}
