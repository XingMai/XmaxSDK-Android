package ai.xmax.sdk

/**
 * 上行视频的实际发送统计；不可用指标为 null，不使用配置值代替实际值。
 */
public data class UplinkVideoStatistics(
    /**
     * 实际编码宽度，单位为像素。
     */
    public val width: Int? = null,

    /**
     * 实际编码高度，单位为像素。
     */
    public val height: Int? = null,

    /**
     * 实际采集帧率，单位为 fps。
     */
    public val captureFrameRate: Int? = null,

    /**
     * 实际编码帧率，单位为 fps。
     */
    public val encodeFrameRate: Int? = null,

    /**
     * 实际发送帧率，单位为 fps。
     */
    public val sendFrameRate: Int? = null,

    /**
     * 实际发送码率，单位为 kbps。
     */
    public val bitrateKbps: Int? = null,

    /**
     * 本端到 RTC 云端的上行视频丢包率，单位为 %。
     */
    public val packetLossPercent: Int? = null,
)

/**
 * 一路下行主视频流的实际接收统计；不可用指标为 null。
 */
public data class DownlinkVideoStatistics(
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
     * 实际解码帧率，单位为 fps。
     */
    public val decodeFrameRate: Int? = null,

    /**
     * 实际渲染帧率，单位为 fps。
     */
    public val renderFrameRate: Int? = null,

    /**
     * 实际接收码率，单位为 kbps。
     */
    public val bitrateKbps: Int? = null,

    /**
     * 下行视频丢包率，单位为 %。
     */
    public val packetLossPercent: Int? = null,

    /**
     * RTC 媒体端到端延迟估算值，单位为 ms；不代表完整 AI 生成耗时。
     */
    public val endToEndDelayMs: Int? = null,
)

/**
 * 主线程接收上行统计；null 表示未发布或连接已清理。
 */
public fun interface UplinkVideoStatisticsListener {
    public fun onStatistics(statistics: UplinkVideoStatistics?)
}

/**
 * 主线程接收下行结果流统计；null 表示当前结果流无统计或已清理。
 */
public fun interface DownlinkVideoStatisticsListener {
    public fun onStatistics(statistics: DownlinkVideoStatistics?)
}
