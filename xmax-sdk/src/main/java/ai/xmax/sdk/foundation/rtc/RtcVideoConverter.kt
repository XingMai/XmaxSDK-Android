package ai.xmax.sdk.foundation.rtc

import com.ss.bytertc.engine.VideoEncoderConfig

/** 在 Xmax 中性视频配置与火山 RTC 类型之间转换。 */
internal object RtcVideoConverter {
    fun makeEncoderConfiguration(
        configuration: VideoEncodingConfiguration,
    ): VideoEncoderConfig = VideoEncoderConfig().apply {
        width = configuration.width
        height = configuration.height
        frameRate = configuration.frameRate
        minBitrate = configuration.minimumBitrate
        maxBitrate = configuration.maximumBitrate
        encodePreference = VideoEncoderConfig.EncoderPreference.MAINTAIN_FRAMERATE
    }
}
