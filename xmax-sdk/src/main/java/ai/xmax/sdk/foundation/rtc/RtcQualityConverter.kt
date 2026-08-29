package ai.xmax.sdk.foundation.rtc

import com.ss.bytertc.engine.type.NetworkQuality
import com.ss.bytertc.engine.type.NetworkQualityStats
import com.ss.bytertc.engine.type.PerformanceAlarmReason

/** 在火山 RTC 质量事件和中性质量模型之间转换。 */
internal object RtcQualityConverter {
    fun convertLevel(quality: Int): RtcQualityLevel = when (quality) {
        NetworkQuality.NETWORK_QUALITY_EXCELLENT -> RtcQualityLevel.EXCELLENT
        NetworkQuality.NETWORK_QUALITY_GOOD -> RtcQualityLevel.GOOD
        NetworkQuality.NETWORK_QUALITY_POOR -> RtcQualityLevel.POOR
        NetworkQuality.NETWORK_QUALITY_BAD -> RtcQualityLevel.BAD
        NetworkQuality.NETWORK_QUALITY_VERY_BAD -> RtcQualityLevel.VERY_BAD
        NetworkQuality.NETWORK_QUALITY_DOWN -> RtcQualityLevel.DOWN
        else -> RtcQualityLevel.UNKNOWN
    }

    fun resolveDownlinkLevel(
        remoteQualities: Array<out NetworkQualityStats>,
    ): RtcQualityLevel {
        val worstQuality = remoteQualities
            .asSequence()
            .map(NetworkQualityStats::rxQuality)
            .filter {
                it in NetworkQuality.NETWORK_QUALITY_UNKNOWN..NetworkQuality.NETWORK_QUALITY_DOWN
            }
            .maxOrNull()
            ?: NetworkQuality.NETWORK_QUALITY_UNKNOWN
        return convertLevel(worstQuality)
    }

    fun resolvePerformanceLimited(reason: PerformanceAlarmReason): Boolean = when (reason) {
        PerformanceAlarmReason.BANDWIDTH_FALLBACKED,
        PerformanceAlarmReason.PERFORMANCE_FALLBACKED,
        -> true

        PerformanceAlarmReason.BANDWIDTH_RESUMED,
        PerformanceAlarmReason.PERFORMANCE_RESUMED,
        -> false
    }
}
