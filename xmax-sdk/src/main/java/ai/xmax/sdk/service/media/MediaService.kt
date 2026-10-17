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
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import androidx.compose.ui.unit.IntSize
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.round
import kotlin.math.sqrt
import kotlin.math.abs
import kotlin.math.ln

/**
 * 提供模型输入尺寸相关的业务规则。
 *
 * @param model 用于确定服务端能力、地址及输入规格的模型；默认值为 `RealtimeModel.X2_1_1_PREVIEW_CN`。
 */
internal class MediaService(
    override val model: RealtimeModel = RealtimeModel.X2_1_1_PREVIEW_CN,
) : MediaServicing {
    /**
     * 为保留媒体比例的实时流选择比例最接近的模型生成画布。
     *
     * @param inputSize 媒体输入的显示尺寸，单位为像素。
     * @return 满足当前计算约束的视频宽度和高度。
     */
    fun resolveGenerationSize(inputSize: IntSize): IntSize {
        if (inputSize.width <= 0 || inputSize.height <= 0) {
            throw XmaxError(XmaxErrorCode.INVALID_CONFIGURATION, "Media input dimensions must be positive")
        }

        val aspect = inputSize.width.toDouble() / inputSize.height
        return model.resolutionBuckets.minByOrNull { bucket ->
            abs(ln(aspect / (bucket.width.toDouble() / bucket.height)))
        } ?: resolveModelInputSize(inputSize)
    }

    /**
     * 计算满足模型输入约束的尺寸。
     *
     * 分辨率桶非空时，精确匹配后原样返回；为空时按像素面积上下限及对齐要求计算。
     *
     * @param size 媒体画面尺寸，单位为像素。
     * @return 满足当前计算约束的视频宽度和高度。
     */
    override fun resolveModelInputSize(size: IntSize): IntSize {
        val buckets = model.resolutionBuckets
        if (buckets.isNotEmpty()) {
            if (size !in buckets) {
                val supportedSizes = buckets.joinToString { "${it.width}×${it.height}" }
                throw XmaxError(
                    code = XmaxErrorCode.INVALID_CONFIGURATION,
                    message = "Model ${model.id} does not support input resolution " +
                        "${size.width}×${size.height}. Supported resolutions: $supportedSizes",
                )
            }

            return size
        }

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
