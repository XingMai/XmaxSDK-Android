package ai.xmax.sdk

/** 实时视频的尺寸和帧率。 */
public data class RealtimeVideoFormat(
    public val width: Int,
    public val height: Int,
    public val fps: Int,
) {
    /** 校验尺寸、帧率以及 RTC 所要求的偶数分辨率。 */
    public fun validate() {
        if (width <= 0 || height <= 0 || fps <= 0 || width % 2 != 0 || height % 2 != 0) {
            throw XmaxError(
                code = XmaxErrorCode.INVALID_CONFIGURATION,
                message = "Realtime video width and height must be positive even numbers, " +
                    "and fps must be greater than zero",
            )
        }
    }
}
