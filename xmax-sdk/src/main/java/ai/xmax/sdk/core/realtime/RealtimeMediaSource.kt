package ai.xmax.sdk

/** 实时生成模型支持的本地媒体来源。 */
public enum class RealtimeMediaSource(public val id: String) {
    /** 摄像头输入。 */
    CAMERA("camera"),

    /** 视频输入。 */
    VIDEO("video"),

    /** 图片输入。 */
    IMAGE("image"),
}
