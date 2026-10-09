package ai.xmax.sdk

import androidx.compose.ui.unit.IntSize

/** SDK 支持的实时生成模型；[id] 是创建服务端会话时使用的模型标识。 */
public enum class RealtimeModel(public val id: String) {
    /** X2.0 实时生成模型。 */
    X2_0("x2.0"),

    /** X2.0 Pro 实时生成模型。 */
    X2_0_PRO("x2.0-pro"),

    /** X2.1 预览模型，输入规格与 X2.0 Pro 一致。 */
    X2_1_PREVIEW("x2.1-preview"),

    ;

    /** 界面展示名称，与创建会话时使用的 [id] 分开维护。 */
    public val displayName: String
        get() = when (this) {
            X2_0 -> "X2.0"
            X2_0_PRO -> "X2.0-pro"
            X2_1_PREVIEW -> "X2.1-preview"
        }

    /** 模型专用的会话 API 地址；null 表示沿用客户端环境地址。 */
    public val baseUrl: String?
        get() = when (this) {
            X2_0 -> null
            X2_0_PRO, X2_1_PREVIEW -> "https://dev.xmaxai.com/open/api/v1"
        }

    /**
     * 模型支持的输入分辨率桶；非空时宽高必须精确匹配，不进行自动缩放。
     * 空列表表示不限制固定尺寸，按像素面积上下限和对齐规则计算输入尺寸。
     */
    public val resolutionBuckets: List<IntSize>
        get() = when (this) {
            X2_0 -> emptyList()
            X2_0_PRO, X2_1_PREVIEW -> listOf(
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
            X2_0_PRO, X2_1_PREVIEW -> 2_100_000
        }

    /** 输入宽度和高度分别需要对齐的像素倍数；仅在分辨率桶为空时参与尺寸计算。 */
    public val inputSizeAlignment: Int
        get() = 32

    /** 未指定视频规格时使用的默认帧率。 */
    public val defaultFrameRate: Int
        get() = when (this) {
            X2_0 -> 30
            X2_0_PRO, X2_1_PREVIEW -> 30
        }

    /** 摄像头采集使用的默认视频规格。 */
    public val defaultCameraVideoFormat: RealtimeVideoFormat
        get() = when (this) {
            X2_0 -> RealtimeVideoFormat(
                width = 832,
                height = 1_472,
                fps = defaultFrameRate,
            )
            X2_0_PRO, X2_1_PREVIEW -> RealtimeVideoFormat(
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
