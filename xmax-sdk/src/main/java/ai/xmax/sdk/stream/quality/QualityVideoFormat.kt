//
//  QualityVideoFormat.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.stream.quality

import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.stream.encoding.EncodingBitratePolicy
import androidx.compose.ui.geometry.Size
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * 从原始基准计算档位规格，保留方向、显式上传码率和编码偏好。
 */
internal object QualityVideoFormat {
    /**
     * 计算下行档位的尺寸上限，保留小数精度并且不放大基准。
     *
     * @param baseline 固定媒体基准规格。
     * @param level 下行目标档位，范围为 1 到 5。
     * @return 尚未按像素对齐的下行宽高上限。
     */
    fun resolveDownlinkMaximumSize(baseline: RealtimeVideoFormat, level: Int): Size {
        val scale = sizeScale(baseline, level)
        return Size((baseline.width * scale).toFloat(), (baseline.height * scale).toFloat())
    }

    /**
     * 计算等比缩放、偶数对齐和最低短边约束后的传输规格。
     * 下行的 24 fps 仅作为码率估算依据，不设置服务端帧率。
     *
     * @param baseline 用于计算各质量档位的原始输入规格。
     * @param level 需要应用或保存的质量档位，1 为最高档。
     * @param downlink true 操作下行档位，false 操作上行档位。
     * @return 解析或计算后的视频尺寸、帧率和编码配置。
     */
    fun resolve(baseline: RealtimeVideoFormat, level: Int, downlink: Boolean): RealtimeVideoFormat {
        val scale = sizeScale(baseline, level)

        /**
         * 将像素尺寸向下对齐为偶数。
         *
         * @param value 需要向下对齐为偶数的像素尺寸。
         * @return 向下取整的偶数像素尺寸。
         */
        fun even(value: Int): Int = max(2, (floor(value * scale / 2 + 1e-9) * 2).toInt())
        return baseline.copy(
            width = even(baseline.width),
            height = even(baseline.height),
            fps = if (downlink) 24 else min(baseline.fps, QualityTiers.frameRates[level - 1]),
        )
    }

    /**
     * 计算档位对应的缩放比例，保留最低短边且不放大原始媒体。
     *
     * @param baseline 固定媒体基准规格。
     * @param level 目标档位，范围为 1 到 5。
     * @return 不超过 1 的等比缩放比例。
     */
    private fun sizeScale(baseline: RealtimeVideoFormat, level: Int): Double {
        require(level in 1..QualityTiers.percentages.size)
        baseline.validate()
        val shortSide = min(baseline.width, baseline.height)
        val minimum = min(shortSide, QualityTiers.MINIMUM_SHORT_SIDE)
        return max(QualityTiers.percentages[level - 1] / 100.0, minimum.toDouble() / shortSide)
    }

    /**
     * 合并尺寸和帧率完全相同的档位，保留最靠近 L1 的编号。
     *
     * @param baseline 用于计算各质量档位的原始输入规格。
     * @param level 需要应用或保存的质量档位，1 为最高档。
     * @param downlink true 操作下行档位，false 操作上行档位。
     * @return 生成相同规格的最高清晰度档位编号。
     */
    fun canonicalLevel(baseline: RealtimeVideoFormat, level: Int, downlink: Boolean): Int {
        val format = resolve(baseline, level, downlink)
        return (1..level).first { resolve(baseline, it, downlink) == format }
    }

    /**
     * 检查候选上行规格的默认码率与显式码率约束是否兼容。
     *
     * @param format 视频尺寸、帧率及上行编码参数。
     * @return 档位满足编码约束时为 true。
     */
    fun supportsEncoding(format: RealtimeVideoFormat): Boolean {
        val (defaultMinimum, defaultMaximum) = EncodingBitratePolicy.resolve(format.width, format.height, format.fps)
        return (format.minimumBitrate ?: defaultMinimum) <= (format.maximumBitrate ?: defaultMaximum)
    }

    /**
     * 选择不低于历史档位且满足显式码率约束的初始档位。
     *
     * @param baseline 用于计算各质量档位的原始输入规格。
     * @param requested 期望使用的质量档位。
     * @param downlink true 操作下行档位，false 操作上行档位。
     * @return 满足码率约束的初始质量档位。
     */
    fun initialLevel(baseline: RealtimeVideoFormat, requested: Int, downlink: Boolean): Int {
        val level = if (downlink) requested else {
            (requested downTo 1).firstOrNull { supportsEncoding(resolve(baseline, it, false)) } ?: 1
        }
        return canonicalLevel(baseline, level, downlink)
    }
}
