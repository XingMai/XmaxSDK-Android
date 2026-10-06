package ai.xmax.sdk

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
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
    private val hasLocalMedia: () -> Boolean = { false },
    private val cleanup: suspend (TerminationScope) -> Unit,
) {
    /** 生成与外部切流请求以最新一次为准，设置可排队，其他操作同一时刻只接纳一个。 */
    enum class OperationKind { MEDIA, CONNECTION, GENERATION, REPLACEMENT, SWITCH, CONFIGURATION, SETTING }
    /** 按清理范围递增排列；合并终止请求时只能扩大范围。 */
    enum class TerminationScope {
        GENERATION, CONNECTION, ALL;
        fun affects(kind: OperationKind): Boolean = when (this) {
            GENERATION -> kind == OperationKind.GENERATION || kind == OperationKind.REPLACEMENT || kind == OperationKind.SWITCH ||
                kind == OperationKind.CONFIGURATION
            CONNECTION -> kind != OperationKind.MEDIA && kind != OperationKind.SETTING
            ALL -> true
        }
    }

    private val lock = Any()
    private val effects = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var active: Operation? = null
    private var generation: GenerationRequest? = null
    private val settings = mutableSetOf<Operation>()
    private var termination: Termination? = null
    private var state = RealtimeState(RealtimeConnectionState.IDLE)
    /** 同步读取不可变快照，外部不能直接修改协调器状态。 */
    val currentState: RealtimeState get() = synchronized(lock) { state }

    /** 相机首帧与预览绑定均就绪后结束媒体准备；迟到通知不改变其他状态。 */
    fun localPreviewDidBecomeReady() = synchronized(lock) {
        if (state.connectionState == RealtimeConnectionState.PREPARING && termination == null) {
            setState(RealtimeState(RealtimeConnectionState.READY))
        }
    }

    /** 单次操作的所有权凭证；异步返回后检查它，阻止旧结果覆盖新生命周期。 */
    inner class Token internal constructor(private val operation: Operation) {
        /** 替换沿用正在启动的请求意图；已生效条件仍只保存在 GenerationManager。 */
        val resumeGeneration: Boolean get() = operation.generation?.resumeGeneration == true
        val requestedContext: RealtimeContext? get() = synchronized(lock) { operation.generation?.context }

        /** 只补齐切流的在途意图，避免规格重连被再次替换时丢失已生效条件。 */
        fun inheritGenerationContext(context: RealtimeContext?) = synchronized(lock) {
            ensureCurrent()
            operation.generation?.let { if (it.context == null) it.context = context }
        }

        /** 操作被替换或终止后按协程取消退出，避免将过期结果视为业务失败。 */
        fun ensureCurrent() = synchronized(lock) {
            if ((active !== operation && operation !in settings) || operation.invalidated) throw CancellationException("Realtime operation was superseded")
            operation.generation?.let {
                if (generation !== it || !it.job.isActive) throw CancellationException("Realtime generation was superseded")
            }
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
    }

    /**
     * 生成和切流请求替换上一请求；其他冲突返回配置错误，最多同时接纳 16 个设置操作。
     * 调用方取消时，先等待操作及回滚结束，再释放准入资格，防止新操作复用尚未清理的资源。
     */
    suspend fun <T> run(
        kind: OperationKind,
        failureScope: TerminationScope? = defaultFailureScope(kind),
        context: RealtimeContext? = null,
        action: suspend (Token) -> T,
    ): T = if (kind == OperationKind.GENERATION || kind == OperationKind.REPLACEMENT) {
        runGeneration(kind, failureScope, context, action)
    } else {
        runOperation(kind, failureScope, action = action)
    }

    /**
     * 只保留最新生成意图。子作用域的取消不会取消接入方的 Job，旧调用以 CancellationException 结束。
     * finished 包含前驱及自身回滚；即使 B 尚未执行便被 C 替换，C 也必须等 A 清理完成。
     */
    private suspend fun <T> runGeneration(
        kind: OperationKind,
        failureScope: TerminationScope?,
        context: RealtimeContext?,
        action: suspend (Token) -> T,
    ): T {
        val callerContext = currentCoroutineContext()
        return coroutineScope {
            lateinit var request: GenerationRequest
            val (previous, terminal) = synchronized(lock) {
                coroutineContext.ensureActive()
                if (termination?.target == TerminationScope.ALL || (active != null && active?.generation == null)) {
                    throw XmaxError(
                        XmaxErrorCode.INVALID_CONFIGURATION,
                        "Another realtime operation is in progress; wait for it to finish",
                    )
                }
                val previous = generation
                request = GenerationRequest(
                    coroutineContext.job,
                    if (kind == OperationKind.REPLACEMENT) previous?.context else context,
                    kind == OperationKind.GENERATION || previous?.resumeGeneration == true ||
                        state.connectionState == RealtimeConnectionState.GENERATING,
                )
                generation = request
                previous?.job?.cancel(CancellationException("Realtime generation was superseded"))
                previous to termination?.task
            }
            try {
                withContext(NonCancellable) {
                    previous?.finished?.await()
                    terminal?.join()
                }
                currentCoroutineContext().ensureActive()
                runOperation(kind, failureScope, request, action)
            } catch (cancelled: CancellationException) {
                callerContext.ensureActive()
                request.error?.let { throw it }
                throw cancelled
            } finally {
                synchronized(lock) {
                    if (generation === request) generation = null
                    request.finished.complete(Unit)
                }
            }
        }
    }

    private suspend fun <T> runOperation(
        kind: OperationKind,
        failureScope: TerminationScope?,
        request: GenerationRequest? = null,
        action: suspend (Token) -> T,
    ): T {
        currentCoroutineContext().ensureActive()
        lateinit var operation: Operation
        val task = synchronized(lock) {
            if (request != null && (generation !== request || !request.job.isActive)) {
                throw CancellationException("Realtime generation was superseded")
            }
            // 已在生成时，startGeneration 只更新条件，不再代表一轮生成生命周期。
            val operationKind = if (kind == OperationKind.GENERATION &&
                state.connectionState == RealtimeConnectionState.GENERATING
            ) OperationKind.CONFIGURATION else kind
            if (termination != null ||
                (operationKind != OperationKind.SETTING && active != null) ||
                (operationKind != OperationKind.SETTING && request == null && generation?.job?.isActive == true) ||
                (operationKind == OperationKind.SETTING && settings.size >= 16)
            ) throw XmaxError(
                XmaxErrorCode.INVALID_CONFIGURATION,
                "Another realtime operation is in progress; wait for it to finish",
            )
            operation = Operation(
                operationKind,
                if (operationKind == OperationKind.CONFIGURATION) null else failureScope,
                request,
            )
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
                    ) null else operation.failureScope?.let { target ->
                        val replacing = operation.generation != null && generation != null && generation !== operation.generation
                        val connected = state.connectionState == RealtimeConnectionState.CONNECTED ||
                            state.connectionState == RealtimeConnectionState.GENERATING
                        // 生成启动已连接时，替换只回收任务，保留连接供两个 startGeneration 重载复用。
                        val cleanupTarget = if (replacing && connected && target == TerminationScope.CONNECTION) {
                            TerminationScope.GENERATION
                        } else target
                        requestTermination(cleanupTarget, origin = operation)
                    }
                }
                terminal?.await()
            }
            if (currentCoroutineContext().isActive) operation.terminationError?.let { throw it }
            throw cancelled
        } catch (error: Throwable) {
            val resolved = XmaxError.from(error)
            val terminal = synchronized(lock) {
                termination?.task ?: operation.failureScope?.let {
                    requestTermination(it, resolved, origin = operation)
                }
            }
            if (terminal != null) {
                withContext(NonCancellable) { terminal.await() }
            } else if (operation.kind == OperationKind.MEDIA) {
                synchronized(lock) {
                    if (state.connectionState == RealtimeConnectionState.PREPARING) {
                        setState(
                            RealtimeState(
                                if (hasLocalMedia()) RealtimeConnectionState.READY
                                else RealtimeConnectionState.IDLE,
                            ),
                        )
                    }
                }
            }
            throw resolved
        } finally {
            synchronized(lock) {
                if (active === operation) active = null
                settings.remove(operation)
            }
        }
    }

    /** 合并清理请求并等待结束；调用方可指定最终状态原因。 */
    suspend fun terminate(
        target: TerminationScope,
        reason: RealtimeReason = RealtimeReason.Normal,
    ) {
        val (request, task) = synchronized(lock) {
            generation to requestTermination(target, reason = reason)
        }
        // 等待者的取消不能传递给独立的资源清理任务。
        withContext(NonCancellable) {
            task.await()
            request?.finished?.await()
        }
    }

    /** 空闲时不重复清理，但仍会取消尚未提交连接状态的活动操作。 */
    suspend fun disconnect(reason: RealtimeReason = RealtimeReason.Normal) {
        val (request, task) = synchronized(lock) {
            val hasConnectionOperation = active?.let {
                TerminationScope.CONNECTION.affects(it.kind)
            } == true
            val task = if (generation == null && !hasConnectionOperation && termination == null &&
                state.connectionState in setOf(
                    RealtimeConnectionState.IDLE,
                    RealtimeConnectionState.PREPARING,
                    RealtimeConnectionState.READY,
                )
            ) null else requestTermination(
                TerminationScope.CONNECTION,
                reason = reason,
            )
            generation to task
        }
        withContext(NonCancellable) {
            task?.await()
            request?.finished?.await()
        }
    }

    /** 非阻塞登记后台故障，可直接从心跳或媒体失败协程调用，避免等待自身退出。 */
    fun reportFailure(error: XmaxError, target: TerminationScope) = synchronized(lock) {
        if (state.reason is RealtimeReason.Failure && termination == null) return@synchronized
        requestTermination(target, error)
        Unit
    }

    /** 须持有 lock；复用同一个清理任务，并将并发请求升级为所需的最大清理范围。 */
    private fun requestTermination(
        target: TerminationScope,
        error: XmaxError? = null,
        reason: RealtimeReason = error?.let(RealtimeReason::Failure) ?: RealtimeReason.Normal,
        origin: Operation? = null,
    ): Deferred<Unit> {
        // 显式终止或后台故障取消已登记的生成，包括尚在等待旧请求清理的最新请求。
        // 旧操作自己的回滚不能取消取代它的新请求。
        if (origin == null) generation?.let {
            if (it.error == null) it.error = error
            it.job.cancel(CancellationException("Realtime generation terminated"))
        }
        val existing = termination
        val pending = existing ?: Termination(target).also { termination = it }
        if (target > pending.target) pending.target = target
        if (pending.error == null) pending.error = error
        if (reason is RealtimeReason.Failure || pending.reason == RealtimeReason.Normal) {
            pending.reason = reason
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
                            XmaxLogger.realtime.warn(
                                message = {
                                    "Realtime cleanup failed: " +
                                        ErrorMessageFormatter.format(cleanupError)
                                },
                            )
                            pending.error?.let { if (it !== cleanupError) it.addSuppressed(cleanupError) }
                        }
                        var stateNotification: RealtimeState? = null
                        val done = synchronized(lock) {
                            if (pending.target != requested) false else {
                                val finalState = RealtimeState(
                                    connectionState = when {
                                        requested == TerminationScope.ALL -> RealtimeConnectionState.IDLE
                                        requested == TerminationScope.GENERATION -> RealtimeConnectionState.CONNECTED
                                        hasLocalMedia() -> RealtimeConnectionState.READY
                                        else -> RealtimeConnectionState.IDLE
                                    },
                                    sessionId = state.sessionId,
                                    reason = pending.reason,
                                )
                                // 先注销旧操作和终止任务，再允许最终状态回调重入新生命周期。
                                if (active?.let { requested.affects(it.kind) } == true) active = null
                                settings.removeAll { requested.affects(it.kind) }
                                if (state != finalState) {
                                    state = finalState
                                    stateNotification = finalState
                                }
                                termination = null
                                true
                            }
                        }
                        if (done) {
                            stateNotification?.let(callbacks::state)
                            pending.error?.let { error ->
                                XmaxLogger.realtime.error(
                                    message = { "Realtime service error: ${ErrorMessageFormatter.format(error)}" },
                                )
                            }
                            break
                        }
                    }
                }
            }
        }
        (listOfNotNull(active) + settings).filter { pending.target.affects(it.kind) && it !== origin }.forEach {
            it.invalidated = true
            it.terminated = true
            it.terminationError = pending.error
            it.task.cancel(CancellationException("Realtime ${pending.target.name.lowercase()} terminated"))
        }
        if (pending.target >= TerminationScope.CONNECTION) {
            setState(
                RealtimeState(
                    connectionState = RealtimeConnectionState.DISCONNECTING,
                    sessionId = state.sessionId,
                ),
            )
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

    private fun defaultFailureScope(kind: OperationKind): TerminationScope? = when (kind) {
        OperationKind.MEDIA -> TerminationScope.ALL
        OperationKind.CONNECTION -> TerminationScope.CONNECTION
        OperationKind.GENERATION, OperationKind.REPLACEMENT, OperationKind.SWITCH -> TerminationScope.GENERATION
        OperationKind.CONFIGURATION, OperationKind.SETTING -> null
    }

    /** 失效与终止标记在 lock 内更新，task 在操作对外可见前完成赋值。 */
    internal class Operation(
        val kind: OperationKind,
        var failureScope: TerminationScope?,
        val generation: GenerationRequest? = null,
    ) {
        lateinit var task: Deferred<*>
        var invalidated = false
        var terminated = false
        var terminationError: XmaxError? = null
    }
    /** 在途生成/切流的所有权和意图，完成后释放；已生效条件仍由 GenerationManager 缓存。 */
    internal class GenerationRequest(
        val job: Job,
        var context: RealtimeContext?,
        val resumeGeneration: Boolean,
    ) {
        val finished = CompletableDeferred<Unit>()

        @Volatile
        var error: XmaxError? = null
    }
    /** 合并后的清理目标和首个故障；清理过程中收到更大目标时继续执行下一轮。 */
    private class Termination(var target: TerminationScope) {
        lateinit var task: Deferred<Unit>
        var error: XmaxError? = null
        var reason: RealtimeReason = RealtimeReason.Normal
    }
}
