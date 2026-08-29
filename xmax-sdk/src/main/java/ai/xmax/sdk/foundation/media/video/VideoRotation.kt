package ai.xmax.sdk

/** 视频帧需要顺时针旋转的角度。 */
internal enum class VideoRotation(val degrees: Int) {
    ROTATION_0(0),
    ROTATION_90(90),
    ROTATION_180(180),
    ROTATION_270(270),
}
