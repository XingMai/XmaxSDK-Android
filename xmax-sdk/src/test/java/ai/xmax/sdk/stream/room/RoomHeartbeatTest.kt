package ai.xmax.sdk.stream.room

import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
public class RoomHeartbeatTest {
    @Test
    public fun `heartbeat sends current user after interval`() = runTest {
        val rtcManager = RtcManagingStub()
        val sleeper = ManualRoomHeartbeatSleeper()
        val heartbeat = RoomHeartbeat(
            rtcManager = rtcManager,
            sleeper = sleeper::sleep,
            scope = this,
        )

        heartbeat.start("user-id")
        runCurrent()
        sleeper.resume()
        runCurrent()
        heartbeat.stop()

        val event = JSONObject(rtcManager.roomMessages.single())
        assertEquals("heartbeat", event.getString("event"))
        assertEquals("user-id", event.getString("user_id"))
    }

    @Test
    public fun `stop prevents waiting heartbeat from sending`() = runTest {
        val rtcManager = RtcManagingStub()
        val sleeper = ManualRoomHeartbeatSleeper()
        val heartbeat = RoomHeartbeat(
            rtcManager = rtcManager,
            sleeper = sleeper::sleep,
            scope = this,
        )

        heartbeat.start("user-id")
        runCurrent()
        heartbeat.stop()
        sleeper.resume()
        runCurrent()

        assertTrue(rtcManager.roomMessages.isEmpty())
    }

    @Test
    public fun `heartbeat continues after send failure`() = runTest {
        val rtcManager = RtcManagingStub(
            sendRoomMessageError = XmaxError(
                code = XmaxErrorCode.RTC_ERROR,
                message = "send failed",
            ),
        )
        val sleeper = ManualRoomHeartbeatSleeper()
        val heartbeat = RoomHeartbeat(
            rtcManager = rtcManager,
            sleeper = sleeper::sleep,
            scope = this,
        )

        heartbeat.start("user-id")
        runCurrent()
        sleeper.resume()
        runCurrent()
        sleeper.resume()
        runCurrent()
        heartbeat.stop()

        assertEquals(2, rtcManager.roomMessages.size)
    }
}

private class ManualRoomHeartbeatSleeper {
    private val resumes = Channel<Unit>(Channel.UNLIMITED)

    suspend fun sleep() {
        resumes.receive()
    }

    fun resume() {
        resumes.trySend(Unit).getOrThrow()
    }
}
