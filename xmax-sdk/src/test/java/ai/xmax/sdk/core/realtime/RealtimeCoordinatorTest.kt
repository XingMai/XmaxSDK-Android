package ai.xmax.sdk

import ai.xmax.sdk.RealtimeCoordinator.OperationKind
import ai.xmax.sdk.RealtimeCoordinator.TerminationScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RealtimeCoordinatorTest {
    @Test fun `close cancels pending startup joins rollback and merges concurrent shutdown`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val events = mutableListOf<String>()
        val rollback = CompletableDeferred<Unit>()
        val coordinator = RealtimeCoordinator(RealtimeCallbacks(dispatcher), dispatcher) { events += "cleanup:$it" }
        val start = async {
            coordinator.run(OperationKind.GENERATION) { token ->
                token.commit(RealtimeState(RealtimeConnectionState.CONNECTING))
                try { awaitCancellation() }
                finally { withContext(NonCancellable) { rollback.await(); events += "rollback" } }
            }
        }
        runCurrent()
        val stop = async { coordinator.terminate(TerminationScope.GENERATION) }
        val disconnect = async { coordinator.terminate(TerminationScope.CONNECTION) }
        val close = async { coordinator.terminate(TerminationScope.ALL, clearListeners = true) }
        runCurrent()
        assertFalse(close.isCompleted)
        assertEquals(RealtimeConnectionState.DISCONNECTING, coordinator.currentState.connectionState)
        val busy = runCatching { coordinator.run(OperationKind.CONNECTION) {} }.exceptionOrNull() as XmaxError
        assertEquals(XmaxErrorSeverity.RECOVERABLE, busy.severity)
        assertTrue(events.isEmpty())
        rollback.complete(Unit)
        close.await(); stop.await(); disconnect.await(); start.join()
        assertTrue(start.isCancelled)
        assertEquals(listOf("rollback", "cleanup:ALL"), events)
        assertEquals(RealtimeConnectionState.DISCONNECTED, coordinator.currentState.connectionState)
        coordinator.run(OperationKind.MEDIA) { events += "reuse" }
        assertEquals("reuse", events.last())
    }

    @Test fun `fatal startup both throws and notifies once after cleanup`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val callbacks = RealtimeCallbacks(dispatcher)
        val events = mutableListOf<String>()
        val errors = mutableListOf<XmaxError>()
        val released = CompletableDeferred<Unit>()
        val coordinator = RealtimeCoordinator(callbacks, dispatcher) {
            released.await(); events += "cleaned"
        }
        callbacks.setErrorListener { errors += it; events += "callback" }
        val fatal = XmaxError(XmaxErrorCode.RTC_ERROR, "cannot join")
        val result = async {
            runCatching {
                coordinator.run(OperationKind.CONNECTION) { token ->
                    token.fail(fatal, TerminationScope.CONNECTION)
                    throw fatal
                }
            }
        }
        runCurrent()
        assertSame(fatal, result.await().exceptionOrNull())
        coordinator.fatal(fatal, TerminationScope.CONNECTION)
        assertTrue(errors.isEmpty())
        released.complete(Unit)
        runCurrent()
        coordinator.fatal(fatal, TerminationScope.CONNECTION)
        runCurrent()
        assertEquals(listOf("cleaned", "callback"), events)
        assertEquals(listOf(fatal), errors)
        assertEquals(RealtimeConnectionState.ERROR, coordinator.currentState.connectionState)
    }

    @Test fun `caller cancellation reclaims a produced but unobserved media result`() = runTest {
        val workers = QueuedDispatcher()
        var cleaned = false
        var allocated = false
        val coordinator = RealtimeCoordinator(RealtimeCallbacks(StandardTestDispatcher(testScheduler)), workers) { cleaned = true }
        val caller = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.run(OperationKind.MEDIA) { allocated = true; "stream" }
        }
        workers.drain()
        assertTrue(allocated)
        assertFalse(caller.isCompleted)
        caller.cancel()
        runCurrent()
        workers.drain()
        runCurrent()
        caller.join()
        assertTrue(cleaned)
    }

    @Test fun `cancelled close waiter cannot abandon cleanup`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val released = CompletableDeferred<Unit>()
        var cleaned = false
        val coordinator = RealtimeCoordinator(RealtimeCallbacks(dispatcher), dispatcher) { released.await(); cleaned = true }
        val close = async { coordinator.terminate(TerminationScope.ALL, true) }
        runCurrent(); close.cancel(); runCurrent()
        assertFalse(close.isCompleted)
        released.complete(Unit); close.join()
        assertTrue(cleaned)
    }

    @Test fun `listeners filter recoverable errors reject old registrations and isolate exceptions`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val callbacks = RealtimeCallbacks(dispatcher)
        var oldCount = 0
        var newCount = 0
        callbacks.setErrorListener { oldCount++ }
        callbacks.error(XmaxError(XmaxErrorCode.RTC_ERROR, "old"))
        callbacks.setErrorListener { newCount++; error("consumer failed") }
        callbacks.error(XmaxError(XmaxErrorCode.INVALID_CONFIGURATION, "recoverable"))
        callbacks.error(XmaxError(XmaxErrorCode.RTC_ERROR, "fatal"))
        runCurrent()
        assertEquals(0, oldCount)
        assertEquals(1, newCount)
        callbacks.error(XmaxError(XmaxErrorCode.RTC_ERROR, "after close"))
        callbacks.clear(); runCurrent()
        assertEquals(1, newCount)
    }

    @Test fun `background fatal interrupts pending call with the fatal error`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val callbacks = RealtimeCallbacks(dispatcher)
        val errors = mutableListOf<XmaxError>()
        callbacks.setErrorListener { errors += it }
        val coordinator = RealtimeCoordinator(callbacks, dispatcher) {}
        val call = async { runCatching { coordinator.run(OperationKind.GENERATION) { awaitCancellation() } } }
        runCurrent()
        val fatal = XmaxError(XmaxErrorCode.SESSION_ERROR, "session expired")
        coordinator.fatal(fatal, TerminationScope.CONNECTION)
        assertSame(fatal, call.await().exceptionOrNull())
        runCurrent()
        assertEquals(listOf(fatal), errors)
    }

    @Test fun `settings wait for startup and close cancels queued settings`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var applied = 0
        val coordinator = RealtimeCoordinator(RealtimeCallbacks(dispatcher), dispatcher) {}
        val start = async { coordinator.run(OperationKind.GENERATION) { awaitCancellation() } }
        runCurrent()
        val localVolume = async { coordinator.run(OperationKind.SETTING) { applied++ } }
        val remoteVolume = async { coordinator.run(OperationKind.SETTING) { applied++ } }
        runCurrent()
        assertEquals(0, applied)
        coordinator.terminate(TerminationScope.ALL, true)
        start.join(); localVolume.join(); remoteVolume.join()
        assertEquals(0, applied)
        val local = async { coordinator.run(OperationKind.SETTING) { applied++ } }
        val remote = async { coordinator.run(OperationKind.SETTING) { applied++ } }
        local.await(); remote.await()
        assertEquals(2, applied)
    }

    @Test fun `cancellation is never converted into a business error`() {
        val cancelled = CancellationException("caller cancelled")
        assertSame(cancelled, runCatching { XmaxError.from(cancelled) }.exceptionOrNull())
    }
}

private class QueuedDispatcher : CoroutineDispatcher() {
    private val queue = ArrayDeque<Runnable>()
    override fun dispatch(context: CoroutineContext, block: Runnable) { queue.add(block) }
    fun drain() { while (queue.isNotEmpty()) queue.removeFirst().run() }
}
