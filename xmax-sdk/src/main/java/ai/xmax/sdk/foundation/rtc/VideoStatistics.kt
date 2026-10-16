package ai.xmax.sdk

/**
 * 本地视频的实际发送统计；不可用指标为 null，不使用配置值代替实际值。
 */
public data class VideoStatistics(
    /**
     * 实际编码宽度，单位为像素。
     */
    public val width: Int? = null,
    /**
     * 实际编码高度，单位为像素。
     */
    public val height: Int? = null,
    /**
     * 实际发送帧率，单位为 fps。
     */
    public val frameRate: Int? = null,
    /**
     * 实际发送码率，单位为 kbps。
     */
    public val bitrateKbps: Int? = null,
    /**
     * 本端到 RTC 云端的上行视频丢包率，单位为 %。
     */
    public val uplinkLossPercent: Int? = null,
)

/**
 * 一路远端主视频流的实际接收统计；不可用指标为 null。
 */
public data class RemoteVideoStatistics(
    /**
     * 远端用户标识，与视频轨道使用同一标识。
     */
    public val userId: String,
    /**
     * 接收视频宽度，单位为像素。
     */
    public val width: Int? = null,
    /**
     * 接收视频高度，单位为像素。
     */
    public val height: Int? = null,
    /**
     * 实际渲染帧率，单位为 fps。
     */
    public val frameRate: Int? = null,
    /**
     * 实际接收码率，单位为 kbps。
     */
    public val bitrateKbps: Int? = null,
    /**
     * 下行视频丢包率，单位为 %。
     */
    public val downlinkLossPercent: Int? = null,
    /**
     * RTC 媒体端到端延迟估算值，单位为 ms；不代表完整 AI 生成耗时。
     */
    public val endToEndDelayMs: Int? = null,
)

/**
 * 主线程接收上行统计；null 表示未发布或连接已清理。
 */
public fun interface VideoStatisticsListener {
    public fun onStatistics(statistics: VideoStatistics?)
}

/**
 * 主线程接收结果流统计；null 表示当前结果流无统计或已清理。
 */
public fun interface RemoteVideoStatisticsListener {
    public fun onStatistics(statistics: RemoteVideoStatistics?)
}
