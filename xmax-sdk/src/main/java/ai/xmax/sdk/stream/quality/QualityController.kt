package ai.xmax.sdk.stream.quality

import ai.xmax.sdk.RealtimeNetworkQuality
import ai.xmax.sdk.VideoStatistics
import ai.xmax.sdk.VideoStatisticsListener
import ai.xmax.sdk.RemoteVideoStatistics
import ai.xmax.sdk.RemoteVideoStatisticsListener
import ai.xmax.sdk.RealtimeNetworkQualityLevel
import ai.xmax.sdk.RealtimeNetworkQualityListener
import ai.xmax.sdk.RealtimePerformanceAlarm
import ai.xmax.sdk.RealtimePerformanceAlarmListener
import ai.xmax.sdk.RealtimePerformanceStatus
import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.foundation.rtc.RtcManaging
import ai.xmax.sdk.foundation.rtc.RtcQualityLevel
import ai.xmax.sdk.foundation.rtc.RtcQualityListener
import ai.xmax.sdk.XmaxLogger
import ai.xmax.sdk.ErrorMessageFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 转换 RTC 质量事件并在主线程派发统计、网络质量与性能告警。
 */
internal class QualityController(
    rtcManager: RtcManaging,
    private val callbackScope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main,
    ),
) : QualityControlling, RtcQualityListener {
    private val listenerLock = Any()
    private var networkVersion = 0L
    private var performanceVersion = 0L
    private var networkQualityListener: RealtimeNetworkQualityListener? = null
    private var performanceAlarmListener: RealtimePerformanceAlarmListener? = null
    private var localStatisticsVersion = 0L
    private var remoteStatisticsVersion = 0L
    private var localStatisticsEnabled = false
    private var remoteStatisticsUser: String? = null
    private var localStatisticsListener: VideoStatisticsListener? = null
    private var remoteStatisticsListener: RemoteVideoStatisticsListener? = null

    override fun setLocalVideoStatisticsListener(listener: VideoStatisticsListener?) {
        synchronized(listenerLock) {
            localStatisticsVersion++
            localStatisticsListener = listener
        }
    }

    override fun setRemoteVideoStatisticsListener(listener: RemoteVideoStatisticsListener?) {
        synchronized(listenerLock) {
            remoteStatisticsVersion++
            remoteStatisticsListener = listener
        }
    }

    /**
     * 设置本地视频统计的接收状态，并清空当前统计。
     */
    override fun setLocalVideoStatisticsEnabled(enabled: Boolean) {
        synchronized(listenerLock) {
            localStatisticsVersion++
            localStatisticsEnabled = enabled
        }
        onLocalVideoStatistics(null)
    }

    /**
     * 设置远端视频统计的目标用户，并清空当前统计；传 null 停止接收。
     */
    override fun setRemoteVideoStatisticsUser(userId: String?) {
        synchronized(listenerLock) {
            remoteStatisticsVersion++
            remoteStatisticsUser = userId
        }
        onRemoteVideoStatistics(null)
    }

    override fun onLocalVideoStatistics(statistics: VideoStatistics?) {
        val registration = synchronized(listenerLock) {
            if (statistics != null && !localStatisticsEnabled) return
            localStatisticsVersion to localStatisticsListener
        }
        if (registration.second == null) return
        callbackScope.launch {
            if (synchronized(listenerLock) { localStatisticsVersion != registration.first }) return@launch
            protect { registration.second?.onStatistics(statistics) }
        }
    }

    override fun onRemoteVideoStatistics(statistics: RemoteVideoStatistics?) {
        val registration = synchronized(listenerLock) {
            if (statistics != null && statistics.userId != remoteStatisticsUser) return
            remoteStatisticsVersion to remoteStatisticsListener
        }
        if (registration.second == null) return
        callbackScope.launch {
            if (synchronized(listenerLock) { remoteStatisticsVersion != registration.first }) return@launch
            protect { registration.second?.onStatistics(statistics) }
        }
    }

    init {
        rtcManager.setQualityListener(this)
    }

    override fun setNetworkQualityListener(listener: RealtimeNetworkQualityListener?) {
        synchronized(listenerLock) {
            networkVersion++
            networkQualityListener = listener
        }
    }

    override fun setPerformanceAlarmListener(listener: RealtimePerformanceAlarmListener?) {
        synchronized(listenerLock) {
            performanceVersion++
            performanceAlarmListener = listener
        }
    }

    override fun onNetworkQuality(
        uplink: RtcQualityLevel,
        downlink: RtcQualityLevel,
    ) {
        val registration = synchronized(listenerLock) { networkVersion to networkQualityListener }
        callbackScope.launch {
            if (synchronized(listenerLock) { networkVersion != registration.first }) return@launch
            protect { registration.second?.onNetworkQualityChanged(
                RealtimeNetworkQuality(
                    uplink = networkQualityLevel(uplink),
                    downlink = networkQualityLevel(downlink),
                ),
            ) }
        }
    }

    override fun onPerformanceAlarm(
        limited: Boolean,
        suggestedWidth: Int,
        suggestedHeight: Int,
        suggestedFrameRate: Int,
    ) {
        val registration = synchronized(listenerLock) { performanceVersion to performanceAlarmListener }
        callbackScope.launch {
            if (synchronized(listenerLock) { performanceVersion != registration.first }) return@launch
            protect { registration.second?.onPerformanceAlarm(
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
            ) }
        }
    }

    private fun protect(action: () -> Unit) {
        try { action() } catch (error: Throwable) {
            XmaxLogger.realtime.warn(
                message = { "Quality listener failed: ${ErrorMessageFormatter.format(error)}" },
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
