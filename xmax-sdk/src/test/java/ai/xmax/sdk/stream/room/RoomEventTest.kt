package ai.xmax.sdk.stream.room

import ai.xmax.sdk.RealtimeContext
import ai.xmax.sdk.RealtimePoint
import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.XmaxSdk
import ai.xmax.sdk.foundation.runtime.RuntimeInfo
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

public class RoomEventTest {
    @Test
    public fun `start event matches room protocol`() {
        val event = JSONObject(
            RoomEvent.start(
                userId = "user-id",
                taskId = "task-id",
                videoFormat = RealtimeVideoFormat(720, 1280, 24),
                context = RealtimeContext(
                    prompt = "a prompt",
                    referencePath = "cos/reference.png",
                ),
            ),
        )

        assertEquals("start", event.getString("event"))
        assertEquals("user-id", event.getString("user_id"))
        assertEquals("task-id", event.getString("uid"))
        assertRuntime(event)
        val params = event.getJSONObject("params")
        assertEquals("default", params.getString("model"))
        assertEquals(720, params.getJSONArray("size").getInt(0))
        assertEquals(1280, params.getJSONArray("size").getInt(1))
        assertEquals("a prompt", params.getString("prompt"))
        assertEquals("cos/reference.png", params.getString("ref_image_path"))
    }

    @Test
    public fun `start event omits missing reference path`() {
        val event = JSONObject(
            RoomEvent.start(
                userId = "user-id",
                taskId = "task-id",
                videoFormat = RealtimeVideoFormat(512, 512, 24),
                context = RealtimeContext(prompt = "a prompt"),
            ),
        )

        assertFalse(event.getJSONObject("params").has("ref_image_path"))
    }

    @Test
    public fun `change condition event matches room protocol`() {
        val event = JSONObject(
            RoomEvent.changeCondition(
                userId = "user-id",
                taskId = "task-id",
                videoFormat = RealtimeVideoFormat(720, 1280, 24),
                context = RealtimeContext(prompt = "new prompt"),
            ),
        )

        assertEquals("change_condition", event.getString("event"))
        assertFalse(event.has("condition_version"))
        assertRuntime(event)
    }

    @Test
    public fun `stop tracks and heartbeat events match room protocol`() {
        val stop = JSONObject(RoomEvent.stop("user-id", "task-id"))
        val tracks = JSONObject(
            RoomEvent.tracks(
                userId = "user-id",
                taskId = "task-id",
                points = listOf(
                    RealtimePoint(12.5, 30.0),
                    RealtimePoint(14.0, 32.5),
                ),
            ),
        )
        val heartbeat = JSONObject(RoomEvent.heartbeat("user-id"))

        assertEquals("stop", stop.getString("event"))
        assertEquals("user-id", stop.getString("user_id"))
        assertEquals("task-id", stop.getString("uid"))
        assertEquals(12.5, tracks.getJSONArray("tracks").getJSONArray(0).getDouble(0), 0.0)
        assertEquals(30.0, tracks.getJSONArray("tracks").getJSONArray(0).getDouble(1), 0.0)
        assertEquals("heartbeat", heartbeat.getString("event"))
        assertEquals("user-id", heartbeat.getString("user_id"))
        listOf(stop, tracks, heartbeat).forEach(::assertRuntime)
    }

    private fun assertRuntime(event: JSONObject) {
        val runtime = event.getJSONObject("runtime")
        assertEquals(setOf("platform", "os_version", "sdk_version", "device_model"), runtime.keys().asSequence().toSet())
        assertEquals("android", runtime.getString("platform"))
        assertEquals(RuntimeInfo.current.osVersion, runtime.getString("os_version"))
        assertEquals(XmaxSdk.VERSION, runtime.getString("sdk_version"))
        assertEquals(RuntimeInfo.current.deviceModel, runtime.getString("device_model"))
        assertFalse(runtime.getString("os_version").isBlank())
        assertFalse(runtime.getString("device_model").isBlank())
    }
}
