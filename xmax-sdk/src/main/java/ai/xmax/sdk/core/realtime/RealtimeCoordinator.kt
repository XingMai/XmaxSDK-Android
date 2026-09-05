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
 * Short admission/state critical sections never suspend. Effects share one gate, including rollback.
 * Termination is registered before cancelling work and runs in an independent supervised task.
 */
internal class RealtimeCoordinator(
    private val callbacks: RealtimeCallbacks,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val cleanup: suspend (TerminationScope) -> Unit,
) {
    enum class OperationKind { MEDIA, CONNECTION, GENERATION, SWITCH, SETTING }
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
    val currentState: RealtimeState get() = synchronized(lock) { state }

    inner class Token internal constructor(private val operation: Operation) {
        fun ensureCurrent() = synchronized(lock) {
            if ((active !== operation && operation !in settings) || operation.invalidated) throw CancellationException("Realtime operation was superseded")
        }
        fun commit(next: RealtimeState) = synchronized(lock) {
            ensureCurrent()
            setState(next)
        }
        fun fail(error: XmaxError, target: TerminationScope) {
            synchronized(lock) {
                ensureCurrent()
                operation.invalidated = true
                requestTermination(target, error, origin = operation)
            }
        }
    }

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
            // Keep admission reserved until a cancelled/unobserved result has been reclaimed.
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

    suspend fun terminate(target: TerminationScope, clearListeners: Boolean = false) {
        val task = synchronized(lock) {
            requestTermination(target, clearListeners = clearListeners)
        }
        // Cancellation of a waiter must never cancel resource teardown.
        withContext(NonCancellable) { task.await() }
    }

    /** Non-blocking: safe to call from the failing heartbeat/media job itself. */
    fun fatal(error: XmaxError, target: TerminationScope) = synchronized(lock) {
        if (state.connectionState == RealtimeConnectionState.ERROR && termination == null) return@synchronized
        requestTermination(target, error)
        Unit
    }

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

    private fun setState(next: RealtimeState) {
        if (state == next) return
        state = next
        callbacks.state(next)
    }

    internal class Operation(val kind: OperationKind) {
        lateinit var task: Deferred<*>
        var invalidated = false
        var terminated = false
        var fatalFailure: XmaxError? = null
    }
    private class Termination(var target: TerminationScope) {
        lateinit var task: Deferred<Unit>
        var error: XmaxError? = null
        var clearListeners = false
    }
}
