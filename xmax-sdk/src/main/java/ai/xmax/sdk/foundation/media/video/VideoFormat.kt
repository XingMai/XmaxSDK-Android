package ai.xmax.sdk

/** 表示视频帧的固定尺寸和像素格式。 */
internal data class VideoFormat(
    val width: Int,
    val height: Int,
    val pixelFormat: VideoPixelFormat,
) {
    init {
        if (width <= 0 || height <= 0) {
            throw XmaxError(
                code = XmaxErrorCode.INVALID_CONFIGURATION,
                message = "Video width and height must be positive integers",
            )
        }
    }
}
