package ai.xmax.sdk.stream.room

import ai.xmax.sdk.RealtimeContext
import ai.xmax.sdk.RealtimeReferenceVideo
import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.service.realtime.RealtimeSessionConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DownlinkEncodingTest {
    @Test
    fun `start uses target dimensions and ignores explicit uplink bitrates`() = runTest {
        val rtc = RtcManagingStub()
        val room = RoomController(rtc, RoomHeartbeat(rtc, sleeper = { awaitCancellation() }, scope = backgroundScope))
        room.join(RealtimeSessionConnection("room", "user", "token", "bot")) {}
        val source = RealtimeVideoFormat(1024, 1920, 30, minimumBitrate = 9000, maximumBitrate = 9500)
        val target = RealtimeVideoFormat(640, 480, 30, minimumBitrate = 8000, maximumBitrate = 9000)

        for (context in listOf(RealtimeContext("camera"), RealtimeContext("network", referenceVideo = RealtimeReferenceVideo("https://example.test/video.mp4")))) {
            room.startGeneration("task", source, context, targetVideoFormat = target)
            val params = messages(rtc).last().getJSONObject("params")
            assertEquals("[1024,1920]", params.getJSONArray("size").toString())
            assertEquals("[640,480]", params.getJSONArray("target_size").toString())
            assertEquals(100, params.getInt("min_bitrate"))
            assertEquals(780, params.getInt("max_bitrate"))
            assertFalse(params.has("fps"))
        }

        room.startGeneration("task", source, RealtimeContext("fallback"))
        val params = messages(rtc).last().getJSONObject("params")
        assertFalse(params.has("target_size"))
        assertEquals(100, params.getInt("min_bitrate"))
        assertEquals(3127, params.getInt("max_bitrate"))
        room.leave()
    }

    @Test
    fun `start and size changes use fixed minimum and bounded maximum bitrate`() = runTest {
        val rtc = RtcManagingStub()
        val room = RoomController(rtc, RoomHeartbeat(rtc, sleeper = { awaitCancellation() }, scope = backgroundScope))
        room.join(RealtimeSessionConnection("room", "user", "token", "bot")) {}
        val cases = listOf(
            Triple(RealtimeVideoFormat(2, 2, 1), 100, 100),
            Triple(RealtimeVideoFormat(1920, 1080, 24), 100, 3266),
            Triple(RealtimeVideoFormat(3840, 2160, 30), 100, 10000),
        )

        for ((format, minimum, maximum) in cases) {
            room.startGeneration("task", format, RealtimeContext("prompt"))
            room.changeTargetSize("task", format) {}
            val events = messages(rtc).takeLast(2)
            assertEquals(listOf("start", "change_target_size"), events.map { it.getString("event") })
            for (event in events) {
                assertEquals("user", event.getString("user_id"))
                assertEquals("task", event.getString("uid"))
                assertEquals("task", event.getString("session_uid"))
                val params = event.getJSONObject("params")
                assertEquals(minimum, params.getInt("min_bitrate"))
                assertEquals(maximum, params.getInt("max_bitrate"))
            }
            val change = events.last().getJSONObject("params")
            assertEquals(setOf("target_size", "min_bitrate", "max_bitrate"), change.keys().asSequence().toSet())
            assertEquals("[${format.width},${format.height}]", change.getJSONArray("target_size").toString())
        }
        room.leave()
    }

    @Test
    fun `invalid target and cancelled updates never send messages`() = runTest {
        val rtc = RtcManagingStub()
        val room = RoomController(rtc, RoomHeartbeat(rtc, sleeper = { awaitCancellation() }, scope = backgroundScope))
        room.join(RealtimeSessionConnection("room", "user", "token", "bot")) {}
        val format = RealtimeVideoFormat(640, 480, 30)
        val count = messages(rtc).size

        for (invalid in listOf(format.copy(width = 0), format.copy(height = 481))) {
            assertTrue(runCatching { room.startGeneration("task", format, RealtimeContext("prompt"), targetVideoFormat = invalid) }.exceptionOrNull() is XmaxError)
            assertTrue(runCatching { room.changeTargetSize("task", invalid) {} }.exceptionOrNull() is XmaxError)
        }

        val cancelled = CancellationException("cancelled")
        var checks = 0
        val failure = runCatching {
            room.changeTargetSize("task", format) {
                if (++checks == 2) throw cancelled
            }
        }.exceptionOrNull()
        assertSame(cancelled, failure)
        assertEquals(count, messages(rtc).size)
        room.leave()
        assertTrue(runCatching { room.changeTargetSize("task", format) {} }.exceptionOrNull() is XmaxError)
    }

    @Test
    fun `downlink bitrate uses fixed 24 fps regardless of local frame rate`() = runTest {
        val rtc = RtcManagingStub()
        val room = RoomController(rtc, RoomHeartbeat(rtc, sleeper = { awaitCancellation() }, scope = backgroundScope))
        room.join(RealtimeSessionConnection("room", "user", "token", "bot")) {}

        for (fps in listOf(15, 24, 30, 60)) {
            val format = RealtimeVideoFormat(1024, 1920, fps)
            room.startGeneration("task", format, RealtimeContext("prompt"))
            room.startGeneration("task", format, RealtimeContext("prompt"), targetVideoFormat = format)
            room.changeTargetSize("task", format) {}

            for (event in messages(rtc).takeLast(3)) {
                val params = event.getJSONObject("params")
                assertEquals(100, params.getInt("min_bitrate"))
                assertEquals(3127, params.getInt("max_bitrate"))
                assertFalse(params.has("fps"))
            }
        }
        room.leave()
    }

    private fun messages(rtc: RtcManagingStub): List<JSONObject> =
        rtc.calls.filterIsInstance<RtcManagingCall.SendRoomMessage>().map { JSONObject(it.message) }
}
