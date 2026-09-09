package ai.xmax.sdk.stream.encoding

import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.RealtimeVideoEncoderPreference
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import ai.xmax.sdk.foundation.rtc.RtcManaging
import ai.xmax.sdk.foundation.rtc.VideoEncodingConfiguration
import kotlin.math.round

/** 根据实时视频格式配置 RTC 视频编码参数。 */
internal class EncodingController(
    private val rtcManager: RtcManaging,
) : EncodingControlling {
    override fun configure(videoFormat: RealtimeVideoFormat) {
        videoFormat.validate()
        val minimum: Int
        val maximum: Int
        if (videoFormat.minimumBitrate != null && videoFormat.maximumBitrate != null) {
            minimum = videoFormat.minimumBitrate
            maximum = videoFormat.maximumBitrate
        } else {
            val (defaultMinimum, defaultMaximum) = resolveDefaultBitrates(videoFormat)
            minimum = videoFormat.minimumBitrate ?: defaultMinimum
            maximum = videoFormat.maximumBitrate ?: defaultMaximum
        }

        if (minimum > maximum) {
            throw XmaxError(
                code = XmaxErrorCode.INVALID_CONFIGURATION,
                message = "Minimum bitrate must not exceed maximum bitrate after applying SDK defaults",
            )
        }
        rtcManager.configureVideoEncoding(
            VideoEncodingConfiguration(
                width = videoFormat.width,
                height = videoFormat.height,
                frameRate = videoFormat.fps,
                minimumBitrate = minimum,
                maximumBitrate = maximum,
                encoderPreference = resolveEncoderPreference(videoFormat.encoderPreference),
            ),
        )
    }

    /** 根据最终上传尺寸和帧率计算默认码率上下界。 */
    private fun resolveDefaultBitrates(videoFormat: RealtimeVideoFormat): Pair<Int, Int> {
        val bitrates = resolveBitrates(
            pixels = videoFormat.width.toDouble() * videoFormat.height,
            fps = videoFormat.fps.toDouble(),
        )
        val roundedMinimum = round(bitrates.first)
        val roundedMaximum = round(bitrates.second)
        if (!roundedMaximum.isFinite() || roundedMaximum >= Int.MAX_VALUE) {
            throw XmaxError(
                code = XmaxErrorCode.INVALID_CONFIGURATION,
                message = "Realtime video format exceeds the supported bitrate range",
            )
        }
        val minimum = maxOf(1, roundedMinimum.toInt())
        return minimum to maxOf(minimum + 1, roundedMaximum.toInt())
    }

    /** 将公共编码偏好转换为 RTC 基础层使用的内部类型。 */
    private fun resolveEncoderPreference(
        preference: RealtimeVideoEncoderPreference,
    ): VideoEncodingConfiguration.EncoderPreference = when (preference) {
        RealtimeVideoEncoderPreference.AUTO ->
            VideoEncodingConfiguration.EncoderPreference.AUTO
        RealtimeVideoEncoderPreference.MAINTAIN_FRAMERATE ->
            VideoEncodingConfiguration.EncoderPreference.MAINTAIN_FRAMERATE
        RealtimeVideoEncoderPreference.MAINTAIN_QUALITY ->
            VideoEncodingConfiguration.EncoderPreference.MAINTAIN_QUALITY
    }

    private companion object {
        private data class BitratePoint(val value: Double, val bitrate: Double)

        // 火山 RTC 官方编码参数参考表，单位为 kbps。
        // 表间及表外规格采用线性插值或比例外推。
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

        private fun resolveBitrates(pixels: Double, fps: Double): Pair<Double, Double> {
            val bitrate15 = interpolate(pixels, referenceBitratesAt15Fps)
            val first30 = referenceBitratesAt30Fps.first()
            val bitrate30 = if (pixels < first30.value) {
                val reference15 = interpolate(first30.value, referenceBitratesAt15Fps)
                bitrate15 * (first30.bitrate / reference15)
            } else {
                interpolate(pixels, referenceBitratesAt30Fps)
            }
            val bitrate10 = bitrate15 * (400.0 / 500)
            val minimum60 = bitrate30 * (4780.0 / 3150)
            val maximum60 = bitrate30 * (6500.0 / 3150)
            return interpolate(
                fps,
                listOf(
                    BitratePoint(10.0, bitrate10),
                    BitratePoint(15.0, bitrate15),
                    BitratePoint(30.0, bitrate30),
                    BitratePoint(60.0, minimum60),
                ),
            ) to interpolate(
                fps,
                listOf(
                    BitratePoint(10.0, bitrate10 * 2),
                    BitratePoint(15.0, bitrate15 * 2),
                    BitratePoint(30.0, bitrate30 * 2),
                    BitratePoint(60.0, maximum60),
                ),
            )
        }

        /** 在相邻参考点间线性插值，表外按最近端点的比例外推。 */
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
}
