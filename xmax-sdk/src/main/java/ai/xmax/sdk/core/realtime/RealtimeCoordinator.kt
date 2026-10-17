//
//  RealtimeCoordinator.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

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
 *
 * @param callbacks 向接入方派发状态与视频帧的回调组件。
 * @param dispatcher 执行后台操作的协程调度器；默认值为 `Dispatchers.Default`。
 * @param hasLocalMedia 查询是否仍有可用本地媒体的函数。
 * @param cleanup 按终止范围释放资源的挂起操作。
 */
internal class RealtimeCoordinator(
    private val callbacks: RealtimeCallbacks,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val hasLocalMedia: () -> Boolean = { false },
    private val cleanup: suspend (TerminationScope) -> Unit,
) {
    /**
     * 生成与外部切流请求以最新一次为准，设置可排队，其他操作同一时刻只接纳一个。
     */
    enum class OperationKind {
        /**
         * 创建、替换或停止本地媒体的操作。
         */
        MEDIA,

        /**
         * 实时连接操作或连接范围的资源终止。
         */
        CONNECTION,

        /**
         * 生成任务操作或任务范围的资源终止。
         */
        GENERATION,

        /**
         * 替换外部视频输入的操作。
         */
        REPLACEMENT,

        /**
         * 切换相机方向的操作。
         */
        SWITCH,

        /**
         * 更新媒体编码配置的操作。
         */
        CONFIGURATION,

        /**
         * 更新音量或监听配置的操作。
         */
        SETTING
    }

    /**
     * 按清理范围递增排列；合并终止请求时只能扩大范围。
     */
    enum class TerminationScope {
        /**
         * 生成任务操作或任务范围的资源终止。
         */
        GENERATION,

        /**
         * 实时连接操作或连接范围的资源终止。
         */
        CONNECTION,

        /**
         * 终止全部实时媒体和连接资源。
         */
        ALL;

        /**
         * 判断指定类型的操作是否会使当前操作失效。
         *
         * @param kind 本次操作或媒体源的类型。
         * @return 该操作会使当前操作失效时为 true。
         */
        fun affects(kind: OperationKind): Boolean = when (this) {
            GENERATION -> kind == OperationKind.GENERATION || kind == OperationKind.REPLACEMENT || kind == OperationKind.SWITCH ||
                kind == OperationKind.CONFIGURATION
            CONNECTION -> kind != OperationKind.MEDIA && kind != OperationKind.SETTING
            ALL -> true
        }
    }

    /**
     * 状态访问和副作用执行的并发保护。
     */
    private val lock = Any()
    private val effects = Mutex()

    /**
     * 生命周期操作的执行作用域。
     */
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    /**
     * 当前操作、生成请求及终止任务。
     */
    private var active: Operation? = null
    private var generation: GenerationRequest? = null
    private val settings = mutableSetOf<Operation>()
    private var foregroundRequests = 0
    private var termination: Termination? = null

    /**
     * 生命周期状态及公开只读快照。
     */
    private var state = RealtimeState(RealtimeConnectionState.IDLE)

    /**
     * 同步读取不可变快照，外部不能直接修改协调器状态。
     */
    val currentState: RealtimeState get() = synchronized(lock) { state }

    /**
     * 是否有尚未完成的前台、后台或资源清理操作。
     */
    val isBusy: Boolean get() = synchronized(lock) {
        foregroundRequests > 0 || active != null || settings.isNotEmpty() || termination != null || generation != null
    }

    /**
     * 相机首帧与预览绑定均就绪后结束媒体准备；迟到通知不改变其他状态。
     */
    fun localPreviewDidBecomeReady() = synchronized(lock) {
        if (state.connectionState == RealtimeConnectionState.PREPARING && termination == null) {
            setState(RealtimeState(RealtimeConnectionState.READY))
        }
    }

    /**
     * 单次操作的所有权凭证；异步返回后检查它，阻止旧结果覆盖新生命周期。
     *
     * @param operation 本次生命周期操作及其执行状态。
     */
    inner class Token internal constructor(private val operation: Operation) {
        /**
         * 切换输入后恢复生成所需的请求配置。
         *
         * 替换沿用正在启动的请求意图；已生效条件仍只保存在 GenerationManager。
         */
        val resumeGeneration: Boolean get() = operation.generation?.resumeGeneration == true
        val requestedContext: RealtimeContext? get() = synchronized(lock) { operation.generation?.context }

        /**
         * 切流恢复生成时使用同一请求的人脸还原设置，尚未继承时为 null。
         */
        val requestedFaceRestoration: Boolean? get() = synchronized(lock) { operation.generation?.faceRestoration }

        /**
         * 为待恢复的生成任务保留最近一次有效的生成条件。
         *
         * @param context 生成提示词及参考素材；null 表示复用已缓存条件，首次生成时必须提供。
         * @param faceRestoration 是否启用人脸修复，对应生成参数 `face_replace`；默认值为 `false`。
         */
        fun inheritGenerationContext(context: RealtimeContext?, faceRestoration: Boolean = false) = synchronized(lock) {
            ensureCurrent()
            operation.generation?.let {
                if (it.context == null) it.context = context
                if (it.faceRestoration == null) it.faceRestoration = faceRestoration
            }
        }

        /**
         * 操作被替换或终止后按协程取消退出，避免将过期结果视为业务失败。
         */
        fun ensureCurrent() = synchronized(lock) {
            if ((active !== operation && operation !in settings) || operation.invalidated) throw CancellationException("Realtime operation was superseded")
            operation.generation?.let {
                if (generation !== it || !it.job.isActive) throw CancellationException("Realtime generation was superseded")
            }
        }

        /**
         * 仅有效操作可以提交状态；验证所有权与状态写入处于同一临界区。
         *
         * @param next 准备提交的新状态快照。
         */
        fun commit(next: RealtimeState) = synchronized(lock) {
            ensureCurrent()
            setState(next)
        }

        /**
         * 连接建立后将后续故障的清理范围收窄到当前生成任务。
         *
         * @param scope 需要终止的资源范围。
         */
        fun setFailureScope(scope: TerminationScope) = synchronized(lock) {
            ensureCurrent()
            operation.failureScope = scope
        }
    }

    /**
     * 生成和切流请求替换上一请求；其他冲突返回配置错误，最多同时接纳 16 个设置操作。
     * 调用方取消时，先等待操作及回滚结束，再释放准入资格，防止新操作复用尚未清理的资源。
     *
     * @param kind 本次操作或媒体源的类型。
     * @param failureScope 操作失败时终止的资源范围；null 表示不额外终止资源。
     * @param context 生成提示词及参考素材；null 表示复用已缓存条件，首次生成时必须提供。
     * @param faceRestoration 是否启用人脸修复，对应生成参数 `face_replace`；默认值为 `false`。
     * @param action 需要执行的操作。
     * @return 传入操作成功执行后的结果。
     */
    suspend fun <T> run(
        kind: OperationKind,
        failureScope: TerminationScope? = defaultFailureScope(kind),
        context: RealtimeContext? = null,
        faceRestoration: Boolean = false,
        action: suspend (Token) -> T,
    ): T {
        val background = synchronized(lock) {
            foregroundRequests++
            active?.takeIf { it.background }?.also {
                it.invalidated = true
                it.task.cancel(CancellationException("Adaptive quality yields to a foreground operation"))
            }
        }
        try {
            background?.task?.join()
            synchronized(lock) { if (active === background) active = null }
            return if (kind == OperationKind.GENERATION || kind == OperationKind.REPLACEMENT) {
                runGeneration(kind, failureScope, context, faceRestoration, action)
            } else {
                runOperation(kind, failureScope, action = action)
            }
        } finally {
            synchronized(lock) { foregroundRequests-- }
        }
    }

    /**
     * 空闲时执行低优先级调档，前台操作及终止请求优先，失败不结束生成。
     *
     * @param action 需要执行的操作。
     * @return 调档操作被接纳并执行时为 true，否则为 false。
     */
    suspend fun runAdaptiveConfiguration(action: suspend (Token) -> Unit): Boolean {
        return try {
            runOperation(OperationKind.CONFIGURATION, null, background = true, action = action)
            true
        } catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
            false
        }
    }

    /**
     * 执行最新生成请求，并在启动前等待被替换请求及其资源回滚完成。
     *
     * @param kind 本次操作或媒体源的类型。
     * @param failureScope 操作失败时终止的资源范围；null 表示不额外终止资源。
     * @param context 生成提示词及参考素材；null 表示复用已缓存条件，首次生成时必须提供。
     * @param faceRestoration 是否启用人脸修复，对应生成参数 `face_replace`。
     * @param action 需要执行的操作。
     * @return 传入操作成功执行后的结果。
     */
    private suspend fun <T> runGeneration(
        kind: OperationKind,
        failureScope: TerminationScope?,
        context: RealtimeContext?,
        faceRestoration: Boolean,
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
                    if (kind == OperationKind.REPLACEMENT) previous?.faceRestoration else faceRestoration,
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
                runOperation(kind, failureScope, request, action = action)
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

    /**
     * 串行执行生命周期操作，并将失败清理限制在指定资源范围内。
     *
     * @param kind 本次操作或媒体源的类型。
     * @param failureScope 操作失败时终止的资源范围；null 表示不额外终止资源。
     * @param request 准备执行的生成请求；null 表示不附带生成上下文。
     * @param background 是否作为后台操作执行；默认值为 `false`。
     * @param action 需要执行的操作。
     * @return 传入操作成功执行后的结果。
     */
    private suspend fun <T> runOperation(
        kind: OperationKind,
        failureScope: TerminationScope?,
        request: GenerationRequest? = null,
        background: Boolean = false,
        action: suspend (Token) -> T,
    ): T {
        currentCoroutineContext().ensureActive()
        lateinit var operation: Operation
        val task = synchronized(lock) {
            if (background && (foregroundRequests > 0 || active != null || settings.isNotEmpty() || termination != null || generation != null)) {
                throw CancellationException("Adaptive quality requires an idle coordinator")
            }
            if (request != null && (generation !== request || !request.job.isActive)) {
                throw CancellationException("Realtime generation was superseded")
            }
            /**
             * 已在生成时，startGeneration 只更新条件，不再代表一轮生成生命周期。
             */
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
                background,
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
            /**
             * 即使底层已产生结果，调用方也可能来不及接收；回收后才允许新操作进入。
             */
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
                        /**
                         * 生成启动已连接时，替换只回收任务，保留连接供两个 startGeneration 重载复用。
                         */
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

    /**
     * 合并清理请求并等待结束；调用方可指定最终状态原因。
     *
     * @param target 需要终止的资源范围。
     * @param reason 引起状态变化或失败的原因；默认值为 `RealtimeReason.Normal`。
     */
    suspend fun terminate(
        target: TerminationScope,
        reason: RealtimeReason = RealtimeReason.Normal,
    ) {
        val (request, task) = synchronized(lock) {
            generation to requestTermination(target, reason = reason)
        }
        /**
         * 等待者的取消不能传递给独立的资源清理任务。
         */
        withContext(NonCancellable) {
            task.await()
            request?.finished?.await()
        }
    }

    /**
     * 空闲时不重复清理，但仍会取消尚未提交连接状态的活动操作。
     *
     * @param reason 引起状态变化或失败的原因；默认值为 `RealtimeReason.Normal`。
     */
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

    /**
     * 非阻塞登记后台故障，可直接从心跳或媒体失败协程调用，避免等待自身退出。
     *
     * @param error 本次操作失败的异常或 SDK 错误。
     * @param target 需要终止的资源范围。
     */
    fun reportFailure(error: XmaxError, target: TerminationScope) = synchronized(lock) {
        if (state.reason is RealtimeReason.Failure && termination == null) return@synchronized
        requestTermination(target, error)
        Unit
    }

    /**
     * 合并资源终止请求，并等待较大终止范围内的全部清理完成。
     *
     * @param target 需要终止的资源范围。
     * @param error 本次操作失败的异常或 SDK 错误。
     * @param reason 引起状态变化或失败的原因。
     * @param origin 触发终止的操作；null 表示不限定来源操作。
     * @return 完成资源清理时结束的异步等待结果。
     */
    private fun requestTermination(
        target: TerminationScope,
        error: XmaxError? = null,
        reason: RealtimeReason = error?.let(RealtimeReason::Failure) ?: RealtimeReason.Normal,
        origin: Operation? = null,
    ): Deferred<Unit> {
        /**
         * 显式终止或后台故障取消已登记的生成，包括尚在等待旧请求清理的最新请求。
         * 旧操作自己的回滚不能取消取代它的新请求。
         */
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
                            /**
                             * 清理失败保留为诊断；已有原始故障时附加 suppressed，不覆盖首个故障。
                             */
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
                                /**
                                 * 先注销旧操作和终止任务，再允许最终状态回调重入新生命周期。
                                 */
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

    /**
     * 更新当前状态并异步通知监听器；相同状态不重复通知。
     *
     * @param next 准备提交的新状态快照。
     */
    private fun setState(next: RealtimeState) {
        if (state == next) return
        state = next
        callbacks.state(next)
    }

    /**
     * 确定操作失败时需要释放的资源范围。
     *
     * @param kind 本次操作或媒体源的类型。
     * @return 需要清理的资源范围；null 表示无需扩大清理范围。
     */
    private fun defaultFailureScope(kind: OperationKind): TerminationScope? = when (kind) {
        OperationKind.MEDIA -> TerminationScope.ALL
        OperationKind.CONNECTION -> TerminationScope.CONNECTION
        OperationKind.GENERATION, OperationKind.REPLACEMENT, OperationKind.SWITCH -> TerminationScope.GENERATION
        OperationKind.CONFIGURATION, OperationKind.SETTING -> null
    }

    /**
     * 失效与终止标记在 lock 内更新，task 在操作对外可见前完成赋值。
     *
     * @param kind 本次操作或媒体源的类型。
     * @param failureScope 操作失败时终止的资源范围；null 表示不额外终止资源。
     * @param generation 用于识别当前生成请求或事件归属的标识。
     * @param background 是否作为后台操作执行；默认值为 `false`。
     */
    internal class Operation(
        val kind: OperationKind,
        var failureScope: TerminationScope?,
        val generation: GenerationRequest? = null,
        val background: Boolean = false,
    ) {
        /**
         * 操作执行任务及失效、终止结果。
         */
        lateinit var task: Deferred<*>
        var invalidated = false
        var terminated = false
        var terminationError: XmaxError? = null
    }

    /**
     * 在途生成/切流的所有权和意图，完成后释放；已生效条件仍由 GenerationManager 缓存。
     *
     * @param job 需要等待或取消的协程任务。
     * @param context 生成提示词及参考素材；null 表示复用已缓存条件，首次生成时必须提供。
     * @param faceRestoration 是否启用人脸修复，对应生成参数 `face_replace`。
     * @param resumeGeneration 替换输入后是否恢复生成。
     */
    internal class GenerationRequest(
        val job: Job,
        var context: RealtimeContext?,
        var faceRestoration: Boolean?,
        val resumeGeneration: Boolean,
    ) {
        /**
         * 生成请求完成状态及失败结果。
         */
        val finished = CompletableDeferred<Unit>()

        @Volatile
        var error: XmaxError? = null
    }

    /**
     * 合并后的清理目标和首个故障；清理过程中收到更大目标时继续执行下一轮。
     *
     * @param target 需要终止的资源范围。
     */
    private class Termination(var target: TerminationScope) {
        /**
         * 资源终止任务及最终状态原因。
         */
        lateinit var task: Deferred<Unit>
        var error: XmaxError? = null
        var reason: RealtimeReason = RealtimeReason.Normal
    }
}
