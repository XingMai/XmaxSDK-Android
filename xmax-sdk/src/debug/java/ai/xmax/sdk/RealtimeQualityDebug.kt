package ai.xmax.sdk

import ai.xmax.sdk.stream.quality.QualityVideoFormat

/**
 * Debug 构建专用的档位测试入口。
 */
public object RealtimeQualityDebug {
    /**
     * 当前请求的传输档位与规格，不代表 RTC 实际编码或接收结果。
     */
    public data class State(
        public val level: Int,
        public val format: RealtimeVideoFormat,
    )

    /**
     * 读取正在生成的指定方向档位；未生成或该方向不可用时返回 null。
     */
    public fun state(manager: XmaxRealtimeManaging, downlink: Boolean): State? {
        val direction = (manager as? XmaxRealtimeManager)?.qualityDebugDirection(downlink) ?: return null
        val level = direction.level
        return State(level, QualityVideoFormat.resolve(direction.baseline, level, downlink))
    }

    /**
     * 选择并记住 L1～L5，保留自动调档；忙碌或任务已结束时返回 false。
     */
    public suspend fun select(manager: XmaxRealtimeManaging, downlink: Boolean, level: Int): Boolean =
        (manager as? XmaxRealtimeManager)?.selectQualityDebugLevel(downlink, level) ?: false
}
