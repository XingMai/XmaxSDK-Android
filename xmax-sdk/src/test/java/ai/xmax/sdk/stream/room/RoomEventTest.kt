package ai.xmax.sdk.stream.room

import ai.xmax.sdk.RealtimeContext
import ai.xmax.sdk.RealtimePoint
import ai.xmax.sdk.RealtimeReferenceVideo
import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxSdk
import ai.xmax.sdk.foundation.runtime.RuntimeInfo
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

public class RoomEventTest {
    @Test
    public fun `contain start separates canvas and output and condition updates preserve output`() {
        val canvas = RealtimeVideoFormat(1920, 1024, 30)
        val target = RealtimeVideoFormat(1024, 576, 24)
        val context = RealtimeContext("first")
        val start = JSONObject(RoomEvent.start("user", "task", canvas, context, 100, 2000,
            targetVideoFormat = target, paddingContain = true)).getJSONObject("params")
        assertEquals("contain", start.getString("padding"))
        assertEquals("[1920,1024]", start.getJSONArray("size").toString())
        assertEquals("[1024,576]", start.getJSONArray("target_size").toString())
        val change = JSONObject(RoomEvent.changeCondition("user", "task", canvas, context, paddingContain = true)).getJSONObject("params")
        assertEquals("contain", change.getString("padding"))
        assertEquals("[1920,1024]", change.getJSONArray("size").toString())
        assertFalse(change.has("target_size"))
        assertFalse(JSONObject(RoomEvent.changeCondition("user", "task", canvas, context)).getJSONObject("params").has("padding"))
    }

    @Test
    public fun `contain start requires explicit target and excludes server video input`() {
        val canvas = RealtimeVideoFormat(1920, 1024, 30)
        assertThrows(XmaxError::class.java) {
            RoomEvent.start("user", "task", canvas, RealtimeContext("prompt"), 100, 2000, paddingContain = true)
        }
        val context = RealtimeContext("prompt", referenceVideo = RealtimeReferenceVideo("https://example.test/video.mp4"))
        assertThrows(XmaxError::class.java) {
            RoomEvent.start("user", "task", canvas, context, 100, 2000, targetVideoFormat = canvas, paddingContain = true)
        }
    }

    @Test
    public fun `network start sends image video sampling dimensions and session identity`() {
        val context = RealtimeContext("prompt", "reference.png",
            ai.xmax.sdk.RealtimeReferenceVideo("https://example.test/video.mp4?signature=abc", ai.xmax.sdk.RealtimeVideoSampleMethod.TIME))
        val format = RealtimeVideoFormat(704, 1280, 24)
        val event = JSONObject(RoomEvent.start("user", "task?os=android", format, context, 2000, 6000))
        val params = event.getJSONObject("params")
        assertEquals("https://example.test/video.mp4?signature=abc", params.getString("ref_video_path"))
        assertEquals("reference.png", params.getString("ref_image_path"))
        assertEquals("time", params.getString("sample_method"))
        assertEquals("[704,1280]", params.getJSONArray("target_size").toString())
        assertEquals("task?os=android", event.getString("session_uid"))
        val change = JSONObject(RoomEvent.changeCondition("user", "task", format, context))
        assertFalse(change.getJSONObject("params").has("ref_video_path"))
        assertEquals("task", JSONObject(RoomEvent.stop("user", "task")).getString("session_uid"))
        val fps = context.copy(referenceVideo = context.referenceVideo!!.copy(sampleMethod = ai.xmax.sdk.RealtimeVideoSampleMethod.FPS))
        assertEquals("fps", JSONObject(RoomEvent.start("user", "task", format, fps, 2000, 6000)).getJSONObject("params").getString("sample_method"))
    }

    @Test
    public fun `start event matches room protocol`() {
        val event = JSONObject(
            RoomEvent.start(
                userId = "user-id",
                taskId = "task-id",
                videoFormat = RealtimeVideoFormat(720, 1280, 24),
                minimumBitrate = 1500,
                maximumBitrate = 3000,
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
        assertFalse(params.getBoolean("face_replace"))
        assertEquals(1500, params.getInt("min_bitrate"))
        assertEquals(3000, params.getInt("max_bitrate"))
        assertFalse(params.has("target_size"))
        assertFalse(params.has("fps"))
    }

    @Test
    public fun `start event omits missing reference path`() {
        val event = JSONObject(
            RoomEvent.start(
                userId = "user-id",
                taskId = "task-id",
                videoFormat = RealtimeVideoFormat(512, 512, 24),
                minimumBitrate = 500,
                maximumBitrate = 1000,
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
        assertFalse(event.getJSONObject("params").getBoolean("face_replace"))
        assertFalse(event.has("condition_version"))
        assertFalse(event.getJSONObject("params").has("min_bitrate"))
        assertFalse(event.getJSONObject("params").has("max_bitrate"))
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

    @Test
    public fun `face restoration is explicitly serialized for start and condition changes`() {
        val format = RealtimeVideoFormat(1024, 1920, 30)
        val context = RealtimeContext("try on", "reference.png")
        for (enabled in listOf(true, false)) {
            val start = JSONObject(RoomEvent.start("user", "task", format, context, 2000, 6000, enabled))
            val change = JSONObject(RoomEvent.changeCondition("user", "task", format, context, enabled))
            for (event in listOf(start, change)) {
                assertEquals(enabled, event.getJSONObject("params").getBoolean("face_replace"))
            }
        }
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
