package ai.xmax.sdk.stream.quality

import android.content.Context

/**
 * 按 RTC 厂商、模型及方向保存成功提交的档位；存储不可用时使用 L2。
 */
internal class QualityHistory(
    private val readValue: (String) -> Int? = { null },
    private val writeValue: (String, Int) -> Unit = { _, _ -> },
) {
    private val values = mutableMapOf<String, Int>()

    /**
     * 读取指定方向的历史档位；记录缺失或无效时返回 L2。
     */
    @Synchronized
    fun read(downlink: Boolean): Int {
        val key = if (downlink) "downlink" else "uplink"
        return values[key] ?: runCatching { readValue(key) }.getOrNull()
            ?.takeIf { it in 1..QualityTiers.percentages.size } ?: QualityTiers.INITIAL_LEVEL
    }

    /**
     * 保存指定方向已应用成功的档位。
     */
    @Synchronized
    fun write(downlink: Boolean, level: Int) {
        if (level !in 1..QualityTiers.percentages.size) return
        val key = if (downlink) "downlink" else "uplink"
        values[key] = level
        runCatching { writeValue(key, level) }
    }

    companion object {
        /**
         * 创建按 RTC 厂商与模型隔离的本地档位存储。
         */
        fun create(context: Context, model: String): QualityHistory = runCatching {
            val preferences = context.applicationContext.getSharedPreferences("xmax_quality_v1", Context.MODE_PRIVATE)
            val prefix = "agora:$model:"
            QualityHistory(
                readValue = { preferences.getInt(prefix + it, QualityTiers.INITIAL_LEVEL) },
                writeValue = { direction, level -> preferences.edit().putInt(prefix + direction, level).apply() },
            )
        }.getOrElse { QualityHistory() }
    }
}
