package ai.xmax.sdk

import androidx.compose.ui.unit.IntSize

/** SDK 支持的实时生成模型；[id] 是创建服务端会话时使用的模型标识。 */
public enum class RealtimeModel(public val id: String) {
    /** X2.0 实时生成模型。 */
    X2_0("x2.0"),

    /** X2.0 Pro 实时生成模型。 */
    X2_0_PRO("x2.0-pro"),

    ;

    /**
     * 模型支持的输入分辨率桶；非空时宽高必须精确匹配，不进行自动缩放。
     * 空列表表示不限制固定尺寸，按像素面积上下限和对齐规则计算输入尺寸。
     */
    public val resolutionBuckets: List<IntSize>
        get() = when (this) {
            X2_0 -> emptyList()
            X2_0_PRO -> listOf(
                IntSize(width = 1_024, height = 1_920),
                IntSize(width = 1_920, height = 1_024),
            )
        }

    /** 输入分辨率的最小总像素面积；仅在分辨率桶为空时参与尺寸计算。 */
    public val minimumInputPixels: Int
        get() = 600_000

    /** 输入分辨率的最大总像素面积；仅在分辨率桶为空时参与尺寸计算。 */
    public val maximumInputPixels: Int
        get() = when (this) {
            X2_0 -> 1_280_000
            X2_0_PRO -> 2_100_000
        }

    /** 输入宽度和高度分别需要对齐的像素倍数；仅在分辨率桶为空时参与尺寸计算。 */
    public val inputSizeAlignment: Int
        get() = 32

    /** 未指定视频规格时使用的默认帧率。 */
    public val defaultFrameRate: Int
        get() = when (this) {
            X2_0 -> 24
            X2_0_PRO -> 30
        }

    /** 摄像头采集使用的默认视频规格。 */
    public val defaultCameraVideoFormat: RealtimeVideoFormat
        get() = when (this) {
            X2_0 -> RealtimeVideoFormat(
                width = 832,
                height = 1_472,
                fps = defaultFrameRate,
            )
            X2_0_PRO -> RealtimeVideoFormat(
                width = 1_024,
                height = 1_920,
                fps = defaultFrameRate,
            )
        }
}

/** 创建实时 Manager 所需的业务配置。 */
public data class RealtimeConfiguration(
    /** 新建实时会话使用的模型，默认为 [RealtimeModel.X2_0]。 */
    public val model: RealtimeModel = RealtimeModel.X2_0,
)
