package ai.xmax.sdk.service.media

import ai.xmax.sdk.MediaServicing
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import androidx.compose.ui.unit.IntSize
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.round
import kotlin.math.sqrt

/** 提供模型输入尺寸和平台媒体能力相关的业务规则。 */
internal class MediaService : MediaServicing {
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
            pixels < MINIMUM_MODEL_PIXELS -> {
                scale = sqrt(MINIMUM_MODEL_PIXELS.toDouble() / pixels)
                rounding = ::ceil
            }
            pixels > MAXIMUM_MODEL_PIXELS -> {
                scale = sqrt(MAXIMUM_MODEL_PIXELS.toDouble() / pixels)
                rounding = ::floor
            }
            else -> {
                scale = 1.0
                rounding = ::round
            }
        }

        return IntSize(
            alignedDimension(width, scale, rounding),
            alignedDimension(height, scale, rounding),
        )
    }

    override fun supportsFrameInterpolation(size: IntSize): Boolean = false

    private fun alignedDimension(
        dimension: Int,
        scale: Double,
        rounding: (Double) -> Double,
    ): Int = maxOf(
        rounding(dimension * scale / MODEL_SIZE_ALIGNMENT).toInt() * MODEL_SIZE_ALIGNMENT,
        MODEL_SIZE_ALIGNMENT,
    )

    private companion object {
        const val MINIMUM_MODEL_PIXELS = 600_000L
        const val MAXIMUM_MODEL_PIXELS = 1_280_000L
        const val MODEL_SIZE_ALIGNMENT = 32
    }
}
