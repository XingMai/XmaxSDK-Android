//
//  QualityController.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

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
 *
 * @param rtcManager 提供 RTC 采集、发布、订阅和渲染能力的管理器。
 * @param callbackScope 派发异步回调的协程作用域。
 */
internal class QualityController(
    rtcManager: RtcManaging,
    private val callbackScope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main,
    ),
) : QualityControlling, RtcQualityListener {
    /**
     * 质量监听配置的并发保护。
     */
    private val listenerLock = Any()

    /**
     * 网络质量、性能告警及自动调档的监听配置。
     */
    private var networkVersion = 0L
    private var performanceVersion = 0L
    private var adaptiveQualityListener: ((RealtimeNetworkQuality) -> Unit)? = null
    private var networkQualityListener: RealtimeNetworkQualityListener? = null
    private var performanceAlarmListener: RealtimePerformanceAlarmListener? = null

    /**
     * 上下行统计的有效版本、目标与监听配置。
     */
    private var uplinkStatisticsVersion = 0L
    private var downlinkStatisticsVersion = 0L
    private var uplinkStatisticsEnabled = false
    private var downlinkStatisticsUser: String? = null
    private var uplinkStatisticsListener: UplinkVideoStatisticsListener? = null
    private var downlinkStatisticsListener: DownlinkVideoStatisticsListener? = null

    /**
     * 设置上行视频统计监听器，回调在主线程执行；传 null 注销，断开连接后仍保留监听器。
     *
     * @param listener 接收对应事件的监听器；null 表示注销。
     */
    override fun setUplinkVideoStatisticsListener(listener: UplinkVideoStatisticsListener?) {
        synchronized(listenerLock) {
            uplinkStatisticsVersion++
            uplinkStatisticsListener = listener
        }
    }

    /**
     * 设置下行结果流统计监听器，回调在主线程执行；传 null 注销，断开连接后仍保留监听器。
     *
     * @param listener 接收对应事件的监听器；null 表示注销。
     */
    override fun setDownlinkVideoStatisticsListener(listener: DownlinkVideoStatisticsListener?) {
        synchronized(listenerLock) {
            downlinkStatisticsVersion++
            downlinkStatisticsListener = listener
        }
    }

    /**
     * 设置上行视频统计的接收状态，并清空当前统计。
     *
     * @param enabled 是否启用对应能力。
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
     *
     * @param userId 房间中的业务用户标识。
     */
    override fun setDownlinkVideoStatisticsUser(userId: String?) {
        synchronized(listenerLock) {
            downlinkStatisticsVersion++
            downlinkStatisticsUser = userId
        }
        onDownlinkVideoStatistics(null)
    }

    /**
     * 接收上行统计快照；null 表示当前没有有效统计。
     *
     * @param statistics 当前统计快照；null 表示没有可用统计。
     */
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

    /**
     * 接收下行统计快照；null 表示当前没有有效统计。
     *
     * @param statistics 当前统计快照；null 表示没有可用统计。
     */
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

    /**
     * 接收自动调档使用的网络质量样本，不占用公共监听器。
     *
     * @param listener 接收对应事件的监听器；null 表示注销。
     */
    override fun setAdaptiveQualityListener(listener: ((RealtimeNetworkQuality) -> Unit)?) {
        synchronized(listenerLock) { adaptiveQualityListener = listener }
    }

    /**
     * 设置 RTC 网络质量监听器；传 null 注销。
     *
     * @param listener 接收对应事件的监听器；null 表示注销。
     */
    override fun setNetworkQualityListener(listener: RealtimeNetworkQualityListener?) {
        synchronized(listenerLock) {
            networkVersion++
            networkQualityListener = listener
        }
    }

    /**
     * 设置 RTC 性能告警监听器；传 null 注销。
     *
     * @param listener 接收对应事件的监听器；null 表示注销。
     */
    override fun setPerformanceAlarmListener(listener: RealtimePerformanceAlarmListener?) {
        synchronized(listenerLock) {
            performanceVersion++
            performanceAlarmListener = listener
        }
    }

    /**
     * 接收上下行网络质量样本。
     *
     * @param uplink 上行网络质量等级。
     * @param downlink 下行网络质量等级。
     */
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

    /**
     * 接收设备性能受限状态及建议的视频规格。
     *
     * @param limited 设备是否受到性能限制。
     * @param suggestedWidth 建议的视频宽度，单位为像素。
     * @param suggestedHeight 建议的视频高度，单位为像素。
     * @param suggestedFrameRate 建议的视频帧率，单位为 fps。
     */
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

    /**
     * 隔离质量监听器抛出的异常，保持统计与自动调档继续运行。
     *
     * @param action 需要执行的操作。
     */
    private fun protect(action: () -> Unit) {
        try { action() } catch (error: Throwable) {
            XmaxLogger.realtime.warn(
                message = { "Quality listener failed: ${ErrorMessageFormatter.format(error)}" },
            )
        }
    }

    /**
     * 将内部 RTC 质量等级转换为公共网络质量等级。
     *
     * @param level 需要应用或保存的质量档位，1 为最高档。
     * @return 对接入方提供的网络质量等级。
     */
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

    /**
     * 从性能建议中构造有效的视频规格；无效尺寸或帧率返回 null。
     *
     * @param width 画面宽度，单位为像素。
     * @param height 画面高度，单位为像素。
     * @param frameRate 视频帧率，单位为 fps。
     * @return 有效的视频规格；无法形成有效规格时为 null。
     */
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
     *
     * @param baseline 用于计算各质量档位的原始输入规格。
     * @param level 需要应用或保存的质量档位，1 为最高档。
     * @param downlink true 操作下行档位，false 操作上行档位。
     * @param policy 控制质量升降档阈值及冷却时间的策略。
     */
    internal class Direction(
        val baseline: RealtimeVideoFormat,
        level: Int,
        val downlink: Boolean,
        private val policy: QualityPolicy = QualityPolicy(),
    ) {
        /**
         * 当前生效的质量档位与传输规格。
         */
        @Volatile var level = QualityVideoFormat.initialLevel(baseline, level, downlink)
            private set
        val format: RealtimeVideoFormat get() = QualityVideoFormat.resolve(baseline, level, downlink)

        /**
         * 连续网络样本计数及最后采样时刻。
         */
        private var good = 0
        private var poor = 0
        private var lastSample: Long? = null

        /**
         * 调档稳定期、升档冷却与探测截止时间。
         */
        private var stableUntil = 0L
        private var upgradeAfter = 0L
        private var probeUntil = 0L

        /**
         * 保存自动调档的原档位、目标档位及触发原因。
         *
         * @param from 调整前的质量档位。
         * @param to 准备应用的目标质量档位。
         * @param reason 引起状态变化或失败的原因。
         */
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
         *
         * @param quality 当前网络质量样本。
         * @param sampleAt 网络质量样本的单调时钟毫秒时间。
         * @param now 当前单调时钟时间，单位为毫秒。
         * @return 建议的质量档位变化；无需或暂不能调档时为 null。
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
         *
         * @param change 准备应用的质量档位变化。
         * @param success 档位调整是否已成功应用。
         * @param now 当前单调时钟时间，单位为毫秒。
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
