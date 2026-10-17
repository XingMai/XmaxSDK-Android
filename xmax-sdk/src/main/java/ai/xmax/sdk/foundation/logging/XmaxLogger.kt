//
//  XmaxLogger.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

import android.util.Log
import java.util.concurrent.atomic.AtomicReference

/**
 * 控制 XmaxSDK 输出的日志类型。
 *
 * @param rawValue 用于构造值对象的底层值。
 */
public class XmaxLoggerOption(public val rawValue: Int) {
    /**
     * 合并两组日志选项。
     *
     * @param other 用于比较或组合的另一个对象。
     * @return 组合后的日志选项。
     */
    public operator fun plus(other: XmaxLoggerOption): XmaxLoggerOption =
        XmaxLoggerOption(rawValue or other.rawValue)

    /**
     * 当前选项是否包含指定日志类型。
     *
     * @param option 控制该条日志是否输出的选项。
     * @return 包含指定选项时为 true。
     */
    public operator fun contains(option: XmaxLoggerOption): Boolean =
        option.rawValue != 0 && rawValue and option.rawValue == option.rawValue

    /**
     * 当前是否未启用任何日志。
     */
    public val isEmpty: Boolean
        get() = rawValue == 0

    /**
     * 比较对象的值是否相等。
     *
     * @param other 用于比较或组合的另一个对象。
     * @return 对象的内容相等时为 true，否则为 false。
     */
    override fun equals(other: Any?): Boolean =
        other is XmaxLoggerOption && rawValue == other.rawValue

    /**
     * 生成与值相等规则一致的哈希值。
     *
     * @return 与值相等规则一致的哈希值。
     */
    override fun hashCode(): Int = rawValue

    /**
     * 返回日志选项的位掩码描述。
     *
     * @return 适合日志或诊断显示的对象描述。
     */
    override fun toString(): String = "XmaxLoggerOption(rawValue=$rawValue)"

    /**
     * 提供 XmaxLoggerOption 使用的构造入口、共享配置或常量。
     */
    public companion object {
        /**
         * 共享常量、默认配置与工厂入口。
         *
         * 不输出 XmaxSDK 日志。
         */
        @JvmField
        public val none: XmaxLoggerOption = XmaxLoggerOption(0)

        /**
         * Room、API、Realtime、Storage 等业务运行日志。
         */
        @JvmField
        public val business: XmaxLoggerOption = XmaxLoggerOption(1 shl 0)

        /**
         * RTC 性能指标及性能告警日志。
         */
        @JvmField
        public val performance: XmaxLoggerOption = XmaxLoggerOption(1 shl 1)

        /**
         * 输出全部 XmaxSDK 日志。
         */
        @JvmField
        public val all: XmaxLoggerOption = business + performance
    }
}

/**
 * 统一输出带 Xmax 前缀和类别的系统日志。
 *
 * @param category 用于区分业务域的日志分类。
 */
internal class XmaxLogger private constructor(
    private val category: String,
) {

    /**
     * 在指定日志选项开启时输出调试级别消息。
     *
     * @param message 延迟生成日志文本的函数，仅在日志启用时求值。
     * @param option 控制该条日志是否输出的选项；默认值为 `XmaxLoggerOption.business`。
     */
    fun debug(
        message: () -> String,
        option: XmaxLoggerOption = XmaxLoggerOption.business,
    ) {
        write(XmaxLogLevel.DEBUG, message, option)
    }

    /**
     * 在指定日志选项开启时输出信息级别消息。
     *
     * @param message 延迟生成日志文本的函数，仅在日志启用时求值。
     * @param option 控制该条日志是否输出的选项；默认值为 `XmaxLoggerOption.business`。
     */
    fun info(
        message: () -> String,
        option: XmaxLoggerOption = XmaxLoggerOption.business,
    ) {
        write(XmaxLogLevel.INFO, message, option)
    }

    /**
     * 在指定日志选项开启时输出警告级别消息。
     *
     * @param message 延迟生成日志文本的函数，仅在日志启用时求值。
     * @param option 控制该条日志是否输出的选项；默认值为 `XmaxLoggerOption.business`。
     */
    fun warn(
        message: () -> String,
        option: XmaxLoggerOption = XmaxLoggerOption.business,
    ) {
        write(XmaxLogLevel.WARNING, message, option)
    }

    /**
     * 在指定日志选项开启时输出错误级别消息。
     *
     * @param message 延迟生成日志文本的函数，仅在日志启用时求值。
     * @param option 控制该条日志是否输出的选项；默认值为 `XmaxLoggerOption.business`。
     */
    fun error(
        message: () -> String,
        option: XmaxLoggerOption = XmaxLoggerOption.business,
    ) {
        write(XmaxLogLevel.ERROR, message, option)
    }

    /**
     * 生成带分类前缀的日志文本，末尾保留一行空白。
     *
     * @param message 待发送或记录的消息文本。
     * @return 带业务分类前缀及末尾换行的日志文本。
     */
    fun formattedMessage(message: String): String {
        val prefix = "[Xmax][$category]"
        return message.trimEnd('\r', '\n').lines()
            .joinToString(separator = "\n", postfix = "\n\n") { "$prefix $it" }
    }

    /**
     * 按日志级别输出带 SDK 分类前缀的消息。
     *
     * @param level 该条消息使用的日志级别。
     * @param message 延迟生成日志文本的函数，仅在日志启用时求值。
     * @param option 控制该条日志是否输出的选项。
     */
    private fun write(
        level: XmaxLogLevel,
        message: () -> String,
        option: XmaxLoggerOption,
    ) {
        if (!isEnabled(option)) return
        sink.get().write(level, TAG, formattedMessage(message()))
    }

    /**
     * 判断指定日志选项是否已启用。
     *
     * @param option 控制该条日志是否输出的选项。
     * @return 指定日志选项已开启时为 true。
     */
    private fun isEnabled(option: XmaxLoggerOption): Boolean {
        if (option.isEmpty) return false
        return XmaxLoggerOption(configuration.get().options).contains(option)
    }

    /**
     * 提供 XmaxLogger 使用的构造入口、共享配置或常量。
     */
    companion object {
        /**
         * 共享常量、默认配置与工厂入口。
         */
        val realtime = XmaxLogger("Realtime")
        val rtc = XmaxLogger("RTC")
        val media = XmaxLogger("Media")
        val api = XmaxLogger("API")
        val storage = XmaxLogger("Storage")
        val room = XmaxLogger("Room")
        val stream = XmaxLogger("Stream")
        val render = XmaxLogger("Render")
        val interaction = XmaxLogger("Interaction")
        val permission = XmaxLogger("Permission")
        val timing = XmaxLogger("Timing")

        private const val TAG = "XmaxSDK"

        /**
         * 保存一次日志配置的开关与语言环境快照。
         *
         * @param options 允许输出的日志选项。
         * @param environment 业务服务环境，同时决定日志语言。
         */
        private data class Configuration(
            val options: Int,
            val environment: XmaxEnvironment,
        )

        /**
         * 共享常量、默认配置与工厂入口。
         */
        private val configuration = AtomicReference(
            Configuration(XmaxLoggerOption.none.rawValue, XmaxEnvironment.CHINA),
        )
        private val sink = AtomicReference<XmaxLogSink>(AndroidXmaxLogSink)

        /**
         * 设置全局日志开关及输出语言。
         *
         * @param options 允许输出的日志选项。
         * @param environment 业务服务环境，同时决定日志语言；默认值为 `XmaxEnvironment.CHINA`。
         */
        fun configure(
            options: XmaxLoggerOption,
            environment: XmaxEnvironment = XmaxEnvironment.CHINA,
        ) {
            configuration.set(Configuration(options.rawValue, environment))
        }

        /**
         * 根据客户端服务环境选择日志文本。
         *
         * @param chinese 国内环境使用的中文文本。
         * @param english 海外环境使用的英文文本。
         * @return 符合当前环境的中文或英文文本。
         */
        fun localized(chinese: String, english: String): String =
            if (configuration.get().environment == XmaxEnvironment.CHINA) chinese else english

        /**
         * 替换日志输出目标；传 null 恢复 Android 日志输出。
         *
         * @param value 替代日志输出器；null 表示恢复默认输出。
         */
        internal fun setSinkForTesting(value: XmaxLogSink?) {
            sink.set(value ?: AndroidXmaxLogSink)
        }
    }
}

/**
 * SDK 日志消息的严重程度分类。
 */
internal enum class XmaxLogLevel {
    /**
     * 调试级别日志。
     */
    DEBUG,

    /**
     * 一般信息日志。
     */
    INFO,

    /**
     * 可继续运行但需要关注的警告日志。
     */
    WARNING,

    /**
     * 操作失败的错误日志。
     */
    ERROR,
}

/**
 * 接收格式化后的 SDK 日志消息的输出接口。
 */
internal fun interface XmaxLogSink {
    /**
     * 按日志级别输出带 SDK 分类前缀的消息。
     *
     * @param level 该条消息使用的日志级别。
     * @param tag Android 日志标签。
     * @param message 待发送或记录的消息文本。
     */
    fun write(level: XmaxLogLevel, tag: String, message: String)
}

/**
 * 将 SDK 日志写入 Android 系统日志。
 */
private object AndroidXmaxLogSink : XmaxLogSink {
    /**
     * 按日志级别输出带 SDK 分类前缀的消息。
     *
     * @param level 该条消息使用的日志级别。
     * @param tag Android 日志标签。
     * @param message 待发送或记录的消息文本。
     */
    override fun write(level: XmaxLogLevel, tag: String, message: String) {
        when (level) {
            XmaxLogLevel.DEBUG -> Log.d(tag, message)
            XmaxLogLevel.INFO -> Log.i(tag, message)
            XmaxLogLevel.WARNING -> Log.w(tag, message)
            XmaxLogLevel.ERROR -> Log.e(tag, message)
        }
    }
}
