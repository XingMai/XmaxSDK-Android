//
//  EncodingController.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.stream.encoding

import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.RealtimeVideoEncoderPreference
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import ai.xmax.sdk.foundation.rtc.RtcManaging
import ai.xmax.sdk.foundation.rtc.VideoEncodingConfiguration

/**
 * 根据实时视频格式配置 RTC 视频编码参数。
 *
 * @param rtcManager 提供 RTC 采集、发布、订阅和渲染能力的管理器。
 */
internal class EncodingController(
    private val rtcManager: RtcManaging,
) : EncodingControlling {
    /**
     * 校验并应用实时视频编码格式。
     *
     * @param videoFormat 视频尺寸、帧率及上行编码参数。
     */
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
     *
     * @param preference 编码时优先保帧率或保画质的策略。
     * @return 与公共配置对应的内部编码偏好。
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
