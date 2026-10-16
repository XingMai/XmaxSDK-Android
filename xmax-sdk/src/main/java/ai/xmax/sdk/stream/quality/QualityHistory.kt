package ai.xmax.sdk.stream.quality

import android.content.Context

/**
 * 按 RTC 厂商、模型及方向保存一小时内成功提交的档位；无有效记录时使用 L2。
 */
internal class QualityHistory(
    private val readValue: (String) -> Entry? = { null },
    private val writeValue: (String, Entry) -> Unit = { _, _ -> },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val values = mutableMapOf<String, Entry>()

    /**
     * 读取指定方向的历史档位；记录缺失、过期或无效时返回 L2。
     */
    @Synchronized
    fun read(downlink: Boolean): Int {
        val key = if (downlink) "downlink" else "uplink"
        val entry = values[key] ?: runCatching { readValue(key) }.getOrNull() ?: return QualityTiers.INITIAL_LEVEL
        val currentTime = now()
        if (entry.level !in 1..QualityTiers.percentages.size || entry.savedAt <= 0 || currentTime < entry.savedAt) {
            return QualityTiers.INITIAL_LEVEL
        }

        return if (currentTime - entry.savedAt < EXPIRATION_MILLIS) entry.level else QualityTiers.INITIAL_LEVEL
    }

    /**
     * 保存指定方向已应用成功的档位，并从当前时间起计算一小时有效期。
     */
    @Synchronized
    fun write(downlink: Boolean, level: Int) {
        if (level !in 1..QualityTiers.percentages.size) return
        val key = if (downlink) "downlink" else "uplink"
        val entry = Entry(level, now())
        values[key] = entry
        runCatching { writeValue(key, entry) }
    }

    /**
     * 历史档位及其保存时间，时间单位为 Unix 毫秒。
     */
    data class Entry(val level: Int, val savedAt: Long)

    companion object {
        private const val EXPIRATION_MILLIS = 60 * 60 * 1000L

        /**
         * 创建按 RTC 厂商与模型隔离的本地档位存储。
         */
        fun create(context: Context, model: String): QualityHistory = runCatching {
            val preferences = context.applicationContext.getSharedPreferences("xmax_quality_v1", Context.MODE_PRIVATE)
            val prefix = "agora:$model:"
            QualityHistory(
                readValue = { direction ->
                    Entry(
                        level = preferences.getInt(prefix + direction, QualityTiers.INITIAL_LEVEL),
                        savedAt = preferences.getLong(prefix + direction + ":saved_at", 0L),
                    )
                },
                writeValue = { direction, entry ->
                    preferences.edit()
                        .putInt(prefix + direction, entry.level)
                        .putLong(prefix + direction + ":saved_at", entry.savedAt)
                        .apply()
                },
            )
        }.getOrElse { QualityHistory() }
    }
}
