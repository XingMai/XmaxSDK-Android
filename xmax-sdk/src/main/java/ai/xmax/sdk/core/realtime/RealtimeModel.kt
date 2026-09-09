package ai.xmax.sdk

/** SDK 支持的实时生成模型；[id] 是创建服务端会话时使用的模型标识。 */
public enum class RealtimeModel(public val id: String) {
    /** X2.0 实时生成模型。 */
    X2_0("x2.0"),

    ;

    /** 模型支持的本地媒体来源。 */
    public val supportedMediaSources: Set<RealtimeMediaSource>
        get() = when (this) {
            X2_0 -> setOf(
                RealtimeMediaSource.CAMERA,
                RealtimeMediaSource.VIDEO,
                RealtimeMediaSource.IMAGE,
            )
        }

    /** 输入分辨率的最小总像素面积。 */
    public val minimumInputPixels: Int
        get() = 600_000

    /** 输入分辨率的最大总像素面积。 */
    public val maximumInputPixels: Int
        get() = when (this) {
            X2_0 -> 1_280_000
        }

    /** 输入宽度和高度分别需要对齐的像素倍数。 */
    public val inputSizeAlignment: Int
        get() = 32

    /** 未指定视频规格时使用的默认帧率。 */
    public val defaultFrameRate: Int
        get() = when (this) {
            X2_0 -> 24
        }

    /** 摄像头采集使用的默认视频规格。 */
    public val defaultCameraVideoFormat: RealtimeVideoFormat
        get() = when (this) {
            X2_0 -> RealtimeVideoFormat(width = 832, height = 1472, fps = defaultFrameRate)
        }
}

/** 创建实时 Manager 所需的业务配置。 */
public data class RealtimeConfiguration(
    /** 新建实时会话使用的模型，默认为 [RealtimeModel.X2_0]。 */
    public val model: RealtimeModel = RealtimeModel.X2_0,
)
