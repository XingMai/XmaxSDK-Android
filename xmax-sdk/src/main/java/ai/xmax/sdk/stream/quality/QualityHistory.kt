//
//  QualityHistory.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.stream.quality

import android.content.Context

/**
 * 按 RTC 厂商、模型及方向保存一小时内成功提交的档位；无有效记录时使用 L2。
 *
 * @param readValue 根据方向键读取历史档位的函数。
 * @param writeValue 保存方向键对应的历史档位和时间的函数。
 * @param now 返回 Unix 毫秒时间的函数，用于判断历史记录是否过期。
 */
internal class QualityHistory(
    private val readValue: (String) -> Entry? = { null },
    private val writeValue: (String, Entry) -> Unit = { _, _ -> },
    private val now: () -> Long = System::currentTimeMillis,
) {
    /**
     * 按上下行方向保存的历史档位缓存。
     */
    private val values = mutableMapOf<String, Entry>()

    /**
     * 读取指定方向的历史档位；记录缺失、过期或无效时返回 L2。
     *
     * @param downlink true 操作下行档位，false 操作上行档位。
     * @return 有效历史档位；记录缺失或过期时为 L2。
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
     *
     * @param downlink true 操作下行档位，false 操作上行档位。
     * @param level 需要应用或保存的质量档位，1 为最高档。
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
     *
     * @param level 需要应用或保存的质量档位，1 为最高档。
     * @param savedAt 成功保存档位时的 Unix 毫秒时间。
     */
    data class Entry(val level: Int, val savedAt: Long)

    /**
     * 提供 QualityHistory 使用的构造入口、共享配置或常量。
     */
    companion object {
        /**
         * 共享常量、默认配置与工厂入口。
         */
        private const val EXPIRATION_MILLIS = 60 * 60 * 1000L

        /**
         * 创建按 RTC 厂商与模型隔离的本地档位存储。
         *
         * @param context 访问 Android 系统服务或媒体资源所需的上下文。
         * @param model 用于确定服务端能力、地址及输入规格的模型。
         * @return 按模型和上下行方向隔离的历史档位存储。
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
