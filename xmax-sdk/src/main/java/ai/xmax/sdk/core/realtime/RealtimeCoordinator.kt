package ai.xmax.sdk

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 管理实时操作的准入、状态提交和终止，是生命周期状态的唯一写入方。
 *
 * [lock] 仅保护短时状态访问，不在持锁期间挂起；实际操作及回滚共用 [effects] 串行执行。
 * 终止请求先登记再取消旧操作，并在独立协程中清理，调用方取消等待不会中断资源释放。
 */
internal class RealtimeCoordinator(
    private val callbacks: RealtimeCallbacks,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val cleanup: suspend (TerminationScope) -> Unit,
) {
    /** 用于冲突检查和终止范围匹配；设置可排队，其他操作同一时刻只接纳一个。 */
    enum class OperationKind { MEDIA, CONNECTION, GENERATION, SWITCH, SETTING }
    /** 按清理范围递增排列；合并终止请求时只能扩大范围。 */
    enum class TerminationScope {
        GENERATION, CONNECTION, ALL;
        fun affects(kind: OperationKind): Boolean = when (this) {
            GENERATION -> kind == OperationKind.GENERATION || kind == OperationKind.SWITCH
            CONNECTION -> kind != OperationKind.MEDIA && kind != OperationKind.SETTING
            ALL -> true
        }
    }

    private val lock = Any()
    private val effects = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var active: Operation? = null
    private val settings = mutableSetOf<Operation>()
    private var termination: Termination? = null
    private var state = RealtimeState(RealtimeConnectionState.IDLE)
    /** 同步读取不可变快照，外部不能直接修改协调器状态。 */
    val currentState: RealtimeState get() = synchronized(lock) { state }

    /** 单次操作的所有权凭证；异步返回后检查它，阻止旧结果覆盖新生命周期。 */
    inner class Token internal constructor(private val operation: Operation) {
        /** 操作被替换或终止后按协程取消退出，避免将过期结果视为业务失败。 */
        fun ensureCurrent() = synchronized(lock) {
            if ((active !== operation && operation !in settings) || operation.invalidated) throw CancellationException("Realtime operation was superseded")
        }
        /** 仅有效操作可以提交状态；验证所有权与状态写入处于同一临界区。 */
        fun commit(next: RealtimeState) = synchronized(lock) {
            ensureCurrent()
            setState(next)
        }
        /** 登记故障清理，同时保留当前操作抛出原始故障的机会。 */
        fun fail(error: XmaxError, target: TerminationScope) {
            synchronized(lock) {
                ensureCurrent()
                operation.invalidated = true
                requestTermination(target, error, origin = operation)
            }
        }
    }

    /**
     * 接纳操作并在统一执行门内运行；冲突立即返回配置错误，最多同时接纳 16 个设置操作。
     * 调用方取消时，先等待操作及回滚结束，再释放准入资格，防止新操作复用尚未清理的资源。
     */
    suspend fun <T> run(kind: OperationKind, action: suspend (Token) -> T): T {
        currentCoroutineContext().ensureActive()
        lateinit var operation: Operation
        val task = synchronized(lock) {
            if (termination != null ||
                (kind != OperationKind.SETTING && active != null) ||
                (kind == OperationKind.SETTING && settings.size >= 16)
            ) throw XmaxError(
                XmaxErrorCode.INVALID_CONFIGURATION,
                "Another realtime operation is in progress; wait for it to finish",
            )
            operation = Operation(kind)
            val task = scope.async(start = CoroutineStart.LAZY) {
                effects.withLock {
                    currentCoroutineContext().ensureActive()
                    Token(operation).let { token -> token.ensureCurrent(); action(token) }
                }
            }
            operation.task = task
            if (kind == OperationKind.SETTING) settings += operation else active = operation
            task
        }
        task.start()
        try {
            return task.await()
        } catch (cancelled: CancellationException) {
            synchronized(lock) { operation.invalidated = true }
            task.cancel(cancelled)
            // 即使底层已产生结果，调用方也可能来不及接收；回收后才允许新操作进入。
            withContext(NonCancellable) {
                task.join()
                val terminal = synchronized(lock) {
                    termination?.task ?: if (operation.terminated) null else when (kind) {
                        OperationKind.MEDIA -> requestTermination(TerminationScope.ALL, origin = operation)
                        OperationKind.CONNECTION -> requestTermination(TerminationScope.CONNECTION, origin = operation)
                        OperationKind.GENERATION, OperationKind.SWITCH -> requestTermination(TerminationScope.GENERATION, origin = operation)
                        OperationKind.SETTING -> null
                    }
                }
                terminal?.await()
            }
            if (currentCoroutineContext().isActive) operation.fatalFailure?.let { throw it }
            throw cancelled
        } finally {
            synchronized(lock) {
                if (active === operation) active = null
                settings.remove(operation)
            }
        }
    }

    /** 合并清理请求并等待结束；仅 close 传入 clearListeners，清理后注销用户监听器。 */
    suspend fun terminate(target: TerminationScope, clearListeners: Boolean = false) {
        val task = synchronized(lock) {
            requestTermination(target, clearListeners = clearListeners)
        }
        // 等待者的取消不能传递给独立的资源清理任务。
        withContext(NonCancellable) { task.await() }
    }

    /** 非阻塞登记后台致命故障，可直接从心跳或媒体失败协程调用，避免等待自身退出。 */
    fun fatal(error: XmaxError, target: TerminationScope) = synchronized(lock) {
        if (state.connectionState == RealtimeConnectionState.ERROR && termination == null) return@synchronized
        requestTermination(target, error)
        Unit
    }

    /** 须持有 lock；复用同一个清理任务，并将并发请求升级为所需的最大清理范围。 */
    private fun requestTermination(
        target: TerminationScope,
        error: XmaxError? = null,
        clearListeners: Boolean = false,
        origin: Operation? = null,
    ): Deferred<Unit> {
        val existing = termination
        val pending = existing ?: Termination(target).also { termination = it }
        if (target > pending.target) pending.target = target
        if (pending.error == null) pending.error = error
        pending.clearListeners = pending.clearListeners || clearListeners
        if (pending.target >= TerminationScope.CONNECTION) {
            setState(state.copy(connectionState = RealtimeConnectionState.DISCONNECTING, taskId = null))
        }
        if (existing == null) {
            pending.task = scope.async(start = CoroutineStart.LAZY) {
                effects.withLock {
                    while (true) {
                        val requested = synchronized(lock) { pending.target }
                        try {
                            cleanup(requested)
                        } catch (cleanupError: Throwable) {
                            // 清理失败保留为诊断；已有原始故障时附加 suppressed，不覆盖首个故障。
                            XmaxLogger.warn({ "Realtime cleanup failed: ${ErrorMessageFormatter.format(cleanupError)}" }, "Realtime")
                            pending.error?.let { if (it !== cleanupError) it.addSuppressed(cleanupError) }
                        }
                        val done = synchronized(lock) {
                            if (pending.target != requested) false else {
                                val finalState = when {
                                    pending.clearListeners -> RealtimeState(RealtimeConnectionState.DISCONNECTED)
                                    pending.error != null -> if (requested == TerminationScope.GENERATION) {
                                        state.copy(connectionState = RealtimeConnectionState.ERROR, taskId = null)
                                    } else RealtimeState(RealtimeConnectionState.ERROR)
                                    requested >= TerminationScope.CONNECTION -> RealtimeState(RealtimeConnectionState.DISCONNECTED)
                                    state.connectionState == RealtimeConnectionState.GENERATING -> state.copy(connectionState = RealtimeConnectionState.CONNECTED, taskId = null)
                                    else -> state
                                }
                                // 本地清理完成后再派发最终状态及致命错误通知。
                                setState(finalState)
                                pending.error?.let(callbacks::error)
                                if (pending.clearListeners) callbacks.clear()
                                termination = null
                                true
                            }
                        }
                        if (done) break
                    }
                }
            }
        }
        (listOfNotNull(active) + settings).filter { pending.target.affects(it.kind) && it !== origin }.forEach {
            it.invalidated = true
            it.terminated = true
            it.fatalFailure = pending.error
            it.task.cancel(CancellationException("Realtime ${pending.target.name.lowercase()} terminated"))
        }
        pending.task.start()
        return pending.task
    }

    /** 须持有 lock；相同快照不重复通知，回调实际执行由 RealtimeCallbacks 异步派发。 */
    private fun setState(next: RealtimeState) {
        if (state == next) return
        state = next
        callbacks.state(next)
    }

    /** 失效与终止标记在 lock 内更新，task 在操作对外可见前完成赋值。 */
    internal class Operation(val kind: OperationKind) {
        lateinit var task: Deferred<*>
        var invalidated = false
        var terminated = false
        var fatalFailure: XmaxError? = null
    }
    /** 合并后的清理目标和首个故障；清理过程中收到更大目标时继续执行下一轮。 */
    private class Termination(var target: TerminationScope) {
        lateinit var task: Deferred<Unit>
        var error: XmaxError? = null
        var clearListeners = false
    }
}
