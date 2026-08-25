package ai.xmax.sdk

/** Realtime generation models exposed by Xmax. */
public enum class RealtimeModel(public val id: String) {
    X2_0("x2.0"),
}

/** Options used when a realtime manager is created. */
public data class RealtimeConfiguration(
    public val model: RealtimeModel = RealtimeModel.X2_0,
)

