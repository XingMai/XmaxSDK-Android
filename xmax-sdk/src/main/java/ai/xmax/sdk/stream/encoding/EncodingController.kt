package ai.xmax.sdk.stream.encoding

import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.RealtimeVideoEncoderPreference
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import ai.xmax.sdk.foundation.rtc.RtcManaging
import ai.xmax.sdk.foundation.rtc.VideoEncodingConfiguration

/**
 * 根据实时视频格式配置 RTC 视频编码参数。
 */
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
            val (defaultMinimum, defaultMaximum) = EncodingBitratePolicy.resolve(
                videoFormat.width,
                videoFormat.height,
                videoFormat.fps,
            )
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

    /**
     * 将公共编码偏好转换为 RTC 基础层使用的内部类型。
     */
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
}
