package ai.xmax.sdk

import android.util.Log
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** 控制 XmaxSDK 输出的日志类型。 */
public class XmaxLoggerOption(public val rawValue: Int) {
    /** 合并两组日志选项。 */
    public operator fun plus(other: XmaxLoggerOption): XmaxLoggerOption =
        XmaxLoggerOption(rawValue or other.rawValue)

    /** 当前选项是否包含指定日志类型。 */
    public operator fun contains(option: XmaxLoggerOption): Boolean =
        option.rawValue != 0 && rawValue and option.rawValue == option.rawValue

    /** 当前是否未启用任何日志。 */
    public val isEmpty: Boolean
        get() = rawValue == 0

    override fun equals(other: Any?): Boolean =
        other is XmaxLoggerOption && rawValue == other.rawValue

    override fun hashCode(): Int = rawValue

    override fun toString(): String = "XmaxLoggerOption(rawValue=$rawValue)"

    public companion object {
        /** 不输出 XmaxSDK 日志。 */
        @JvmField
        public val none: XmaxLoggerOption = XmaxLoggerOption(0)

        /** Room、API、Realtime、Storage 等业务运行日志。 */
        @JvmField
        public val business: XmaxLoggerOption = XmaxLoggerOption(1 shl 0)

        /** RTC 性能指标及性能告警日志。 */
        @JvmField
        public val performance: XmaxLoggerOption = XmaxLoggerOption(1 shl 1)

        /** 输出全部 XmaxSDK 日志。 */
        @JvmField
        public val all: XmaxLoggerOption = business + performance
    }
}

/** 统一输出带 Xmax 前缀和类别的系统日志。 */
internal class XmaxLogger private constructor(
    private val category: String,
) {

    fun debug(
        message: () -> String,
        option: XmaxLoggerOption = XmaxLoggerOption.business,
    ) {
        write(XmaxLogLevel.DEBUG, message, option)
    }

    fun info(
        message: () -> String,
        option: XmaxLoggerOption = XmaxLoggerOption.business,
    ) {
        write(XmaxLogLevel.INFO, message, option)
    }

    fun warn(
        message: () -> String,
        option: XmaxLoggerOption = XmaxLoggerOption.business,
    ) {
        write(XmaxLogLevel.WARNING, message, option)
    }

    fun error(
        message: () -> String,
        option: XmaxLoggerOption = XmaxLoggerOption.business,
    ) {
        write(XmaxLogLevel.ERROR, message, option)
    }

    fun formattedMessage(message: String): String {
        val prefix = "[Xmax][$category]"
        return message.lines().joinToString(separator = "\n", postfix = "\n") { "$prefix $it" }
    }

    private fun write(
        level: XmaxLogLevel,
        message: () -> String,
        option: XmaxLoggerOption,
    ) {
        if (!isEnabled(option)) return
        sink.get().write(level, TAG, formattedMessage(message()))
    }

    private fun isEnabled(option: XmaxLoggerOption): Boolean {
        if (option.isEmpty) return false
        return XmaxLoggerOption(options.get()).contains(option)
    }

    companion object {
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
        private val options = AtomicInteger(XmaxLoggerOption.none.rawValue)
        private val sink = AtomicReference<XmaxLogSink>(AndroidXmaxLogSink)

        fun configure(options: XmaxLoggerOption) {
            this.options.set(options.rawValue)
        }

        internal fun setSinkForTesting(value: XmaxLogSink?) {
            sink.set(value ?: AndroidXmaxLogSink)
        }
    }
}

internal enum class XmaxLogLevel {
    DEBUG,
    INFO,
    WARNING,
    ERROR,
}

internal fun interface XmaxLogSink {
    fun write(level: XmaxLogLevel, tag: String, message: String)
}

private object AndroidXmaxLogSink : XmaxLogSink {
    override fun write(level: XmaxLogLevel, tag: String, message: String) {
        when (level) {
            XmaxLogLevel.DEBUG -> Log.d(tag, message)
            XmaxLogLevel.INFO -> Log.i(tag, message)
            XmaxLogLevel.WARNING -> Log.w(tag, message)
            XmaxLogLevel.ERROR -> Log.e(tag, message)
        }
    }
}
