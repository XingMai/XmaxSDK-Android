package ai.xmax.sdk

/** 实时业务连接状态。 */
public enum class RealtimeConnectionState(public val value: String) {
    IDLE("Idle"),
    CONNECTING("Connecting"),
    CONNECTED("Connected"),
    GENERATING("Generating"),
    DISCONNECTING("Disconnecting"),
    DISCONNECTED("Disconnected"),
    ERROR("Error"),
}

/** 实时业务当前状态快照。 */
public data class RealtimeState(
    public val connectionState: RealtimeConnectionState,
    public val sessionId: String? = null,
    public val taskId: String? = null,
)

/** 实时状态监听器。 */
public fun interface RealtimeStateListener {
    public fun onStateChanged(state: RealtimeState)
}
