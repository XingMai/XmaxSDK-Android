package ai.xmax.sdk.foundation.rtc

import io.agora.rtc2.Constants

/**
 * 声网网络质量映射；检测中及不可用状态不能误报为网络良好。
 */
internal object RtcQualityConverter {
    fun convertLevel(quality: Int): RtcQualityLevel = when (quality) {
        Constants.QUALITY_EXCELLENT -> RtcQualityLevel.EXCELLENT
        Constants.QUALITY_GOOD -> RtcQualityLevel.GOOD
        Constants.QUALITY_POOR -> RtcQualityLevel.POOR
        Constants.QUALITY_BAD -> RtcQualityLevel.BAD
        Constants.QUALITY_VBAD -> RtcQualityLevel.VERY_BAD
        Constants.QUALITY_DOWN -> RtcQualityLevel.DOWN
        else -> RtcQualityLevel.UNKNOWN
    }
}
