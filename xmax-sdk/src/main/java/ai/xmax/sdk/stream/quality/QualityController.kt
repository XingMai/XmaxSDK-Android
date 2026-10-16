package ai.xmax.sdk.stream.quality

import ai.xmax.sdk.RealtimeNetworkQuality
import ai.xmax.sdk.UplinkVideoStatistics
import ai.xmax.sdk.UplinkVideoStatisticsListener
import ai.xmax.sdk.DownlinkVideoStatistics
import ai.xmax.sdk.DownlinkVideoStatisticsListener
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
 * 转换 RTC 质量事件，为内部调档提供样本，并在主线程派发公共统计与告警。
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
    private var adaptiveQualityListener: ((RealtimeNetworkQuality) -> Unit)? = null
    private var networkQualityListener: RealtimeNetworkQualityListener? = null
    private var performanceAlarmListener: RealtimePerformanceAlarmListener? = null
    private var uplinkStatisticsVersion = 0L
    private var downlinkStatisticsVersion = 0L
    private var uplinkStatisticsEnabled = false
    private var downlinkStatisticsUser: String? = null
    private var uplinkStatisticsListener: UplinkVideoStatisticsListener? = null
    private var downlinkStatisticsListener: DownlinkVideoStatisticsListener? = null

    override fun setUplinkVideoStatisticsListener(listener: UplinkVideoStatisticsListener?) {
        synchronized(listenerLock) {
            uplinkStatisticsVersion++
            uplinkStatisticsListener = listener
        }
    }

    override fun setDownlinkVideoStatisticsListener(listener: DownlinkVideoStatisticsListener?) {
        synchronized(listenerLock) {
            downlinkStatisticsVersion++
            downlinkStatisticsListener = listener
        }
    }

    /**
     * 设置上行视频统计的接收状态，并清空当前统计。
     */
    override fun setUplinkVideoStatisticsEnabled(enabled: Boolean) {
        synchronized(listenerLock) {
            uplinkStatisticsVersion++
            uplinkStatisticsEnabled = enabled
        }
        onUplinkVideoStatistics(null)
    }

    /**
     * 设置下行视频统计的目标用户，并清空当前统计；传 null 停止接收。
     */
    override fun setDownlinkVideoStatisticsUser(userId: String?) {
        synchronized(listenerLock) {
            downlinkStatisticsVersion++
            downlinkStatisticsUser = userId
        }
        onDownlinkVideoStatistics(null)
    }

    override fun onUplinkVideoStatistics(statistics: UplinkVideoStatistics?) {
        val registration = synchronized(listenerLock) {
            if (statistics != null && !uplinkStatisticsEnabled) return
            uplinkStatisticsVersion to uplinkStatisticsListener
        }
        if (registration.second == null) return
        callbackScope.launch {
            if (synchronized(listenerLock) { uplinkStatisticsVersion != registration.first }) return@launch
            protect { registration.second?.onStatistics(statistics) }
        }
    }

    override fun onDownlinkVideoStatistics(statistics: DownlinkVideoStatistics?) {
        val registration = synchronized(listenerLock) {
            if (statistics != null && statistics.userId != downlinkStatisticsUser) return
            downlinkStatisticsVersion to downlinkStatisticsListener
        }
        if (registration.second == null) return
        callbackScope.launch {
            if (synchronized(listenerLock) { downlinkStatisticsVersion != registration.first }) return@launch
            protect { registration.second?.onStatistics(statistics) }
        }
    }

    init {
        rtcManager.setQualityListener(this)
    }

    override fun setAdaptiveQualityListener(listener: ((RealtimeNetworkQuality) -> Unit)?) {
        synchronized(listenerLock) { adaptiveQualityListener = listener }
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
        val quality = RealtimeNetworkQuality(networkQualityLevel(uplink), networkQualityLevel(downlink))
        val adaptive = synchronized(listenerLock) { adaptiveQualityListener }
        protect { adaptive?.invoke(quality) }
        val registration = synchronized(listenerLock) { networkVersion to networkQualityListener }
        callbackScope.launch {
            if (synchronized(listenerLock) { networkVersion != registration.first }) return@launch
            protect { registration.second?.onNetworkQualityChanged(quality) }
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

    /**
     * 单个方向的调档状态机；计算候选档位，只有应用成功才提交新档位。
     */
    internal class Direction(
        val baseline: RealtimeVideoFormat,
        level: Int,
        val downlink: Boolean,
        private val policy: QualityPolicy = QualityPolicy(),
    ) {
        @Volatile var level = QualityVideoFormat.initialLevel(baseline, level, downlink)
            private set
        val format: RealtimeVideoFormat get() = QualityVideoFormat.resolve(baseline, level, downlink)
        private var good = 0
        private var poor = 0
        private var lastSample: Long? = null
        private var stableUntil = 0L
        private var upgradeAfter = 0L
        private var probeUntil = 0L

        data class Change(val from: Int, val to: Int, val reason: String)

        /**
         * 清空连续样本，保留当前档位、稳定窗口和升档冷却。
         */
        @Synchronized
        fun resetSamples() {
            good = 0
            poor = 0
            lastSample = null
        }

        /**
         * 根据新鲜、连续的质量样本计算下一档位。
         */
        @Synchronized
        fun observe(quality: RealtimeNetworkQualityLevel, sampleAt: Long, now: Long): Change? {
            if (sampleAt > now || (lastSample != null && sampleAt <= lastSample!!)) return null

            if (lastSample != null && sampleAt - lastSample!! > policy.sampleTimeoutMs) resetSamples()

            lastSample = sampleAt
            if (now - sampleAt > policy.sampleTimeoutMs || sampleAt < stableUntil) {
                good = 0
                poor = 0
                return null
            }

            val step = when (quality) {
                RealtimeNetworkQualityLevel.BAD, RealtimeNetworkQualityLevel.VERY_BAD -> {
                    good = 0
                    if (++poor < policy.poorSamples) return null
                    1
                }
                RealtimeNetworkQualityLevel.EXCELLENT, RealtimeNetworkQualityLevel.GOOD -> {
                    poor = 0
                    if (sampleAt < upgradeAfter) {
                        good = 0
                        return null
                    }

                    if (++good < policy.goodSamples) return null
                    -1
                }
                else -> {
                    good = 0
                    poor = 0
                    return null
                }
            }
            var next = level + step
            while (next in 1..QualityTiers.percentages.size && QualityVideoFormat.resolve(baseline, next, downlink) == format) {
                next += step
            }
            good = 0
            poor = 0
            if (next !in 1..QualityTiers.percentages.size) return null

            next = QualityVideoFormat.canonicalLevel(baseline, next, downlink)
            if (!downlink && !QualityVideoFormat.supportsEncoding(QualityVideoFormat.resolve(baseline, next, false))) return null

            return Change(level, next, if (step < 0) "probeUpgrade" else if (now < probeUntil) "failedProbe" else "poorNetwork")
        }

        /**
         * 记录调档应用结果，并更新稳定窗口和试探升档状态。
         */
        @Synchronized
        fun complete(change: Change, success: Boolean, now: Long) {
            resetSamples()
            stableUntil = now + policy.stabilizationMs
            if (!success) return

            level = change.to
            probeUntil = if (change.reason == "probeUpgrade") now + policy.probeMs else 0
            if (change.reason == "failedProbe") upgradeAfter = now + policy.upgradeCooldownMs
        }
    }
}
