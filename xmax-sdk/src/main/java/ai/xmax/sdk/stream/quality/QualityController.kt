package ai.xmax.sdk.stream.quality

import ai.xmax.sdk.RealtimeNetworkQuality
import ai.xmax.sdk.RealtimeNetworkQualityLevel
import ai.xmax.sdk.RealtimeNetworkQualityListener
import ai.xmax.sdk.RealtimePerformanceAlarm
import ai.xmax.sdk.RealtimePerformanceAlarmListener
import ai.xmax.sdk.RealtimePerformanceStatus
import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.foundation.rtc.RtcManaging
import ai.xmax.sdk.foundation.rtc.RtcQualityLevel
import ai.xmax.sdk.foundation.rtc.RtcQualityListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** 转换 RTC 质量事件并通知实时生成业务监听器。 */
internal class QualityController(
    rtcManager: RtcManaging,
    private val callbackScope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate,
    ),
) : QualityControlling, RtcQualityListener {
    private val listenerLock = Any()
    private var networkQualityListener: RealtimeNetworkQualityListener? = null
    private var performanceAlarmListener: RealtimePerformanceAlarmListener? = null

    init {
        rtcManager.setQualityListener(this)
    }

    override fun setNetworkQualityListener(listener: RealtimeNetworkQualityListener?) {
        synchronized(listenerLock) {
            networkQualityListener = listener
        }
    }

    override fun setPerformanceAlarmListener(listener: RealtimePerformanceAlarmListener?) {
        synchronized(listenerLock) {
            performanceAlarmListener = listener
        }
    }

    override fun onNetworkQuality(
        uplink: RtcQualityLevel,
        downlink: RtcQualityLevel,
    ) {
        callbackScope.launch {
            synchronized(listenerLock) { networkQualityListener }?.onNetworkQualityChanged(
                RealtimeNetworkQuality(
                    uplink = networkQualityLevel(uplink),
                    downlink = networkQualityLevel(downlink),
                ),
            )
        }
    }

    override fun onPerformanceAlarm(
        limited: Boolean,
        suggestedWidth: Int,
        suggestedHeight: Int,
        suggestedFrameRate: Int,
    ) {
        callbackScope.launch {
            synchronized(listenerLock) { performanceAlarmListener }?.onPerformanceAlarm(
                RealtimePerformanceAlarm(
                    status = if (limited) {
                        RealtimePerformanceStatus.LIMITED
                    } else {
                        RealtimePerformanceStatus.RECOVERED
                    },
                    suggestedVideoFormat = suggestedVideoFormat(
                        width = suggestedWidth,
                        height = suggestedHeight,
                        frameRate = suggestedFrameRate,
                    ),
                ),
            )
        }
    }

    private fun networkQualityLevel(level: RtcQualityLevel): RealtimeNetworkQualityLevel =
        when (level) {
            RtcQualityLevel.UNKNOWN -> RealtimeNetworkQualityLevel.UNKNOWN
            RtcQualityLevel.EXCELLENT -> RealtimeNetworkQualityLevel.EXCELLENT
            RtcQualityLevel.GOOD -> RealtimeNetworkQualityLevel.GOOD
            RtcQualityLevel.POOR -> RealtimeNetworkQualityLevel.POOR
            RtcQualityLevel.BAD -> RealtimeNetworkQualityLevel.BAD
            RtcQualityLevel.VERY_BAD -> RealtimeNetworkQualityLevel.VERY_BAD
            RtcQualityLevel.DOWN -> RealtimeNetworkQualityLevel.DOWN
        }

    private fun suggestedVideoFormat(
        width: Int,
        height: Int,
        frameRate: Int,
    ): RealtimeVideoFormat? = if (width > 0 && height > 0 && frameRate > 0) {
        RealtimeVideoFormat(width, height, frameRate)
    } else {
        null
    }
}
