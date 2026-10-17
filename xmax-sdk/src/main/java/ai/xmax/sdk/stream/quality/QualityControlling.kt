//
//  QualityControlling.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

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
     *
     * @param listener 接收对应事件的监听器；null 表示注销。
     */
    fun setAdaptiveQualityListener(listener: ((RealtimeNetworkQuality) -> Unit)?)

    /**
     * 设置上行视频统计的接收状态，并清空当前统计。
     *
     * @param enabled 是否启用对应能力。
     */
    fun setUplinkVideoStatisticsEnabled(enabled: Boolean)

    /**
     * 设置下行视频统计的目标用户，并清空当前统计；传 null 停止接收。
     *
     * @param userId 房间中的业务用户标识。
     */
    fun setDownlinkVideoStatisticsUser(userId: String?)

    /**
     * 设置上行视频统计监听器，回调在主线程执行；传 null 注销，断开连接后仍保留监听器。
     *
     * @param listener 接收对应事件的监听器；null 表示注销。
     */
    fun setUplinkVideoStatisticsListener(listener: UplinkVideoStatisticsListener?)

    /**
     * 设置下行结果流统计监听器，回调在主线程执行；传 null 注销，断开连接后仍保留监听器。
     *
     * @param listener 接收对应事件的监听器；null 表示注销。
     */
    fun setDownlinkVideoStatisticsListener(listener: DownlinkVideoStatisticsListener?)

    /**
     * 设置 RTC 网络质量监听器；传 null 注销。
     *
     * @param listener 接收对应事件的监听器；null 表示注销。
     */
    fun setNetworkQualityListener(listener: RealtimeNetworkQualityListener?)

    /**
     * 设置 RTC 性能告警监听器；传 null 注销。
     *
     * @param listener 接收对应事件的监听器；null 表示注销。
     */
    fun setPerformanceAlarmListener(listener: RealtimePerformanceAlarmListener?)
}
