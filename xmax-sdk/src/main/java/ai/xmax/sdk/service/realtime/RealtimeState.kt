package ai.xmax.sdk

/** 实时业务连接状态。 */
public enum class RealtimeConnectionState(public val value: String) {
    IDLE("Idle"),
    PREPARING("Preparing"),
    READY("Ready"),
    CONNECTING("Connecting"),
    CONNECTED("Connected"),
    GENERATING("Generating"),
    DISCONNECTING("Disconnecting"),
}

/** 进入当前实时状态的原因。 */
public sealed interface RealtimeReason {
    /** 主动停止或正常释放资源。 */
    public data object Normal : RealtimeReason

    /** 显示方向变化，需要重新配置生成。 */
    public data object OrientationChanged : RealtimeReason

    /** 操作或运行异常导致当前流程结束。 */
    public data class Failure(public val error: XmaxError) : RealtimeReason
}

/** 实时业务当前状态快照。 */
public data class RealtimeState(
    public val connectionState: RealtimeConnectionState,
    public val sessionId: String? = null,
    public val taskId: String? = null,
    /** 进入当前状态的原因；开始新操作时清空。 */
    public val reason: RealtimeReason? = null,
)

/** 实时状态监听器。 */
public fun interface RealtimeStateListener {
    public fun onStateChanged(state: RealtimeState)
}
