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

/** 提供模型输入尺寸相关的业务规则。 */
internal class MediaService(
    override val model: RealtimeModel = RealtimeModel.X2_0,
) : MediaServicing {
    override fun resolveModelInputSize(size: IntSize): IntSize {
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

        // 分别对齐可能使边界附近的面积越界；选择满足限制且最接近目标的尺寸。
        return boundedAlignedSize(
            width = size.width * scale,
            height = size.height * scale,
        )
    }

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

    private fun alignedDimension(
        dimension: Int,
        scale: Double,
        rounding: (Double) -> Double,
    ): Int = maxOf(
        rounding(dimension * scale / model.inputSizeAlignment).toInt() * model.inputSizeAlignment,
        model.inputSizeAlignment,
    )
}
