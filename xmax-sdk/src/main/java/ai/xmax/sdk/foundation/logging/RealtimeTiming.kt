//
//  RealtimeTiming.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

import java.util.Locale
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/**
 * 记录生成启动的总耗时、连接阶段和首帧等待时间，并按性能日志配置输出。
 *
 * @param clockNanos 返回单调时钟纳秒数的函数。
 * @param log 接收完整日志文本的输出函数。
 */
internal class RealtimeTiming(
    private val clockNanos: () -> Long = System::nanoTime,
    private val log: (String) -> Unit = {
        XmaxLogger.timing.info(
            message = { it },
            option = XmaxLoggerOption.performance,
        )
    },
) {
    /**
     * 统计生成启动的阶段耗时，并在成功或非取消失败时输出结果。
     *
     * @param action 需要执行的操作。
     * @return 传入操作成功执行后的结果。
     */
    suspend fun <T> measure(action: suspend () -> T): T {
        val attempt = Attempt(clockNanos, log)
        return try {
            withContext(attempt) { action() }
        } catch (error: Throwable) {
            attempt.fail(error)
            throw error
        } finally {
            attempt.invalidate()
        }
    }

    /**
     * 记录单次生成任务的启动阶段及最终结果，忽略其他任务的事件。
     *
     * @param clock 返回单调时钟纳秒数的函数。
     * @param log 接收完整日志文本的输出函数。
     */
    internal class Attempt(
        private val clock: () -> Long,
        private val log: (String) -> Unit,
    ) : AbstractCoroutineContextElement(Key) {
        /**
         * 启动统计的并发保护。
         */
        private val lock = Any()

        /**
         * 本次启动的起点及已记录阶段。
         */
        private val startedAt = clock()
        private val times = mutableMapOf<Stage, Long>()

        /**
         * 统计归属的生成任务及完成标记。
         */
        private var taskId: String? = null
        private var completed = false

        /**
         * 记录阶段首次发生的时间，忽略已结束的统计。
         *
         * @param stage 需要记录的启动阶段。
         */
        fun mark(stage: Stage) = synchronized(lock) {
            if (!completed) times.putIfAbsent(stage, clock())
            Unit
        }

        /**
         * 绑定本次生成任务，并记录开始发送生成信令的时间。
         *
         * @param taskId 用于关联本次生成请求和结果流的任务标识。
         */
        fun beginSignal(taskId: String) = synchronized(lock) {
            if (!completed && this.taskId == null) {
                this.taskId = taskId
                times[Stage.SIGNAL_START] = clock()
            }
        }

        /**
         * 确认当前任务的结果流已可用，忽略其他任务的确认。
         *
         * @param taskId 用于关联本次生成请求和结果流的任务标识。
         */
        fun confirmRemoteStream(taskId: String) = markTask(taskId, Stage.STREAM_CONFIRMED)

        /**
         * 记录指定任务的阶段时间，忽略非当前任务及重复事件。
         *
         * @param taskId 用于关联本次生成请求和结果流的任务标识。
         * @param stage 需要记录的启动阶段。
         */
        private fun markTask(taskId: String, stage: Stage) = synchronized(lock) {
            if (!completed && this.taskId == taskId) times.putIfAbsent(stage, clock())
            Unit
        }

        /**
         * 输出当前任务的启动总耗时和各阶段耗时，每次启动仅输出一次。
         *
         * @param taskId 用于关联本次生成请求和结果流的任务标识。
         */
        fun finish(taskId: String) {
            val message = synchronized(lock) {
                if (completed || this.taskId != taskId) return
                completed = true
                successMessage(clock())
            }
            log(message)
        }

        /**
         * 输出启动失败时已完成的阶段及等待位置，取消操作不输出故障统计。
         *
         * @param error 本次操作失败的异常或 SDK 错误。
         */
        fun fail(error: Throwable) {
            val message = synchronized(lock) {
                if (completed) return
                completed = true
                val isCancellation = error is CancellationException ||
                    (error is XmaxError && error.code == XmaxErrorCode.CANCELLED)
                if (isCancellation) return
                failureMessage(clock(), error)
            }
            log(message)
        }

        /**
         * 结束当前统计，后续阶段事件不再写入。
         */
        fun invalidate() = synchronized(lock) { completed = true }

        /**
         * 计算两个时间点之间的非负毫秒差。
         *
         * @param start 阶段起点的单调时钟纳秒数；null 表示尚未记录。
         * @param end 阶段终点的单调时钟纳秒数；null 表示尚未记录。
         * @return 非负毫秒差；任一时间点缺失时为 null。
         */
        private fun elapsed(start: Long?, end: Long?): Double? =
            if (start == null || end == null) null else (end - start).coerceAtLeast(0L) / 1_000_000.0

        /**
         * 将毫秒数格式化为保留一位小数的耗时文本。
         *
         * @param value 需要格式化的毫秒耗时。
         * @return 保留一位小数并附带 ms 单位的文本。
         */
        private fun ms(value: Double): String = String.format(Locale.ROOT, "%.1f ms", value)

        /**
         * 根据日志环境选择中文或英文文本。
         *
         * @param chinese 国内环境使用的中文文本。
         * @param english 海外环境使用的英文文本。
         * @return 符合当前日志语言配置的文本。
         */
        private fun label(chinese: String, english: String): String = XmaxLogger.localized(chinese, english)

        /**
         * 在时间点完整且达到阈值时，将阶段耗时追加到日志行列表。
         *
         * @param title 日志条目的说明标题。
         * @param start 阶段起点的单调时钟纳秒数；null 表示尚未记录。
         * @param end 阶段终点的单调时钟纳秒数；null 表示尚未记录。
         * @param minimum 需要输出的最小阶段耗时，单位为毫秒。
         */
        private fun MutableList<String>.addDuration(title: String, start: Long?, end: Long?, minimum: Double = 0.0) {
            elapsed(start, end)?.takeIf { it >= minimum }?.let {
                add("$title${label("：", ": ")}${ms(it)}")
            }
        }

        /**
         * 生成包含总耗时、建连阶段及首帧等待时间的启动日志。
         *
         * @param readyAt 首帧就绪时的单调时钟纳秒数。
         * @return 本次生成启动成功的分行耗时文本。
         */
        private fun successMessage(readyAt: Long): String {
            val lines = mutableListOf(label("实时生成启动耗时", "Realtime Generation Startup Timing"))
            lines.addDuration("└─ ${label("总耗时", "Total")}", startedAt, readyAt)
            lines.addDuration(
                "   ├─ ${label("创建会话", "Session Creation")}",
                times[Stage.SESSION_START],
                times[Stage.SESSION_END],
            )
            lines.addDuration(
                "   ├─ ${label("RTC 进房", "RTC Room Join")}",
                times[Stage.ROOM_START],
                times[Stage.ROOM_END],
            )
            lines.addDuration(
                "   ├─ ${label("发布本地流", "Local Stream Publication")}",
                times[Stage.PUBLICATION_START],
                times[Stage.PUBLICATION_END],
            )
            lines.addDuration(
                "   └─ ${label("首帧到达", "First Frame Arrival")}",
                times[Stage.SIGNAL_START],
                readyAt,
            )
            return lines.joinToString("\n")
        }

        /**
         * 生成包含已耗时、停留阶段及错误原因的失败日志。
         *
         * @param failedAt 失败时的单调时钟纳秒数。
         * @param error 本次操作失败的异常或 SDK 错误。
         * @return 本次生成启动失败的分行诊断文本。
         */
        private fun failureMessage(failedAt: Long, error: Throwable): String {
            val lines = mutableListOf(
                "实时生成启动未完成耗时 " +
                    "(Incomplete Realtime Generation Startup Timing)",
            )
            lines.addDuration("├─ ${label("已耗时", "Elapsed")}", startedAt, failedAt)
            val pendingStage = when {
                Stage.STREAM_CONFIRMED in times -> label("等待首帧", "Waiting for First Frame")
                Stage.SIGNAL_START in times -> label("等待结果流确认", "Waiting for Result Stream")
                Stage.CONNECTION_END in times -> label("连接后准备", "Post-Connection Setup")
                Stage.ROOM_START in times && Stage.ROOM_END !in times -> label("RTC 房间连接", "RTC Room Connection")
                Stage.SESSION_END in times -> label("RTC 房间连接准备", "RTC Room Setup")
                Stage.SESSION_START in times -> label("服务端会话创建", "Server Session Creation")
                else -> label("调用与本地准备", "Call and Local Setup")
            }
            lines.add("├─ ${label("停留阶段：", "Pending Stage: ")}$pendingStage")
            lines.addDuration(
                "├─ ${label("服务端会话创建", "Server Session Creation")}",
                times[Stage.SESSION_START],
                times[Stage.SESSION_END] ?: failedAt,
            )
            lines.addDuration(
                "├─ ${label("RTC 房间连接", "RTC Room Connection")}",
                times[Stage.ROOM_START],
                times[Stage.ROOM_END] ?: failedAt,
            )
            lines.addDuration(
                "├─ ${label("实时连接", "Realtime Connection")}",
                times[Stage.CONNECTION_START],
                times[Stage.CONNECTION_END] ?: failedAt,
            )
            lines.addDuration(
                "├─ ${label("等待生成结果流确认", "Waiting for Result Stream Confirmation")}",
                times[Stage.SIGNAL_START],
                times[Stage.STREAM_CONFIRMED] ?: failedAt,
            )
            lines.addDuration(
                "├─ ${label("结果流确认后等待首帧", "Waiting for First Frame after Confirmation")}",
                times[Stage.STREAM_CONFIRMED],
                failedAt,
            )
            lines.add("└─ ${label("失败原因：", "Failure Reason: ")}${ErrorMessageFormatter.format(error)}")
            return lines.joinToString("\n")
        }

        /**
         * 在协程上下文中查找本次生成启动统计的键。
         */
        companion object Key : CoroutineContext.Key<Attempt>
    }

    /**
     * 用于区分生成启动各时间节点的阶段标识。
     */
    enum class Stage {
        /**
         * 开始建立实时连接。
         */
        CONNECTION_START,

        /**
         * 开始创建服务端会话。
         */
        SESSION_START,

        /**
         * 服务端会话创建完成。
         */
        SESSION_END,

        /**
         * 开始加入 RTC 房间。
         */
        ROOM_START,

        /**
         * RTC 进房确认完成。
         */
        ROOM_END,

        /**
         * 开始发布本地媒体流。
         */
        PUBLICATION_START,

        /**
         * 本地媒体发布调用完成。
         */
        PUBLICATION_END,

        /**
         * 实时连接及关联组件准备完成。
         */
        CONNECTION_END,

        /**
         * 开始发送生成请求。
         */
        SIGNAL_START,

        /**
         * 当前任务的结果流已确认可用。
         */
        STREAM_CONFIRMED,
    }
}
