package ai.xmax.sdk

/** SDK 当前支持的实时生成模型。 */
public enum class RealtimeModel(public val id: String) {
    X2_0("x2.0"),
}

/** 创建实时 Manager 所需的业务配置。 */
public data class RealtimeConfiguration(
    public val model: RealtimeModel = RealtimeModel.X2_0,
    public val isFrameInterpolationEnabled: Boolean = true,
)
