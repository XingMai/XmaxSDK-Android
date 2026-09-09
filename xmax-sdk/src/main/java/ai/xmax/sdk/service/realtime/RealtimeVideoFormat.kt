package ai.xmax.sdk

/** 实时视频的编码策略偏好。 */
public enum class RealtimeVideoEncoderPreference {
    /** 平衡帧率和分辨率。 */
    AUTO,

    /** 优先保障帧率。 */
    MAINTAIN_FRAMERATE,

    /** 优先保障分辨率。 */
    MAINTAIN_QUALITY,
}

/** 实时视频的尺寸、帧率和上传编码配置。 */
public data class RealtimeVideoFormat @JvmOverloads constructor(
    /** 视频宽度，单位为像素。 */
    public val width: Int,

    /** 视频高度，单位为像素。 */
    public val height: Int,

    /** 视频帧率，单位为 fps。 */
    public val fps: Int,

    /** 最低上传码率，单位为 kbps；null 使用 SDK 默认值，0 表示不设最低码率。 */
    public val minimumBitrate: Int? = null,

    /** 最高上传码率，单位为 kbps；null 使用 SDK 默认值，指定时必须大于 0。 */
    public val maximumBitrate: Int? = null,

    /** 上传编码策略偏好。 */
    public val encoderPreference: RealtimeVideoEncoderPreference =
        RealtimeVideoEncoderPreference.AUTO,
) {
    /** 校验尺寸、帧率和显式指定的码率范围。 */
    public fun validate() {
        if (width <= 0 || height <= 0 || fps <= 0 || width % 2 != 0 || height % 2 != 0) {
            throw XmaxError(
                code = XmaxErrorCode.INVALID_CONFIGURATION,
                message = "Realtime video width and height must be positive even numbers, " +
                    "and fps must be greater than zero",
            )
        }

        if (minimumBitrate != null && minimumBitrate < 0) {
            throw XmaxError(
                code = XmaxErrorCode.INVALID_CONFIGURATION,
                message = "Minimum bitrate must not be negative",
            )
        }

        if (maximumBitrate != null && maximumBitrate <= 0) {
            throw XmaxError(
                code = XmaxErrorCode.INVALID_CONFIGURATION,
                message = "Maximum bitrate must be greater than zero",
            )
        }

        if (minimumBitrate != null && maximumBitrate != null && minimumBitrate > maximumBitrate) {
            throw XmaxError(
                code = XmaxErrorCode.INVALID_CONFIGURATION,
                message = "Minimum bitrate must not exceed maximum bitrate",
            )
        }
    }
}
