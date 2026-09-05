package ai.xmax.sdk.rendering

import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import ai.xmax.sdk.foundation.rtc.RemoteStream
import ai.xmax.sdk.stream.room.RtcManagingStub
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RenderControllerTest {
    @Test
    fun `new remote frame resolves readiness without a bound view and releases sink`() = runTest {
        val rtc = RtcManagingStub()
        val controller = RenderController(rtc, remoteFrameReadyTimeoutMillis = 1_000L, renderDispatcher = StandardTestDispatcher(testScheduler))
        val stream = RemoteStream("room-id", "bot-id")
        controller.setRemoteStream(stream)

        val readiness = async { controller.waitUntilRemoteFrameReady() }
        runCurrent()
        rtc.emitRemoteVideoFrame(stream, 704, 1280)

        readiness.await()
        assertNull(rtc.captureRemoteVideoFrameListener(stream))
    }

    @Test
    fun `frame before SEI is not reused and invalid or unrelated frames do not make generation ready`() = runTest {
        val rtc = RtcManagingStub()
        val controller = RenderController(rtc, renderDispatcher = StandardTestDispatcher(testScheduler))
        val stream = RemoteStream("room-id", "bot-id")

        rtc.emitRemoteVideoFrame(stream, 704, 1280)
        controller.setRemoteStream(stream)
        val readiness = async { controller.waitUntilRemoteFrameReady() }
        runCurrent()
        rtc.emitRemoteVideoFrame(stream, 0, 1280)
        rtc.emitRemoteVideoFrame(stream, 704, -1)
        rtc.emitRemoteVideoFrame(RemoteStream("other-room", "bot-id"), 704, 1280)
        runCurrent()
        assertFalse(readiness.isCompleted)
        rtc.emitRemoteVideoFrame(stream, 704, 1280)
        readiness.await()
    }

    @Test
    fun `same stream needs a fresh frame each generation and rejects old registration callbacks`() = runTest {
        val rtc = RtcManagingStub()
        val controller = RenderController(rtc, renderDispatcher = StandardTestDispatcher(testScheduler))
        val stream = RemoteStream("room-id", "bot-id")
        controller.setRemoteStream(stream)
        val oldCallback = rtc.captureRemoteVideoFrameListener(stream)!!
        oldCallback(704, 1280)
        controller.waitUntilRemoteFrameReady()

        controller.setRemoteStream(stream)
        val readiness = async { controller.waitUntilRemoteFrameReady() }
        runCurrent()
        oldCallback(704, 1280)
        runCurrent()
        assertFalse(readiness.isCompleted)
        assertNotNull(rtc.captureRemoteVideoFrameListener(stream))
        rtc.emitRemoteVideoFrame(stream, 704, 1280)
        readiness.await()
    }

    @Test
    fun `reset cancels old waiters and old callbacks cannot complete a later generation`() = runTest {
        val rtc = RtcManagingStub()
        val controller = RenderController(rtc, renderDispatcher = StandardTestDispatcher(testScheduler))
        val stream = RemoteStream("room-id", "bot-id")
        controller.setRemoteStream(stream)
        val oldCallback = rtc.captureRemoteVideoFrameListener(stream)!!
        val oldWait = async { controller.waitUntilRemoteFrameReady() }
        runCurrent()
        controller.resetRemoteTrack(null)
        oldWait.join()
        assertTrue(oldWait.isCancelled)
        assertNull(rtc.captureRemoteVideoFrameListener(stream))

        controller.setRemoteStream(stream)
        val nextWait = async { controller.waitUntilRemoteFrameReady() }
        runCurrent()
        oldCallback(704, 1280)
        runCurrent()
        assertFalse(nextWait.isCompleted)
        controller.setRemoteStream(null)
        nextWait.join()
        assertTrue(nextWait.isCancelled)
    }

    @Test
    fun `generation replacement cancels pending first frame wait even for the same stream`() = runTest {
        val controller = RenderController(RtcManagingStub(), renderDispatcher = StandardTestDispatcher(testScheduler))
        val stream = RemoteStream("room-id", "bot-id")
        controller.setRemoteStream(stream)
        val oldWait = async { controller.waitUntilRemoteFrameReady() }
        runCurrent()
        controller.setRemoteStream(stream)
        oldWait.join()
        assertTrue(oldWait.isCancelled)
        controller.setRemoteStream(null)
    }

    @Test
    fun `timeout is an SDK error while caller cancellation remains cancellation`() = runTest {
        val controller = RenderController(RtcManagingStub(), remoteFrameReadyTimeoutMillis = 1_000, renderDispatcher = StandardTestDispatcher(testScheduler))
        controller.setRemoteStream(RemoteStream("room-id", "bot-id"))
        val wait = async { runCatching { controller.waitUntilRemoteFrameReady() } }
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(XmaxErrorCode.TIMEOUT, (wait.await().exceptionOrNull() as XmaxError).code)

        val cancelled = runCatching { withTimeout(100) { controller.waitUntilRemoteFrameReady() } }.exceptionOrNull()
        assertTrue(cancelled is CancellationException)
        controller.setRemoteStream(null)
    }

    @Test
    fun `sink registration and release failures are delivered through startup`() = runTest {
        val rtc = RtcManagingStub()
        val controller = RenderController(rtc, renderDispatcher = StandardTestDispatcher(testScheduler))
        val stream = RemoteStream("room-id", "bot-id")
        val failure = XmaxError(XmaxErrorCode.RTC_ERROR, "sink failed")
        rtc.remoteFrameRegistrationError = failure
        assertSame(failure, runCatching { controller.setRemoteStream(stream) }.exceptionOrNull())
        assertSame(failure, runCatching { controller.waitUntilRemoteFrameReady() }.exceptionOrNull())

        rtc.remoteFrameRegistrationError = null
        controller.setRemoteStream(stream)
        rtc.remoteFrameReleaseError = failure
        val wait = async { runCatching { controller.waitUntilRemoteFrameReady() } }
        runCurrent()
        // The callback must complete the wait exceptionally, rather than throw on the RTC thread.
        rtc.emitRemoteVideoFrame(stream, 704, 1280)
        assertSame(failure, wait.await().exceptionOrNull())
        rtc.remoteFrameReleaseError = null
        controller.setRemoteStream(null)
    }

    @Test
    fun `waiting without remote stream fails`() = runTest {
        val controller = RenderController(RtcManagingStub(), renderDispatcher = StandardTestDispatcher(testScheduler))

        val error = try {
            controller.waitUntilRemoteFrameReady()
            throw AssertionError("Expected XmaxError")
        } catch (error: XmaxError) {
            error
        }

        assertEquals(XmaxErrorCode.RTC_ERROR, error.code)
    }
}
