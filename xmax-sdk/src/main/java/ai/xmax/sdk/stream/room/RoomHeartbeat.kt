//
//  RoomHeartbeat.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.stream.room

import ai.xmax.sdk.ErrorMessageFormatter
import ai.xmax.sdk.XmaxLogger
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
import kotlinx.coroutines.cancelAndJoin

/**
 * 周期发送 RTC 房间心跳并隔离已经停止的旧周期。
 *
 * @param rtcManager 提供 RTC 采集、发布、订阅和渲染能力的管理器。
 * @param sleeper 等待下一轮心跳的挂起函数。
 * @param scope 执行后台任务的协程作用域。
 */
internal class RoomHeartbeat(
    private val rtcManager: RtcManaging,
    private val sleeper: suspend () -> Unit = { delay(10_000L) },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    /**
     * 房间心跳任务的并发保护。
     */
    private val taskLock = Any()

    /**
     * 当前心跳周期及执行任务。
     */
    private val cycle = AtomicLong(0L)
    private var heartbeatTask: Job? = null

    /**
     * 启动当前用户的房间心跳，替换此前的心跳任务。
     *
     * @param userId 房间中的业务用户标识。
     */
    fun start(userId: String) {
        synchronized(taskLock) {
            val version = cycle.incrementAndGet()
            heartbeatTask?.cancel()
            heartbeatTask = scope.launch {
                run(version, userId)
            }
        }
    }

    /**
     * 停止房间心跳并等待已启动的心跳协程退出。
     */
    suspend fun stop() {
        val job = synchronized(taskLock) {
            cycle.incrementAndGet()
            heartbeatTask.also { heartbeatTask = null }
        }
        job?.cancelAndJoin()
    }

    /**
     * 在心跳周期仍有效期间向房间发送心跳信令。
     *
     * @param version 用于识别当前任务或回调归属的版本号。
     * @param userId 房间中的业务用户标识。
     */
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
            } catch (error: Throwable) {
                if (cycle.get() != version) return
                XmaxLogger.room.error(
                    message = {
                        "发送 RTC 房间心跳失败 (Failed to Send RTC Room Heartbeat)\n" +
                            "└─ ${XmaxLogger.localized("原因：", "Reason: ")}${ErrorMessageFormatter.format(error)}"
                    },
                )
            }
        }
    }
}
