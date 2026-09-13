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

        private fun label(chinese: String, english: String): String = XmaxLogger.localized(chinese, english)

        private fun MutableList<String>.addDuration(title: String, start: Long?, end: Long?, minimum: Double = 0.0) {
            elapsed(start, end)?.takeIf { it >= minimum }?.let {
                add("$title${label("：", ": ")}${ms(it)}")
            }
        }

        private fun successMessage(readyAt: Long): String {
            val lines = mutableListOf("实时生成启动耗时 (Realtime Generation Startup Timing)")
            val connectionStart = times[Stage.CONNECTION_START]
            val connectionEnd = times[Stage.CONNECTION_END]
            val signalStart = times[Stage.SIGNAL_START]
            if (connectionStart != null) {
                lines.addDuration("├─ ${label("实时连接", "Realtime Connection")}", connectionStart, connectionEnd)
                lines.addDuration(
                    "│  ├─ ${label("服务端会话创建", "Server Session Creation")}",
                    times[Stage.SESSION_START],
                    times[Stage.SESSION_END],
                )
                lines.addDuration(
                    "│  ├─ ${label("RTC 房间连接", "RTC Room Connection")}",
                    times[Stage.ROOM_START],
                    times[Stage.ROOM_END],
                )
                elapsed(connectionStart, connectionEnd)?.let { total ->
                    val remainder = total - (elapsed(times[Stage.SESSION_START], times[Stage.SESSION_END]) ?: 0.0) -
                        (elapsed(times[Stage.ROOM_START], times[Stage.ROOM_END]) ?: 0.0)
                    lines.add(
                        "│  └─ ${label("媒体发布与连接准备：", "Media Publication and Connection Setup: ")}" +
                            ms(remainder.coerceAtLeast(0.0)),
                    )
                }
            }
            lines.addDuration(
                "├─ ${label("等待生成结果流确认", "Waiting for Result Stream Confirmation")}",
                signalStart,
                times[Stage.SEI],
            )
            lines.addDuration(
                "└─ ${label("结果流确认到首帧就绪", "Result Confirmation to First Frame")}",
                times[Stage.SEI],
                readyAt,
            )
            return lines.joinToString("\n")
        }

        private fun failureMessage(failedAt: Long, error: Throwable): String {
            val lines = mutableListOf(
                "实时生成启动未完成耗时 " +
                    "(Incomplete Realtime Generation Startup Timing)",
            )
            lines.addDuration("├─ ${label("已耗时", "Elapsed")}", startedAt, failedAt)
            val pendingStage = when {
                Stage.SEI in times -> label("等待首帧", "Waiting for First Frame")
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
                times[Stage.SEI] ?: failedAt,
            )
            lines.addDuration(
                "├─ ${label("结果流确认后等待首帧", "Waiting for First Frame after Confirmation")}",
                times[Stage.SEI],
                failedAt,
            )
            lines.add("└─ ${label("失败原因：", "Failure Reason: ")}${ErrorMessageFormatter.format(error)}")
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
