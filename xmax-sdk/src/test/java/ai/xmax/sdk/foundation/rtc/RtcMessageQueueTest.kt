//
//  RtcMessageQueueTest.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.foundation.rtc

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
public class RtcMessageQueueTest {
    @Test
    public fun `thirty 800 byte packets fit in the first window`() = runTest {
        val packets = mutableListOf<String>()
        val queue = queue(packets)
        repeat(30) { queue.enqueue("a".repeat(800)) }
        runCurrent()
        assertEquals(30, packets.size)
        assertEquals(0L, testScheduler.currentTime)
        queue.close()
    }

    @Test
    public fun `byte budget waits for old packets to leave the sliding window`() = runTest {
        val packets = mutableListOf<String>()
        val queue = queue(packets)
        repeat(37) { queue.enqueue("a".repeat(800)) }
        runCurrent()
        assertEquals(36, packets.size)
        advanceTimeBy(999)
        runCurrent()
        assertEquals(36, packets.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(37, packets.size)
        queue.close()
    }

    @Test
    public fun `packet budget limits each window to 58 packets`() = runTest {
        val packets = mutableListOf<String>()
        val queue = queue(packets)
        repeat(59) { queue.enqueue("a") }
        runCurrent()
        assertEquals(58, packets.size)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(59, packets.size)
        queue.close()
    }

    @Test
    public fun `pending trajectories share one result and do not fill the queue`() = runTest {
        val packets = mutableListOf<String>()
        val queue = queue(packets)
        queue.enqueue("start")
        val first = queue.enqueue("position-0", "tracks:task")
        repeat(200) { assertSame(first, queue.enqueue("position-$it", "tracks:task")) }
        queue.enqueue("condition")
        queue.enqueue("stop")
        runCurrent()
        queue.flush()
        assertEquals(listOf("start", "position-199", "condition", "stop"), packets)
        queue.close()
    }

    @Test
    public fun `trajectory remains replaceable while waiting for budget`() = runTest {
        val packets = mutableListOf<String>()
        val queue = queue(packets)
        repeat(58) { queue.enqueue("budget") }
        runCurrent()
        queue.enqueue("old", "tracks:task")
        runCurrent()
        advanceTimeBy(20)
        queue.enqueue("new".repeat(100), "tracks:task")
        runCurrent()
        advanceTimeBy(980)
        runCurrent()
        assertEquals(59, packets.size)
        assertEquals("new".repeat(100), packets.last())
        assertFalse(packets.contains("old"))
        queue.close()
    }

    @Test
    public fun `larger replacement rechecks byte budget`() = runTest {
        val packets = mutableListOf<String>()
        val queue = queue(packets)
        repeat(36) { queue.enqueue("a".repeat(800)) }
        runCurrent()
        queue.enqueue("old".repeat(100), "tracks:task")
        runCurrent()
        queue.enqueue("new".repeat(200), "tracks:task")
        runCurrent()
        assertEquals(36, packets.size)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals("new".repeat(200), packets.last())
        assertEquals(37, packets.size)
        queue.close()
    }

    @Test
    public fun `discard keeps controls ordered and only removes matching keys`() = runTest {
        val packets = mutableListOf<String>()
        val queue = queue(packets)
        queue.enqueue("start")
        queue.enqueue("old", "tracks:old")
        queue.enqueue("keep", "tracks:new")
        queue.discardPendingMessages("tracks:old")
        queue.enqueue("stop")
        runCurrent()
        assertEquals(listOf("start", "keep", "stop"), packets)

        queue.enqueue("old1", "tracks:old")
        queue.enqueue("old2", "tracks:new")
        queue.discardPendingMessages()
        queue.enqueue("new generation")
        queue.enqueue("new trajectory", "tracks:next")
        runCurrent()
        assertEquals(listOf("start", "keep", "stop", "new generation", "new trajectory"), packets)
        queue.close()
    }

    @Test
    public fun `discarding last trajectory does not finish flush before earlier controls`() = runTest {
        val packets = mutableListOf<String>()
        val queue = queue(packets)
        repeat(58) { queue.enqueue("budget") }
        runCurrent()
        queue.enqueue("control")
        queue.enqueue("old", "tracks:task")
        val flushing = async { queue.flush() }
        runCurrent()
        queue.discardPendingMessages()
        runCurrent()
        assertFalse(flushing.isCompleted)
        advanceTimeBy(1_000)
        runCurrent()
        flushing.await()
        assertEquals("control", packets.last())
        assertFalse(packets.contains("old"))
        queue.close()
    }

    @Test
    public fun `replacement and discard never interrupt started fragments`() = runTest {
        val packets = mutableListOf<String>()
        lateinit var queue: RtcMessageQueue
        queue = RtcMessageQueue(backgroundScope, send = { bytes ->
            packets.add(bytes.toString(Charsets.UTF_8))
            if (packets.size == 1) {
                queue.enqueue("obsolete", "tracks:task")
                queue.discardPendingMessages()
                queue.enqueue("old", "tracks:task")
                queue.enqueue("latest", "tracks:task")
            }
        }, onFailure = { throw AssertionError(it) }, nowMillis = { testScheduler.currentTime })
        queue.enqueue("a".repeat(1_200), "tracks:task")
        runCurrent()
        assertEquals(3, packets.size)
        val first = JSONObject(packets[0])
        val second = JSONObject(packets[1])
        assertEquals(0, first.getInt("index"))
        assertEquals(1, second.getInt("index"))
        assertEquals(first.getString("eventId"), second.getString("eventId"))
        assertEquals("latest", packets.last())
        queue.close()
    }

    @Test
    public fun `close cancels pending results without reporting a room failure`() = runTest {
        val packets = mutableListOf<String>()
        val queue = queue(packets)
        repeat(58) { queue.enqueue("budget") }
        runCurrent()
        val pending = queue.enqueue("old", "tracks:task")
        runCurrent()
        queue.close()
        runCurrent()
        assertTrue(pending.isCancelled)
        assertTrue(runCatching { pending.await() }.exceptionOrNull() is CancellationException)
        assertEquals(58, packets.size)
    }

    @Test
    public fun `send failure fails all results flush and later submissions`() = runTest {
        val failure = IllegalStateException("RTC rejected packet")
        val failures = mutableListOf<Throwable>()
        val queue = RtcMessageQueue(backgroundScope, send = { throw failure }, onFailure = failures::add)
        val first = queue.enqueue("start")
        val second = queue.enqueue("tracks", "tracks:task")
        runCurrent()
        assertEquals(failure.message, runCatching { first.await() }.exceptionOrNull()?.message)
        assertEquals(failure.message, runCatching { second.await() }.exceptionOrNull()?.message)
        assertSame(failure, runCatching { queue.flush() }.exceptionOrNull())
        assertSame(failure, runCatching { queue.enqueue("stop") }.exceptionOrNull())
        assertEquals(listOf(failure), failures)
        queue.close()
    }

    private fun TestScope.queue(packets: MutableList<String>): RtcMessageQueue = RtcMessageQueue(
        scope = backgroundScope,
        send = { packets.add(it.toString(Charsets.UTF_8)) },
        onFailure = { throw AssertionError(it) },
        nowMillis = { testScheduler.currentTime },
    )
}
