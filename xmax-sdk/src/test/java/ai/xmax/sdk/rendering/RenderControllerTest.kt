package ai.xmax.sdk.rendering

import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import ai.xmax.sdk.foundation.rtc.RemoteStream
import ai.xmax.sdk.stream.room.RtcManagingStub
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RenderControllerTest {
    @Test
    fun `decoded remote frame resolves readiness without a bound view`() = runTest {
        val rtc = RtcManagingStub()
        val controller = RenderController(rtc, remoteFrameReadyTimeoutMillis = 1_000L)
        val stream = RemoteStream("room-id", "bot-id")
        controller.setRemoteStream(stream)

        val readiness = async { controller.waitUntilRemoteFrameReady() }
        runCurrent()
        rtc.emitRemoteVideoFrameReady(stream, 704, 1280)

        readiness.await()
    }

    @Test
    fun `decoded frame received before SEI stream selection remains ready`() = runTest {
        val rtc = RtcManagingStub()
        val controller = RenderController(rtc)
        val stream = RemoteStream("room-id", "bot-id")

        rtc.emitRemoteVideoFrameReady(stream, 704, 1280)
        controller.setRemoteStream(stream)

        controller.waitUntilRemoteFrameReady()
    }

    @Test
    fun `waiting without remote stream fails`() = runTest {
        val controller = RenderController(RtcManagingStub())

        val error = try {
            controller.waitUntilRemoteFrameReady()
            throw AssertionError("Expected XmaxError")
        } catch (error: XmaxError) {
            error
        }

        assertEquals(XmaxErrorCode.RTC_ERROR, error.code)
    }
}
