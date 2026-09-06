package ai.xmax.sdk

/** SDK 支持的实时生成模型；[id] 是创建服务端会话时使用的模型标识。 */
public enum class RealtimeModel(public val id: String) {
    /** X2.0 实时生成模型。 */
    X2_0("x2.0"),
}

/** 创建实时 Manager 所需的业务配置。 */
public data class RealtimeConfiguration(
    /** 新建实时会话使用的模型，默认为 [RealtimeModel.X2_0]。 */
    public val model: RealtimeModel = RealtimeModel.X2_0,
)
