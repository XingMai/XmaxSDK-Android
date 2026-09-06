package ai.xmax.sdk.render.video

import ai.xmax.sdk.RealtimeVideoFrame
import ai.xmax.sdk.foundation.rtc.RtcRemoteVideoFrame
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RealtimeVideoFrameDispatcherTest {
    @Test
    fun `listener replacement generation change and clear discard queued pixels`() = runTest {
        val dispatcher = RealtimeVideoFrameDispatcher(StandardTestDispatcher(testScheduler))
        val generation = Any()
        val nextGeneration = Any()
        val received = mutableListOf<Long>()
        dispatcher.setGeneration(generation)
        val frame = Frame(1)
        dispatcher.dispatch(generation, frame)
        assertEquals(0, frame.copies)
        dispatcher.setListener { received += it.presentationTimeUs }
        dispatcher.dispatch(generation, frame)
        dispatcher.setListener { received += it.presentationTimeUs + 10 }
        runCurrent()
        assertTrue(received.isEmpty())
        dispatcher.dispatch(generation, frame)
        dispatcher.setGeneration(nextGeneration)
        dispatcher.dispatch(generation, frame)
        assertEquals(2, frame.copies)
        runCurrent()
        assertTrue(received.isEmpty())
        dispatcher.dispatch(nextGeneration, Frame(2))
        runCurrent()
        assertEquals(listOf(12L), received)
        dispatcher.dispatch(nextGeneration, Frame(3))
        dispatcher.setListener(null)
        runCurrent()
        assertEquals(listOf(12L), received)
    }

    @Test
    fun `slow listeners receive latest pending frame and listener exceptions do not kill delivery`() = runTest {
        val dispatcher = RealtimeVideoFrameDispatcher(StandardTestDispatcher(testScheduler))
        val generation = Any()
        val received = mutableListOf<Long>()
        dispatcher.setGeneration(generation)
        dispatcher.setListener {
            received += it.presentationTimeUs
            if (it.presentationTimeUs == 1L) {
                repeat(100) { index -> dispatcher.dispatch(generation, Frame(index + 2L)) }
                throw AssertionError("consumer failed")
            }
        }
        dispatcher.dispatch(generation, Frame(1))
        runCurrent()
        assertEquals(listOf(1L, 101L), received)
    }

    @Test
    fun `production dispatcher runs off caller thread and never overlaps callbacks`() {
        val dispatcher = RealtimeVideoFrameDispatcher()
        val generation = Any()
        val caller = Thread.currentThread()
        val firstEntered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val completed = CountDownLatch(2)
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val error = AtomicReference<Throwable?>()
        dispatcher.setGeneration(generation)
        dispatcher.setListener {
            try {
                assertNotSame(caller, Thread.currentThread())
                maximum.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                if (it.presentationTimeUs == 1L) {
                    firstEntered.countDown()
                    check(unblock.await(5, TimeUnit.SECONDS))
                }
            } catch (failure: Throwable) { error.set(failure) }
            finally { active.decrementAndGet(); completed.countDown() }
        }
        try {
            dispatcher.dispatch(generation, Frame(1))
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS))
            repeat(100) { dispatcher.dispatch(generation, Frame(it + 2L)) }
        } finally { unblock.countDown() }
        assertTrue(completed.await(5, TimeUnit.SECONDS))
        dispatcher.setListener(null)
        error.get()?.let { throw it }
        assertEquals(1, maximum.get())
    }

    private class Frame(private val timestamp: Long) : RtcRemoteVideoFrame {
        var copies = 0
        override val width = 2
        override val height = 2
        override fun copy(): RealtimeVideoFrame {
            copies++
            return RealtimeVideoFrame(2, 2, timestamp, null, 0, ByteArray(4), ByteArray(1), ByteArray(1))
        }
    }
}
