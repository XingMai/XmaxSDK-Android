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
    enum class OperationKind { MEDIA, CONNECTION, GENERATION, SWITCH, CONFIGURATION, SETTING }
    /** 按清理范围递增排列；合并终止请求时只能扩大范围。 */
    enum class TerminationScope {
        GENERATION, CONNECTION, ALL;
        fun affects(kind: OperationKind): Boolean = when (this) {
            GENERATION -> kind == OperationKind.GENERATION || kind == OperationKind.SWITCH ||
                kind == OperationKind.CONFIGURATION
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
        /** 连接建立后将后续故障的清理范围收窄到当前生成任务。 */
        fun setFailureScope(scope: TerminationScope) = synchronized(lock) {
            ensureCurrent()
            operation.failureScope = scope
        }
        /** 登记故障清理，同时保留当前操作抛出原始故障的机会。 */
        fun fail(error: XmaxError) {
            synchronized(lock) {
                ensureCurrent()
                operation.invalidated = true
                operation.failureScope?.let { requestTermination(it, error, origin = operation) }
            }
        }
    }

    /**
     * 接纳操作并在统一执行门内运行；冲突立即返回配置错误，最多同时接纳 16 个设置操作。
     * 调用方取消时，先等待操作及回滚结束，再释放准入资格，防止新操作复用尚未清理的资源。
     */
    suspend fun <T> run(
        kind: OperationKind,
        failureScope: TerminationScope? = defaultFailureScope(kind),
        action: suspend (Token) -> T,
    ): T {
        currentCoroutineContext().ensureActive()
        lateinit var operation: Operation
        val task = synchronized(lock) {
            // 已在生成时，startGeneration 只更新条件，不再代表一轮生成生命周期。
            val operationKind = if (kind == OperationKind.GENERATION &&
                state.connectionState == RealtimeConnectionState.GENERATING
            ) OperationKind.CONFIGURATION else kind
            if (termination != null ||
                (operationKind != OperationKind.SETTING && active != null) ||
                (operationKind == OperationKind.SETTING && settings.size >= 16)
            ) throw XmaxError(
                XmaxErrorCode.INVALID_CONFIGURATION,
                "Another realtime operation is in progress; wait for it to finish",
            )
            operation = Operation(operationKind, failureScope)
            val task = scope.async(start = CoroutineStart.LAZY) {
                effects.withLock {
                    currentCoroutineContext().ensureActive()
                    Token(operation).let { token -> token.ensureCurrent(); action(token) }
                }
            }
            operation.task = task
            if (operationKind == OperationKind.SETTING) settings += operation else active = operation
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
                    termination?.task ?: if (operation.terminated ||
                        operation.kind == OperationKind.CONFIGURATION ||
                        operation.kind == OperationKind.SETTING
                    ) null else operation.failureScope?.let {
                        requestTermination(it, origin = operation)
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

    /** 合并清理请求并等待结束；调用方可指定清理完成后的连接状态。 */
    suspend fun terminate(
        target: TerminationScope,
        finalState: RealtimeConnectionState? = null,
    ) {
        val task = synchronized(lock) {
            requestTermination(target, finalState = finalState)
        }
        // 等待者的取消不能传递给独立的资源清理任务。
        withContext(NonCancellable) { task.await() }
    }

    /** 空闲时不重复清理，但仍会取消尚未提交连接状态的活动操作。 */
    suspend fun disconnect() {
        val task = synchronized(lock) {
            val hasConnectionOperation = active?.let {
                TerminationScope.CONNECTION.affects(it.kind)
            } == true
            if (!hasConnectionOperation && termination == null &&
                state.connectionState in setOf(
                    RealtimeConnectionState.IDLE,
                    RealtimeConnectionState.DISCONNECTED,
                )
            ) null else requestTermination(
                TerminationScope.CONNECTION,
                finalState = RealtimeConnectionState.DISCONNECTED,
            )
        }
        if (task != null) withContext(NonCancellable) { task.await() }
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
        finalState: RealtimeConnectionState? = null,
        origin: Operation? = null,
    ): Deferred<Unit> {
        val existing = termination
        val pending = existing ?: Termination(target).also { termination = it }
        if (target > pending.target) pending.target = target
        if (pending.error == null) pending.error = error
        pending.finalState = mergeFinalState(pending.finalState, finalState)
        if (existing == null) {
            pending.task = scope.async(start = CoroutineStart.LAZY) {
                effects.withLock {
                    while (true) {
                        val requested = synchronized(lock) { pending.target }
                        try {
                            cleanup(requested)
                        } catch (cleanupError: Throwable) {
                            // 清理失败保留为诊断；已有原始故障时附加 suppressed，不覆盖首个故障。
                            XmaxLogger.realtime.warn(
                                message = {
                                    "Realtime cleanup failed: " +
                                        ErrorMessageFormatter.format(cleanupError)
                                },
                            )
                            pending.error?.let { if (it !== cleanupError) it.addSuppressed(cleanupError) }
                        }
                        var stateNotification: RealtimeState? = null
                        var errorNotification: XmaxError? = null
                        val done = synchronized(lock) {
                            if (pending.target != requested) false else {
                                val finalState = when {
                                    pending.finalState != null -> RealtimeState(pending.finalState!!)
                                    pending.error != null -> if (requested == TerminationScope.GENERATION) {
                                        state.copy(connectionState = RealtimeConnectionState.ERROR, taskId = null)
                                    } else RealtimeState(RealtimeConnectionState.ERROR)
                                    requested >= TerminationScope.CONNECTION -> RealtimeState(RealtimeConnectionState.DISCONNECTED)
                                    state.connectionState == RealtimeConnectionState.GENERATING -> state.copy(connectionState = RealtimeConnectionState.CONNECTED, taskId = null)
                                    else -> state
                                }
                                // 先注销旧操作和终止任务，再允许最终状态回调重入新生命周期。
                                if (active?.let { requested.affects(it.kind) } == true) active = null
                                settings.removeAll { requested.affects(it.kind) }
                                if (state != finalState) {
                                    state = finalState
                                    stateNotification = finalState
                                }
                                errorNotification = pending.error
                                termination = null
                                true
                            }
                        }
                        if (done) {
                            stateNotification?.let(callbacks::state)
                            errorNotification?.let(callbacks::error)
                            break
                        }
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
        if (pending.target >= TerminationScope.CONNECTION) {
            setState(state.copy(connectionState = RealtimeConnectionState.DISCONNECTING, taskId = null))
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

    /** 显式断开优先于错误状态，与 iOS 合并并发关闭请求的规则一致。 */
    private fun mergeFinalState(
        current: RealtimeConnectionState?,
        requested: RealtimeConnectionState?,
    ): RealtimeConnectionState? = when {
        current == RealtimeConnectionState.DISCONNECTED || requested == RealtimeConnectionState.DISCONNECTED ->
            RealtimeConnectionState.DISCONNECTED
        else -> requested ?: current
    }

    private fun defaultFailureScope(kind: OperationKind): TerminationScope? = when (kind) {
        OperationKind.MEDIA -> TerminationScope.ALL
        OperationKind.CONNECTION -> TerminationScope.CONNECTION
        OperationKind.GENERATION, OperationKind.SWITCH -> TerminationScope.GENERATION
        OperationKind.CONFIGURATION, OperationKind.SETTING -> null
    }

    /** 失效与终止标记在 lock 内更新，task 在操作对外可见前完成赋值。 */
    internal class Operation(
        val kind: OperationKind,
        var failureScope: TerminationScope?,
    ) {
        lateinit var task: Deferred<*>
        var invalidated = false
        var terminated = false
        var fatalFailure: XmaxError? = null
    }
    /** 合并后的清理目标和首个故障；清理过程中收到更大目标时继续执行下一轮。 */
    private class Termination(var target: TerminationScope) {
        lateinit var task: Deferred<Unit>
        var error: XmaxError? = null
        var finalState: RealtimeConnectionState? = null
    }
}
