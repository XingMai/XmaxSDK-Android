package ai.xmax.sdk

import java.util.Locale
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/**
 * 与 iOS 一致地记录一次生成启动的各阶段耗时，仅通过 performance 日志输出。
 * Attempt 随调用协程传递；SEI 等外部事件捕获同一个实例，不能污染下一次启动。
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

    internal class Attempt(
        private val clock: () -> Long,
        private val log: (String) -> Unit,
    ) : AbstractCoroutineContextElement(Key) {
        private val lock = Any()
        private val startedAt = clock()
        private val times = mutableMapOf<Stage, Long>()
        private var taskId: String? = null
        private var completed = false

        fun mark(stage: Stage) = synchronized(lock) {
            if (!completed) times.putIfAbsent(stage, clock())
            Unit
        }

        fun beginSignal(taskId: String) = synchronized(lock) {
            if (!completed && this.taskId == null) {
                this.taskId = taskId
                times[Stage.SIGNAL_START] = clock()
            }
        }

        fun matchSEI(taskId: String) = markTask(taskId, Stage.SEI)

        private fun markTask(taskId: String, stage: Stage) = synchronized(lock) {
            if (!completed && this.taskId == taskId) times.putIfAbsent(stage, clock())
            Unit
        }

        fun finish(taskId: String) {
            val message = synchronized(lock) {
                if (completed || this.taskId != taskId) return
                completed = true
                successMessage(clock())
            }
            log(message)
        }

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

        fun invalidate() = synchronized(lock) { completed = true }

        private fun elapsed(start: Long?, end: Long?): Double? =
            if (start == null || end == null) null else (end - start).coerceAtLeast(0L) / 1_000_000.0

        private fun ms(value: Double): String = String.format(Locale.ROOT, "%.1f ms", value)

        private fun MutableList<String>.addDuration(label: String, start: Long?, end: Long?, minimum: Double = 0.0) {
            elapsed(start, end)?.takeIf { it >= minimum }?.let { add("$label：${ms(it)}") }
        }

        private fun successMessage(readyAt: Long): String {
            val lines = mutableListOf("实时生成启动耗时 (Realtime Generation Startup Timing)")
            val connectionStart = times[Stage.CONNECTION_START]
            val connectionEnd = times[Stage.CONNECTION_END]
            val signalStart = times[Stage.SIGNAL_START]
            if (connectionStart != null) {
                lines.addDuration("├─ 实时连接", connectionStart, connectionEnd)
                lines.addDuration(
                    "│  ├─ 服务端会话创建",
                    times[Stage.SESSION_START],
                    times[Stage.SESSION_END],
                )
                lines.addDuration("│  ├─ RTC 房间连接", times[Stage.ROOM_START], times[Stage.ROOM_END])
                elapsed(connectionStart, connectionEnd)?.let { total ->
                    val remainder = total - (elapsed(times[Stage.SESSION_START], times[Stage.SESSION_END]) ?: 0.0) -
                        (elapsed(times[Stage.ROOM_START], times[Stage.ROOM_END]) ?: 0.0)
                    lines.add("│  └─ 媒体发布与连接准备：${ms(remainder.coerceAtLeast(0.0))}")
                }
            }
            lines.addDuration("├─ 等待生成结果流确认", signalStart, times[Stage.SEI])
            lines.addDuration("└─ 结果流确认到首帧就绪", times[Stage.SEI], readyAt)
            return lines.joinToString("\n")
        }

        private fun failureMessage(failedAt: Long, error: Throwable): String {
            val lines = mutableListOf(
                "实时生成启动未完成耗时 " +
                    "(Incomplete Realtime Generation Startup Timing)",
            )
            lines.addDuration("├─ 已耗时", startedAt, failedAt)
            val pendingStage = when {
                Stage.SEI in times -> "等待首帧"
                Stage.SIGNAL_START in times -> "等待结果流确认"
                Stage.CONNECTION_END in times -> "连接后准备"
                Stage.ROOM_START in times && Stage.ROOM_END !in times -> "RTC 房间连接"
                Stage.SESSION_END in times -> "RTC 房间连接准备"
                Stage.SESSION_START in times -> "服务端会话创建"
                else -> "调用与本地准备"
            }
            lines.add("├─ 停留阶段：$pendingStage")
            lines.addDuration(
                "├─ 服务端会话创建",
                times[Stage.SESSION_START],
                times[Stage.SESSION_END] ?: failedAt,
            )
            lines.addDuration("├─ RTC 房间连接", times[Stage.ROOM_START], times[Stage.ROOM_END] ?: failedAt)
            lines.addDuration(
                "├─ 实时连接",
                times[Stage.CONNECTION_START],
                times[Stage.CONNECTION_END] ?: failedAt,
            )
            lines.addDuration(
                "├─ 等待生成结果流确认",
                times[Stage.SIGNAL_START],
                times[Stage.SEI] ?: failedAt,
            )
            lines.addDuration("├─ 结果流确认后等待首帧", times[Stage.SEI], failedAt)
            lines.add("└─ 失败原因：${ErrorMessageFormatter.format(error)}")
            return lines.joinToString("\n")
        }

        companion object Key : CoroutineContext.Key<Attempt>
    }

    enum class Stage {
        CONNECTION_START,
        SESSION_START,
        SESSION_END,
        ROOM_START,
        ROOM_END,
        CONNECTION_END,
        SIGNAL_START,
        SEI,
    }
}
