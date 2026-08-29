package ai.xmax.sdk.stream.room

import ai.xmax.sdk.foundation.rtc.RtcManaging
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** 周期发送 RTC 房间心跳并隔离已经停止的旧周期。 */
internal class RoomHeartbeat(
    private val rtcManager: RtcManaging,
    private val sleeper: suspend () -> Unit = { delay(10_000L) },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val taskLock = Any()
    private val cycle = AtomicLong(0L)
    private var heartbeatTask: Job? = null

    fun start(userId: String) {
        synchronized(taskLock) {
            val version = cycle.incrementAndGet()
            heartbeatTask?.cancel()
            heartbeatTask = scope.launch {
                run(version, userId)
            }
        }
    }

    fun stop() {
        synchronized(taskLock) {
            cycle.incrementAndGet()
            heartbeatTask?.cancel()
            heartbeatTask = null
        }
    }

    private suspend fun run(
        version: Long,
        userId: String,
    ) {
        while (cycle.get() == version) {
            try {
                sleeper()
                currentCoroutineContext().ensureActive()
                if (cycle.get() != version) return
                rtcManager.sendRoomMessage(RoomEvent.heartbeat(userId))
            } catch (_: CancellationException) {
                return
            } catch (_: Throwable) {
                if (cycle.get() != version) return
            }
        }
    }
}
