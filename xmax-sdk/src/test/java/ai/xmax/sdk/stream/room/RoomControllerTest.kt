package ai.xmax.sdk.stream.room

import ai.xmax.sdk.RealtimeContext
import ai.xmax.sdk.RealtimePoint
import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import ai.xmax.sdk.foundation.rtc.RoomJoinConfiguration
import ai.xmax.sdk.service.realtime.RealtimeSessionConnection
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
public class RoomControllerTest {
    @Test
    public fun `join forwards connection and leave releases room once`() = runTest {
        val rtcManager = RtcManagingStub()
        val heartbeat = RoomHeartbeat(
            rtcManager,
            sleeper = { awaitCancellation() },
            scope = this,
        )
        val controller = RoomController(rtcManager, heartbeat)

        controller.join(connection()) {}
        controller.leave()
        controller.leave()

        assertEquals(
            listOf(
                RtcManagingCall.JoinRoom(
                    RoomJoinConfiguration("room-id", "user-id", "room-token"),
                ),
                RtcManagingCall.LeaveRoom,
            ),
            rtcManager.calls.filterNot { it is RtcManagingCall.SendRoomMessage },
        )
    }

    @Test
    public fun `join rejects another room until leave`() = runTest {
        val rtcManager = RtcManagingStub()
        val controller = RoomController(
            rtcManager,
            RoomHeartbeat(rtcManager, sleeper = { awaitCancellation() }, scope = this),
        )
        controller.join(connection()) {}

        val error = expectXmaxError {
            controller.join(connection()) {}
        }

        assertEquals(XmaxErrorCode.INVALID_CONFIGURATION, error.code)
        assertEquals(
            "Leave the current RTC room before joining another one",
            error.message,
        )
        controller.leave()
    }

    @Test
    public fun `join failure rolls back room resources`() = runTest {
        val expected = XmaxError(XmaxErrorCode.RTC_ERROR, "join failed")
        val rtcManager = RtcManagingStub(joinRoomError = expected)
        val controller = RoomController(
            rtcManager,
            RoomHeartbeat(rtcManager, sleeper = { awaitCancellation() }, scope = this),
        )

        val error = expectXmaxError {
            controller.join(connection()) {}
        }

        assertTrue(error === expected)
        assertEquals(RtcManagingCall.LeaveRoom, rtcManager.calls.last())
    }

    @Test
    public fun `inactive join after async boundary leaves room`() = runTest {
        val rtcManager = RtcManagingStub()
        val controller = RoomController(
            rtcManager,
            RoomHeartbeat(rtcManager, sleeper = { awaitCancellation() }, scope = this),
        )
        var invocationCount = 0
        val expected = XmaxError(XmaxErrorCode.CANCELLED, "connection replaced")

        val error = expectXmaxError {
            controller.join(connection()) {
                invocationCount += 1
                if (invocationCount == 2) throw expected
            }
        }

        assertTrue(error === expected)
        assertEquals(2, invocationCount)
        assertEquals(RtcManagingCall.LeaveRoom, rtcManager.calls.last())
    }

    @Test
    public fun `generation signals use joined user and room protocol`() = runTest {
        val rtcManager = RtcManagingStub()
        val controller = RoomController(
            rtcManager,
            RoomHeartbeat(
                rtcManager,
                sleeper = { awaitCancellation() },
                scope = this,
            ),
        )
        controller.join(connection()) {}
        val format = RealtimeVideoFormat(720, 1280, 24)

        controller.startGeneration("task-id", format, RealtimeContext("first"))
        controller.changeGenerationCondition(
            "task-id",
            format,
            RealtimeContext("second"),
        )
        controller.sendTracks("task-id", listOf(RealtimePoint(10.0, 20.0)))
        controller.stopGeneration("task-id")

        assertEquals(
            listOf("start", "change_condition", "tracks", "stop"),
            rtcManager.roomMessages.map { JSONObject(it).getString("event") },
        )
        assertTrue(
            rtcManager.roomMessages.all {
                JSONObject(it).getString("user_id") == "user-id"
            },
        )
        controller.leave()
    }

    @Test
    public fun `generation signal requires joined room`() = runTest {
        val rtcManager = RtcManagingStub()
        val controller = RoomController(
            rtcManager,
            RoomHeartbeat(rtcManager, sleeper = { awaitCancellation() }, scope = this),
        )

        val error = expectXmaxError {
            controller.startGeneration(
                "task-id",
                RealtimeVideoFormat(720, 1280, 24),
                RealtimeContext("prompt"),
            )
        }

        assertEquals(XmaxErrorCode.RTC_ERROR, error.code)
        assertEquals("RTC room is not joined", error.message)
        assertTrue(rtcManager.roomMessages.isEmpty())
    }

    @Test
    public fun `empty optional signals are ignored`() = runTest {
        val rtcManager = RtcManagingStub()
        val controller = RoomController(
            rtcManager,
            RoomHeartbeat(rtcManager, sleeper = { awaitCancellation() }, scope = this),
        )
        controller.join(connection()) {}

        controller.stopGeneration("")
        controller.sendTracks("task-id", emptyList())
        runCurrent()

        assertTrue(rtcManager.roomMessages.isEmpty())
        controller.leave()
    }

    @Test
    public fun `room signal log identifies and pretty prints event`() {
        val controller = RoomController(RtcManagingStub())

        val message = controller.formatSignalLog(
            JSONObject()
                .put("event", "start")
                .put("user_id", "user-id")
                .toString(),
        )

        assertTrue(message.contains("Outbound Room Signaling"))
        assertTrue(message.contains("类型：start"))
        assertTrue(message.contains("\"user_id\""))
    }

    private fun connection(): RealtimeSessionConnection = RealtimeSessionConnection(
        roomId = "room-id",
        userId = "user-id",
        token = "room-token",
        botName = "bot-user",
    )

    private suspend fun expectXmaxError(block: suspend () -> Unit): XmaxError = try {
        block()
        throw AssertionError("Expected XmaxError")
    } catch (error: XmaxError) {
        error
    }
}
