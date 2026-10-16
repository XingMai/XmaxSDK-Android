package ai.xmax.sdk.stream.quality

import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.stream.encoding.EncodingBitratePolicy
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * 从原始基准计算档位规格，保留方向、显式上传码率和编码偏好。
 */
internal object QualityVideoFormat {
    /**
     * 计算等比缩放、偶数对齐和最低短边约束后的传输规格。
     * 下行的 24 fps 仅作为码率估算依据，不设置服务端帧率。
     */
    fun resolve(baseline: RealtimeVideoFormat, level: Int, downlink: Boolean): RealtimeVideoFormat {
        require(level in 1..QualityTiers.percentages.size)
        baseline.validate()
        val shortSide = min(baseline.width, baseline.height)
        val minimum = min(shortSide, QualityTiers.MINIMUM_SHORT_SIDE)
        val scale = max(QualityTiers.percentages[level - 1] / 100.0, minimum.toDouble() / shortSide)
        fun even(value: Int): Int = max(2, (floor(value * scale / 2 + 1e-9) * 2).toInt())
        return baseline.copy(
            width = even(baseline.width),
            height = even(baseline.height),
            fps = if (downlink) 24 else min(baseline.fps, QualityTiers.frameRates[level - 1]),
        )
    }

    /**
     * 合并尺寸和帧率完全相同的档位，保留最靠近 L1 的编号。
     */
    fun canonicalLevel(baseline: RealtimeVideoFormat, level: Int, downlink: Boolean): Int {
        val format = resolve(baseline, level, downlink)
        return (1..level).first { resolve(baseline, it, downlink) == format }
    }

    /**
     * 检查候选上行规格的默认码率与显式码率约束是否兼容。
     */
    fun supportsEncoding(format: RealtimeVideoFormat): Boolean {
        val (defaultMinimum, defaultMaximum) = EncodingBitratePolicy.resolve(format.width, format.height, format.fps)
        return (format.minimumBitrate ?: defaultMinimum) <= (format.maximumBitrate ?: defaultMaximum)
    }

    /**
     * 选择不低于历史档位且满足显式码率约束的初始档位。
     */
    fun initialLevel(baseline: RealtimeVideoFormat, requested: Int, downlink: Boolean): Int {
        val level = if (downlink) requested else {
            (requested downTo 1).firstOrNull { supportsEncoding(resolve(baseline, it, false)) } ?: 1
        }
        return canonicalLevel(baseline, level, downlink)
    }
}
