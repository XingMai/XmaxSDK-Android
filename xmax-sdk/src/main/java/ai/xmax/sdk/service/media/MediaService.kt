//
//  MediaService.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.service.media

import ai.xmax.sdk.MediaServicing
import ai.xmax.sdk.RealtimeModel
import ai.xmax.sdk.RealtimeMediaSource
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.geometry.Size
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.round
import kotlin.math.sqrt
import kotlin.math.abs
import kotlin.math.ln

/**
 * 统一解析本地媒体、模型生成画布和回传尺寸。
 *
 * @param model 用于确定服务端能力、地址及输入规格的模型；默认值为 `RealtimeModel.X2_1_1_PREVIEW_CN`。
 */
internal class MediaService(
    override val model: RealtimeModel = RealtimeModel.X2_1_1_PREVIEW_CN,
) : MediaServicing {
    /**
     * 为保留媒体比例的实时流选择比例最接近的模型生成画布。
     *
     * @param size 媒体输入的显示尺寸，单位为像素。
     * @return 比例最接近的模型画布尺寸。
     */
    override fun resolveGenerationSize(size: IntSize): IntSize {
        if (size.width <= 0 || size.height <= 0) {
            throw XmaxError(XmaxErrorCode.INVALID_CONFIGURATION, "Media input dimensions must be positive")
        }

        val aspect = size.width.toDouble() / size.height
        return model.resolutionBuckets.minByOrNull { bucket ->
            abs(ln(aspect / (bucket.width.toDouble() / bucket.height)))
        } ?: resolveGenerationCanvas(size)
    }

    /**
     * 校验摄像头分辨率桶并计算不放大、受模型面积上限约束的 4 对齐媒体尺寸。
     *
     * @param size 媒体画面尺寸，单位为像素。
     * @param source 媒体来源；只有摄像头需要精确匹配分辨率桶。
     * @return 优先保持精确比例的媒体宽高。
     */
    override fun resolveMediaSize(size: IntSize, source: RealtimeMediaSource): IntSize {
        if (size.width <= 0 || size.height <= 0) {
            throw XmaxError(XmaxErrorCode.INVALID_CONFIGURATION, "Media dimensions must be positive")
        }

        val buckets = model.resolutionBuckets
        if (source == RealtimeMediaSource.CAMERA && buckets.isNotEmpty()) {
            if (size !in buckets) {
                val supportedSizes = buckets.joinToString { "${it.width}×${it.height}" }
                throw XmaxError(
                    code = XmaxErrorCode.INVALID_CONFIGURATION,
                    message = "Model ${model.id} does not support camera resolution " +
                        "${size.width}×${size.height}. Supported resolutions: $supportedSizes",
                )
            }
        }

        val pixels = size.width.toDouble() * size.height
        val scale = minOf(1.0, sqrt(model.maximumInputPixels / pixels))
        return alignedSize(size, scale)
    }

    /**
     * 按下行宽高上限计算不放大、4 对齐的最终回传尺寸。
     *
     * @param sourceSize 固定媒体基准尺寸，单位为像素。
     * @param maximumSize 下行宽高上限，可包含小数像素。
     * @return 优先保持精确比例且不超过上限的回传尺寸。
     */
    override fun resolveTargetSize(sourceSize: IntSize, maximumSize: Size): IntSize {
        if (sourceSize.width <= 0 || sourceSize.height <= 0 || !maximumSize.width.isFinite() || !maximumSize.height.isFinite() || maximumSize.width <= 0f || maximumSize.height <= 0f) {
            throw XmaxError(XmaxErrorCode.INVALID_CONFIGURATION, "Source dimensions and target bounds must be positive and finite")
        }

        val scale = minOf(1.0, maximumSize.width.toDouble() / sourceSize.width, maximumSize.height.toDouble() / sourceSize.height)
        return alignedSize(sourceSize, scale)
    }

    /**
     * 以最简宽高比的整数倍计算 4 对齐尺寸；无法精确等比时分别向下对齐。
     *
     * @param size 原始媒体整数像素尺寸。
     * @param scale 不超过 1 的等比缩放比例。
     * @return 不超过缩放边界的 4 对齐尺寸。
     */
    private fun alignedSize(size: IntSize, scale: Double): IntSize {
        var divisor = size.width
        var remainder = size.height
        while (remainder != 0) {
            val next = divisor % remainder
            divisor = remainder
            remainder = next
        }
        val alignedScale = floor(divisor * scale / 4).toInt() * 4
        if (alignedScale >= 4) {
            return IntSize(size.width / divisor * alignedScale, size.height / divisor * alignedScale)
        }

        val width = floor(size.width * scale / 4).toInt() * 4
        val height = floor(size.height * scale / 4).toInt() * 4
        if (width < 4 || height < 4) {
            throw XmaxError(XmaxErrorCode.INVALID_CONFIGURATION, "No aligned video size is available within the size limits")
        }

        return IntSize(width, height)
    }

    /**
     * 在模型没有固定分辨率桶时计算满足面积范围和对齐要求的生成画布。
     *
     * @param size 媒体输入尺寸，单位为像素。
     * @return 满足模型面积上下限的生成画布尺寸。
     */
    private fun resolveGenerationCanvas(size: IntSize): IntSize {
        val width = size.width
        val height = size.height
        if (width <= 0 || height <= 0) {
            throw XmaxError(
                code = XmaxErrorCode.INVALID_CONFIGURATION,
                message = "Image width and height must be greater than zero",
            )
        }

        val pixels = width.toLong() * height.toLong()
        val scale: Double
        val rounding: (Double) -> Double
        when {
            pixels < model.minimumInputPixels -> {
                scale = sqrt(model.minimumInputPixels.toDouble() / pixels)
                rounding = ::ceil
            }
            pixels > model.maximumInputPixels -> {
                scale = sqrt(model.maximumInputPixels.toDouble() / pixels)
                rounding = ::floor
            }
            else -> {
                scale = 1.0
                rounding = ::round
            }
        }

        val alignedWidth = alignedDimension(width, scale, rounding)
        val alignedHeight = alignedDimension(height, scale, rounding)
        val alignedPixels = alignedWidth.toLong() * alignedHeight.toLong()
        if (alignedPixels in model.minimumInputPixels.toLong()..model.maximumInputPixels.toLong()) {
            return IntSize(alignedWidth, alignedHeight)
        }

        /**
         * 分别对齐可能使边界附近的面积越界；选择满足限制且最接近目标的尺寸。
         */
        return boundedAlignedSize(
            width = size.width * scale,
            height = size.height * scale,
        )
    }

    /**
     * 计算满足像素面积范围及模型对齐要求的尺寸。
     *
     * @param width 画面宽度，单位为像素。
     * @param height 画面高度，单位为像素。
     * @return 满足当前计算约束的视频宽度和高度。
     */
    private fun boundedAlignedSize(width: Double, height: Double): IntSize {
        val alignment = model.inputSizeAlignment
        val unitPixels = alignment.toLong() * alignment
        val minimumUnits = (model.minimumInputPixels.toLong() + unitPixels - 1L) / unitPixels
        val maximumUnits = model.maximumInputPixels.toLong() / unitPixels
        var bestSize = IntSize.Zero
        var bestDistance = Double.POSITIVE_INFINITY

        for (widthUnits in 1L..maximumUnits) {
            val minimumHeight = (minimumUnits + widthUnits - 1L) / widthUnits
            val maximumHeight = maximumUnits / widthUnits
            if (minimumHeight > maximumHeight) continue
            val preferredHeight = round(height / alignment).toLong()
            val heightUnits = preferredHeight.coerceIn(minimumHeight, maximumHeight)
            val candidateWidth = widthUnits * alignment
            val candidateHeight = heightUnits * alignment
            val widthDistance = (candidateWidth - width) / width
            val heightDistance = (candidateHeight - height) / height
            val distance = widthDistance * widthDistance + heightDistance * heightDistance
            if (distance < bestDistance) {
                bestDistance = distance
                bestSize = IntSize(candidateWidth.toInt(), candidateHeight.toInt())
            }
        }
        return bestSize
    }

    /**
     * 按比例和取整规则计算符合模型对齐要求的单边尺寸。
     *
     * @param dimension 需要缩放的单边像素尺寸。
     * @param scale 相对于基准尺寸的缩放比例。
     * @param rounding 对缩放结果取整的函数。
     * @return 符合模型对齐倍数的像素尺寸。
     */
    private fun alignedDimension(
        dimension: Int,
        scale: Double,
        rounding: (Double) -> Double,
    ): Int = maxOf(
        rounding(dimension * scale / model.inputSizeAlignment).toInt() * model.inputSizeAlignment,
        model.inputSizeAlignment,
    )
}
