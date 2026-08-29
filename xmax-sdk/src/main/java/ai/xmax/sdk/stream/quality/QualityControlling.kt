package ai.xmax.sdk.stream.quality

import ai.xmax.sdk.RealtimeNetworkQualityListener
import ai.xmax.sdk.RealtimePerformanceAlarmListener

/** 定义网络质量与设备性能告警的监听能力。 */
internal interface QualityControlling {
    fun setNetworkQualityListener(listener: RealtimeNetworkQualityListener?)

    fun setPerformanceAlarmListener(listener: RealtimePerformanceAlarmListener?)
}
