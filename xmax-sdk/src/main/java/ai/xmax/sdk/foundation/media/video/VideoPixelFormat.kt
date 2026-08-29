package ai.xmax.sdk

/** 中性视频帧支持的像素格式。 */
internal enum class VideoPixelFormat(val value: String) {
    I420("i420"),
    NV12("nv12"),
    NV21("nv21"),
    RGBA("rgba"),
    BGRA("bgra"),
    ARGB("argb"),
}
