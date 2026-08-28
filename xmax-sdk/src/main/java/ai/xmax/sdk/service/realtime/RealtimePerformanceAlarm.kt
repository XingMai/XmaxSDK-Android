package ai.xmax.sdk

/** 实时性能限制状态。 */
public enum class RealtimePerformanceStatus(public val value: String) {
    LIMITED("Limited"),
    RECOVERED("Recovered"),
}

/** 实时性能告警信息。 */
public data class RealtimePerformanceAlarm(
    public val status: RealtimePerformanceStatus,
    public val suggestedVideoFormat: RealtimeVideoFormat?,
)

/** 实时性能告警监听器。 */
public fun interface RealtimePerformanceAlarmListener {
    public fun onPerformanceAlarm(alarm: RealtimePerformanceAlarm)
}
