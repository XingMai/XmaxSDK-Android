package ai.xmax.sdk

/** 服务端读取参考视频时的采样方式。 */
public enum class RealtimeVideoSampleMethod(internal val value: String) {
    TIME("time"),
    FPS("fps"),
}

/** 可由服务端直接访问的参考视频。 */
public data class RealtimeReferenceVideo(
    public val path: String,
    public val sampleMethod: RealtimeVideoSampleMethod = RealtimeVideoSampleMethod.TIME,
)
