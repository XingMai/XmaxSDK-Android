package ai.xmax.sdk.stream.encoding

import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import kotlin.math.round

/**
 * 根据视频尺寸和帧率计算默认编码码率范围，单位为 kbps。
 */
internal object EncodingBitratePolicy {
    /**
     * 默认码率相对参考表计算结果的预算系数。
     */
    private const val BITRATE_SCALE = 0.6

    /**
     * 最低码率固定为 100 Kbps；最高码率按尺寸和帧率计算，应用预算系数后取整且不低于最低值。
     */
    fun resolve(width: Int, height: Int, fps: Int): Pair<Int, Int> {
        RealtimeVideoFormat(width, height, fps).validate()
        val maximum = resolveMaximumBitrate(
            pixels = width.toDouble() * height,
            fps = fps.toDouble(),
        )
        val roundedMaximum = round(maximum * BITRATE_SCALE)
        if (!roundedMaximum.isFinite() || roundedMaximum >= Int.MAX_VALUE) {
            throw XmaxError(
                code = XmaxErrorCode.INVALID_CONFIGURATION,
                message = "Realtime video format exceeds the supported bitrate range",
            )
        }

        return 100 to maxOf(100, roundedMaximum.toInt())
    }

    private data class BitratePoint(val value: Double, val bitrate: Double)

    /**
     * 15 fps 的像素面积与码率参考点，单位为 kbps。
     */
    private val referenceBitratesAt15Fps = listOf(
        BitratePoint(120.0 * 120, 50.0),
        BitratePoint(160.0 * 120, 65.0),
        BitratePoint(180.0 * 180, 100.0),
        BitratePoint(240.0 * 180, 120.0),
        BitratePoint(320.0 * 180, 140.0),
        BitratePoint(320.0 * 240, 200.0),
        BitratePoint(424.0 * 240, 220.0),
        BitratePoint(360.0 * 360, 260.0),
        BitratePoint(480.0 * 360, 320.0),
        BitratePoint(640.0 * 360, 400.0),
        BitratePoint(640.0 * 480, 500.0),
        BitratePoint(848.0 * 480, 610.0),
        BitratePoint(960.0 * 720, 910.0),
        BitratePoint(1280.0 * 720, 1130.0),
        BitratePoint(1920.0 * 1080, 2080.0),
    )

    private val referenceBitratesAt30Fps = listOf(
        BitratePoint(360.0 * 360, 400.0),
        BitratePoint(480.0 * 360, 490.0),
        BitratePoint(640.0 * 360, 600.0),
        BitratePoint(640.0 * 480, 750.0),
        BitratePoint(848.0 * 480, 930.0),
        BitratePoint(960.0 * 720, 1380.0),
        BitratePoint(1280.0 * 720, 1710.0),
        BitratePoint(1920.0 * 1080, 3150.0),
    )

    private fun resolveMaximumBitrate(pixels: Double, fps: Double): Double {
        val bitrate15 = interpolate(pixels, referenceBitratesAt15Fps)
        val first30 = referenceBitratesAt30Fps.first()
        val bitrate30 = if (pixels < first30.value) {
            val reference15 = interpolate(first30.value, referenceBitratesAt15Fps)
            bitrate15 * (first30.bitrate / reference15)
        } else {
            interpolate(pixels, referenceBitratesAt30Fps)
        }
        val bitrate10 = bitrate15 * (400.0 / 500)
        val maximum60 = bitrate30 * (6500.0 / 3150)
        return interpolate(
            fps,
            listOf(
                BitratePoint(10.0, bitrate10 * 2),
                BitratePoint(15.0, bitrate15 * 2),
                BitratePoint(30.0, bitrate30 * 2),
                BitratePoint(60.0, maximum60),
            ),
        )
    }

    /**
     * 在相邻参考点间线性插值，表外按最近端点的比例外推。
     */
    private fun interpolate(value: Double, points: List<BitratePoint>): Double {
        val first = points.first()
        if (value <= first.value) return first.bitrate * (value / first.value)
        points.drop(1).forEachIndexed { index, upper ->
            if (value <= upper.value) {
                val lower = points[index]
                val ratio = (value - lower.value) / (upper.value - lower.value)
                return lower.bitrate + (upper.bitrate - lower.bitrate) * ratio
            }
        }
        val last = points.last()
        return last.bitrate * (value / last.value)
    }
}
