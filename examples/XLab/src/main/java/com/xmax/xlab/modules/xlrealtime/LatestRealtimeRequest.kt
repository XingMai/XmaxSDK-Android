package com.xmax.xlab.modules.xlrealtime

import androidx.annotation.MainThread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 主线程接纳最新选择；新请求等待旧请求连同 SDK 回滚完全退出后才开始。 */
@MainThread
internal class LatestRealtimeRequest(
    private val scope: CoroutineScope,
    private val onBusyChanged: (Boolean) -> Unit,
) {
    private var version = 0L
    private var active: Request? = null

    fun replace(action: suspend Request.() -> Unit) {
        if (!scope.isActive) return
        val previous = active
        val requestVersion = ++version
        val request = Request { version == requestVersion && scope.isActive }
        active = request
        onBusyChanged(true)
        // 立即进入等待段，避免 B 尚未执行便被 C 取消时，C 越过仍在清理的 A。
        request.job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                withContext(NonCancellable) { previous?.job?.cancelAndJoin() }
                request.ensureCurrent()
                request.action()
            } finally {
                if (active === request) {
                    active = null
                    onBusyChanged(false)
                }
            }
        }
    }

    /** 媒体切换或页面退出前调用；使晚到结果失效，并等待整条替换链释放资源。 */
    suspend fun cancelAndJoin() {
        version++
        val previous = active
        withContext(NonCancellable) { previous?.job?.cancelAndJoin() }
        if (active === previous) {
            active = null
            onBusyChanged(false)
        }
    }

    internal class Request(private val isLatest: () -> Boolean) {
        internal var job: Job? = null

        // 成功返回后仍是当前选择，供该会话后续的致命错误回调检查。
        val isCurrent: Boolean get() = isLatest()

        suspend fun ensureCurrent() {
            currentCoroutineContext().ensureActive()
            if (!isCurrent) throw CancellationException("Realtime selection was replaced")
        }
    }
}
