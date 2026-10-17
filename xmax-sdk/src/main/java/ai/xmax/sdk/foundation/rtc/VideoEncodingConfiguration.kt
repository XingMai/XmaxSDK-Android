//
//  VideoEncodingConfiguration.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.foundation.rtc

/**
 * RTC 主视频流编码参数。
 *
 * @param width 画面宽度，单位为像素。
 * @param height 画面高度，单位为像素。
 * @param frameRate 视频帧率，单位为 fps。
 * @param minimumBitrate 最低编码码率，单位为 kbps；默认值为 `0`。
 * @param maximumBitrate 最高编码码率，单位为 kbps。
 * @param encoderPreference 带宽不足时优先保帧率或保画质的编码策略；默认值为 `EncoderPreference.MAINTAIN_FRAMERATE`。
 */
internal data class VideoEncodingConfiguration(
    val width: Int,
    val height: Int,
    val frameRate: Int,
    val minimumBitrate: Int = 0,
    val maximumBitrate: Int = -1,
    val encoderPreference: EncoderPreference = EncoderPreference.MAINTAIN_FRAMERATE,
) {
    /**
     * 网络受限时上行编码保帧率或保画质的偏好。
     */
    enum class EncoderPreference {
        /**
         * 由 RTC 自动选择编码质量与帧率的取舍。
         */
        AUTO,

        /**
         * 带宽受限时优先维持视频帧率。
         */
        MAINTAIN_FRAMERATE,

        /**
         * 带宽受限时优先维持单帧画质。
         */
        MAINTAIN_QUALITY,
    }
}
