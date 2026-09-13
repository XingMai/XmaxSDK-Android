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
    @Test fun `media preparation failure without owned resources resets state without cleanup`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val cleanups = mutableListOf<TerminationScope>()
        val coordinator = RealtimeCoordinator(
            RealtimeCallbacks(dispatcher),
            dispatcher,
            cleanup = cleanups::add,
        )
        val failure = XmaxError(XmaxErrorCode.MEDIA_ERROR, "Cannot read input")

        val thrown = runCatching {
            coordinator.run(OperationKind.MEDIA, failureScope = null) { token ->
                token.commit(RealtimeState(RealtimeConnectionState.PREPARING))
                throw failure
            }
        }.exceptionOrNull()

        assertSame(failure, thrown)
        assertEquals(RealtimeConnectionState.IDLE, coordinator.currentState.connectionState)
        assertTrue(cleanups.isEmpty())
    }

    @Test fun `connection failure scope cleans up even for configuration error code`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val cleanups = mutableListOf<TerminationScope>()
        val coordinator = RealtimeCoordinator(
            RealtimeCallbacks(dispatcher),
            dispatcher,
            cleanup = cleanups::add,
        )
        val failure = XmaxError(XmaxErrorCode.INVALID_CONFIGURATION, "Session rejected")

        val thrown = runCatching {
            coordinator.run(OperationKind.CONNECTION, failureScope = null) { token ->
                token.setFailureScope(TerminationScope.CONNECTION)
                token.commit(RealtimeState(RealtimeConnectionState.CONNECTING))
                throw failure
            }
        }.exceptionOrNull()

        assertSame(failure, thrown)
        assertEquals(listOf(TerminationScope.CONNECTION), cleanups)
        assertEquals(RealtimeReason.Failure(failure), coordinator.currentState.reason)
    }

    @Test fun `camera readiness and reasoned disconnect follow iOS state lifecycle`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var mediaPresent = false
        val coordinator = RealtimeCoordinator(
            callbacks = RealtimeCallbacks(dispatcher),
            dispatcher = dispatcher,
            hasLocalMedia = { mediaPresent },
            cleanup = { target ->
                if (target == TerminationScope.ALL) mediaPresent = false
            },
        )

        coordinator.run(OperationKind.MEDIA) { token ->
            token.commit(RealtimeState(RealtimeConnectionState.PREPARING))
            mediaPresent = true
        }
        assertEquals(RealtimeConnectionState.PREPARING, coordinator.currentState.connectionState)

        coordinator.localPreviewDidBecomeReady()
        coordinator.localPreviewDidBecomeReady()
        assertEquals(RealtimeConnectionState.READY, coordinator.currentState.connectionState)

        coordinator.run(OperationKind.CONNECTION) { token ->
            token.commit(RealtimeState(RealtimeConnectionState.CONNECTED, sessionId = "session-1"))
        }
        coordinator.disconnect(RealtimeReason.OrientationChanged)
        assertEquals(RealtimeConnectionState.READY, coordinator.currentState.connectionState)
        assertEquals("session-1", coordinator.currentState.sessionId)
        assertEquals(RealtimeReason.OrientationChanged, coordinator.currentState.reason)
        assertNull(coordinator.currentState.taskId)

        coordinator.run(OperationKind.CONNECTION) { token ->
            token.commit(RealtimeState(RealtimeConnectionState.CONNECTED, sessionId = "session-2"))
        }
        assertNull(coordinator.currentState.reason)
        coordinator.terminate(TerminationScope.ALL)
        assertEquals(RealtimeConnectionState.IDLE, coordinator.currentState.connectionState)
        assertEquals(RealtimeReason.Normal, coordinator.currentState.reason)
    }

    @Test fun `caller cancellation of configuration preserves generation`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val cleanups = mutableListOf<TerminationScope>()
        val coordinator = RealtimeCoordinator(RealtimeCallbacks(dispatcher), dispatcher, cleanup = cleanups::add)
        coordinator.run(OperationKind.GENERATION) { token ->
            token.commit(
                RealtimeState(
                    RealtimeConnectionState.GENERATING,
                    sessionId = "session",
                    taskId = "task",
                ),
            )
        }
        for (kind in listOf(OperationKind.CONFIGURATION, OperationKind.GENERATION)) {
            val started = CompletableDeferred<Unit>()
            val changing = async {
                coordinator.run(kind, TerminationScope.GENERATION) {
                    started.complete(Unit)
                    awaitCancellation()
                }
            }
            runCurrent()
            assertTrue(started.isCompleted)
            changing.cancel()
            changing.join()

            assertTrue(cleanups.isEmpty())
            assertEquals(RealtimeConnectionState.GENERATING, coordinator.currentState.connectionState)
        }
        coordinator.run(OperationKind.CONFIGURATION) {}
    }

    @Test fun `disconnect cancels and waits for configuration before connection cleanup`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val events = mutableListOf<String>()
        val coordinator = RealtimeCoordinator(RealtimeCallbacks(dispatcher), dispatcher) {
            events += "cleanup:$it"
        }
        val changing = async {
            coordinator.run(OperationKind.CONFIGURATION, TerminationScope.GENERATION) {
                try {
                    awaitCancellation()
                } finally {
                    events += "configuration-finished"
                }
            }
        }
        runCurrent()

        coordinator.disconnect()
        changing.join()

        assertEquals(
            listOf("configuration-finished", "cleanup:CONNECTION"),
            events,
        )
    }

    @Test fun `disconnect sees pending connection before it commits connecting state`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val cleanups = mutableListOf<TerminationScope>()
        val coordinator = RealtimeCoordinator(RealtimeCallbacks(dispatcher), dispatcher, cleanup = cleanups::add)
        val started = CompletableDeferred<Unit>()
        val connecting = async {
            coordinator.run(OperationKind.CONNECTION, TerminationScope.CONNECTION) {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        runCurrent()
        assertTrue(started.isCompleted)
        assertEquals(RealtimeConnectionState.IDLE, coordinator.currentState.connectionState)

        coordinator.disconnect()
        connecting.join()

        assertTrue(connecting.isCancelled)
        assertEquals(listOf(TerminationScope.CONNECTION), cleanups)
        assertEquals(RealtimeConnectionState.IDLE, coordinator.currentState.connectionState)
        assertEquals(RealtimeReason.Normal, coordinator.currentState.reason)
    }

    @Test fun `disconnect is idle safe and does not cancel media preparation`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val cleanups = mutableListOf<TerminationScope>()
        val coordinator = RealtimeCoordinator(RealtimeCallbacks(dispatcher), dispatcher, cleanup = cleanups::add)
        coordinator.disconnect()
        assertEquals(RealtimeConnectionState.IDLE, coordinator.currentState.connectionState)
        assertTrue(cleanups.isEmpty())

        val release = CompletableDeferred<Unit>()
        val media = async {
            coordinator.run(OperationKind.MEDIA) { release.await() }
        }
        runCurrent()
        coordinator.disconnect()
        assertFalse(media.isCompleted)
        assertTrue(cleanups.isEmpty())
        release.complete(Unit)
        media.await()

        coordinator.terminate(TerminationScope.CONNECTION)
        coordinator.disconnect()
        assertEquals(listOf(TerminationScope.CONNECTION), cleanups)
    }

    @Test fun `final notification unregisters old operation before reentrant start`() = runTest {
        val callbacks = RealtimeCallbacks(ImmediateDispatcher)
        val cleanups = mutableListOf<TerminationScope>()
        val coordinator = RealtimeCoordinator(callbacks, ImmediateDispatcher, cleanup = cleanups::add)
        val started = CompletableDeferred<Unit>()
        val running = async {
            runCatching {
                coordinator.run(OperationKind.GENERATION, TerminationScope.CONNECTION) {
                    started.complete(Unit)
                    awaitCancellation()
                }
            }
        }
        started.await()
        val testScope = this
        var restart: kotlinx.coroutines.Deferred<Boolean>? = null
        callbacks.setStateListener({ state ->
            if (state.connectionState == RealtimeConnectionState.IDLE &&
                state.reason == RealtimeReason.Normal && restart == null
            ) {
                restart = testScope.async(start = CoroutineStart.UNDISPATCHED) {
                    runCatching { coordinator.run(OperationKind.CONNECTION) {} }.isSuccess
                }
            }
        }, coordinator.currentState)

        coordinator.disconnect()

        running.await()
        assertTrue(restart?.await() == true)
        assertEquals(listOf(TerminationScope.CONNECTION), cleanups)
    }

    @Test fun `close started by idle notification completes full cleanup`() = runTest {
        val callbacks = RealtimeCallbacks(ImmediateDispatcher)
        val cleanups = mutableListOf<TerminationScope>()
        val coordinator = RealtimeCoordinator(callbacks, ImmediateDispatcher, cleanup = cleanups::add)
        coordinator.run(OperationKind.CONNECTION) { token ->
            token.commit(RealtimeState(RealtimeConnectionState.CONNECTED))
        }
        val testScope = this
        var close: kotlinx.coroutines.Deferred<Unit>? = null
        callbacks.setStateListener({ state ->
            if (state.connectionState == RealtimeConnectionState.IDLE && close == null) {
                close = testScope.async(start = CoroutineStart.UNDISPATCHED) {
                    coordinator.terminate(TerminationScope.ALL)
                }
            }
        }, coordinator.currentState)

        coordinator.disconnect()

        close?.await()
        assertEquals(
            listOf(TerminationScope.CONNECTION, TerminationScope.ALL),
            cleanups,
        )
    }

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
        val close = async {
            coordinator.terminate(TerminationScope.ALL)
        }
        runCurrent()
        assertFalse(close.isCompleted)
        assertEquals(RealtimeConnectionState.DISCONNECTING, coordinator.currentState.connectionState)
        val busy = runCatching { coordinator.run(OperationKind.CONNECTION) {} }.exceptionOrNull() as XmaxError
        assertEquals(XmaxErrorCode.INVALID_CONFIGURATION, busy.code)
        assertTrue(events.isEmpty())
        rollback.complete(Unit)
        close.await(); stop.await(); disconnect.await(); start.join()
        assertTrue(start.isCancelled)
        assertEquals(listOf("rollback", "cleanup:ALL"), events)
        assertEquals(RealtimeConnectionState.IDLE, coordinator.currentState.connectionState)
        coordinator.run(OperationKind.MEDIA) { events += "reuse" }
        assertEquals("reuse", events.last())
    }

    @Test fun `operation failure throws and notifies once after cleanup`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val callbacks = RealtimeCallbacks(dispatcher)
        val events = mutableListOf<String>()
        val errors = mutableListOf<XmaxError>()
        val released = CompletableDeferred<Unit>()
        val coordinator = RealtimeCoordinator(callbacks, dispatcher) {
            released.await(); events += "cleaned"
        }
        callbacks.setStateListener({ state ->
            (state.reason as? RealtimeReason.Failure)?.let {
                errors += it.error
                events += "callback"
            }
        }, coordinator.currentState)
        val failure = XmaxError(XmaxErrorCode.RTC_ERROR, "cannot join")
        val result = async {
            runCatching {
                coordinator.run(OperationKind.CONNECTION, TerminationScope.CONNECTION) {
                    throw failure
                }
            }
        }
        runCurrent()
        assertFalse(result.isCompleted)
        coordinator.reportFailure(failure, TerminationScope.CONNECTION)
        assertTrue(errors.isEmpty())
        released.complete(Unit)
        runCurrent()
        assertSame(failure, result.await().exceptionOrNull())
        coordinator.reportFailure(failure, TerminationScope.CONNECTION)
        runCurrent()
        assertEquals(listOf("cleaned", "callback"), events)
        assertEquals(listOf(failure), errors)
        assertEquals(RealtimeConnectionState.IDLE, coordinator.currentState.connectionState)
        assertEquals(RealtimeReason.Failure(failure), coordinator.currentState.reason)
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
        val close = async {
            coordinator.terminate(TerminationScope.ALL)
        }
        runCurrent(); close.cancel(); runCurrent()
        assertFalse(close.isCompleted)
        released.complete(Unit); close.join()
        assertTrue(cleaned)
    }

    @Test fun `state listener rejects old registrations and carries failure reason`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val callbacks = RealtimeCallbacks(dispatcher)
        var oldCount = 0
        var newCount = 0
        callbacks.setStateListener({ oldCount++ }, RealtimeState(RealtimeConnectionState.IDLE))
        callbacks.state(RealtimeState(RealtimeConnectionState.READY))
        callbacks.setStateListener({ state ->
            if (state.reason is RealtimeReason.Failure) {
                newCount++
                error("consumer failed")
            }
        }, RealtimeState(RealtimeConnectionState.READY))
        callbacks.state(RealtimeState(
            RealtimeConnectionState.IDLE,
            reason = RealtimeReason.Failure(XmaxError(XmaxErrorCode.RTC_ERROR, "fatal")),
        ))
        runCurrent()
        assertEquals(0, oldCount)
        assertEquals(1, newCount)
        callbacks.state(RealtimeState(RealtimeConnectionState.READY))
        callbacks.setStateListener(null, RealtimeState(RealtimeConnectionState.READY))
        runCurrent()
        assertEquals(1, newCount)
    }

    @Test fun `background failure interrupts pending call with the original error`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val callbacks = RealtimeCallbacks(dispatcher)
        val errors = mutableListOf<XmaxError>()
        val coordinator = RealtimeCoordinator(callbacks, dispatcher) {}
        callbacks.setStateListener({ state ->
            (state.reason as? RealtimeReason.Failure)?.let { errors += it.error }
        }, coordinator.currentState)
        val call = async { runCatching { coordinator.run(OperationKind.GENERATION) { awaitCancellation() } } }
        runCurrent()
        val failure = XmaxError(XmaxErrorCode.SESSION_ERROR, "session expired")
        coordinator.reportFailure(failure, TerminationScope.CONNECTION)
        assertSame(failure, call.await().exceptionOrNull())
        runCurrent()
        assertEquals(listOf(failure), errors)
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
        coordinator.terminate(TerminationScope.ALL)
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

private object ImmediateDispatcher : CoroutineDispatcher() {
    override fun dispatch(context: CoroutineContext, block: Runnable) = block.run()
}
