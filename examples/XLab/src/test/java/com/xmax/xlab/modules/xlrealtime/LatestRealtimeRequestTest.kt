package com.xmax.xlab.modules.xlrealtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LatestRealtimeRequestTest {
    @Test
    fun `rapid A B C selections wait for A cleanup and only start C`() = runTest {
        val events = mutableListOf<String>()
        val busy = mutableListOf<Boolean>()
        val cleanup = CompletableDeferred<Unit>()
        val requests = LatestRealtimeRequest(this, busy::add)
        requests.replace {
            events += "start A"
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    cleanup.await()
                    events += "clean A"
                }
            }
        }

        requests.replace { events += "start B" }
        requests.replace { events += "start C" }
        runCurrent()

        assertEquals(listOf("start A"), events)
        assertEquals(listOf(true, true, true), busy)

        cleanup.complete(Unit)
        runCurrent()

        assertEquals(listOf("start A", "clean A", "start C"), events)
        assertEquals(listOf(true, true, true, false), busy)
    }

    @Test
    fun `late result cannot overwrite the newest selection`() = runTest {
        val response = CompletableDeferred<Unit>()
        var displayed: String? = null
        var busy = false
        val requests = LatestRealtimeRequest(this) { busy = it }
        requests.replace {
            withContext(NonCancellable) { response.await() }
            ensureCurrent()
            displayed = "A"
        }
        requests.replace {
            ensureCurrent()
            displayed = "B"
        }
        runCurrent()
        assertTrue(busy)
        assertEquals(null, displayed)

        response.complete(Unit)
        runCurrent()

        assertEquals("B", displayed)
        assertFalse(busy)
    }

    @Test
    fun `source change drains the replacement chain and permits a fresh source`() = runTest {
        val cleanup = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        var busy = false
        val requests = LatestRealtimeRequest(this) { busy = it }
        requests.replace {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    cleanup.await()
                    events += "old source released"
                }
            }
        }
        requests.replace { events += "obsolete selection" }
        val reset = async { requests.cancelAndJoin() }
        runCurrent()
        assertFalse(reset.isCompleted)
        assertTrue(busy)

        cleanup.complete(Unit)
        reset.await()
        assertFalse(busy)
        requests.replace { events += "new source" }

        assertEquals(listOf("old source released", "new source"), events)
        assertFalse(busy)
    }

    @Test
    fun `selection after stop waits for disconnect even if stop is cancelled`() = runTest {
        val disconnected = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val requests = LatestRealtimeRequest(this) {}
        requests.replace {
            withContext(NonCancellable) {
                events += "disconnect"
                disconnected.await()
                events += "disconnected"
            }
        }
        requests.replace { events += "start latest" }
        runCurrent()
        assertEquals(listOf("disconnect"), events)

        disconnected.complete(Unit)
        runCurrent()

        assertEquals(listOf("disconnect", "disconnected", "start latest"), events)
    }

    @Test
    fun `only callbacks belonging to the latest selection remain valid`() = runTest {
        val errors = mutableListOf<String>()
        var oldCallback: () -> Unit = {}
        var newCallback: () -> Unit = {}
        val requests = LatestRealtimeRequest(this) {}
        requests.replace {
            oldCallback = { if (isCurrent) errors += "old" }
        }
        oldCallback()
        assertEquals(listOf("old"), errors)
        errors.clear()

        requests.replace {
            newCallback = { if (isCurrent) errors += "new" }
        }
        oldCallback()
        newCallback()
        assertEquals(listOf("new"), errors)

        requests.cancelAndJoin()
        newCallback()
        assertEquals(listOf("new"), errors)
    }
}
