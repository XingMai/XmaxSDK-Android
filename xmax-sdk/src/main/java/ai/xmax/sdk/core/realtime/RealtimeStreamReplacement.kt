package ai.xmax.sdk

/** 外部输入替换结果；remoteStream 仅在自动恢复生成成功时提供。 */
public data class RealtimeStreamReplacement(
    public val localStream: RealtimeMediaStream,
    public val remoteStream: RealtimeMediaStream?,
)
