package ai.xmax.sdk.foundation.runtime

import ai.xmax.sdk.RealtimeContext
import ai.xmax.sdk.RealtimePoint
import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.XmaxSdk
import ai.xmax.sdk.service.network.ApiHttpRequest
import ai.xmax.sdk.service.network.ApiHttpResponse
import ai.xmax.sdk.service.network.ApiMethod
import ai.xmax.sdk.service.network.ApiService
import ai.xmax.sdk.service.network.ApiTransport
import ai.xmax.sdk.stream.room.RoomEvent
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies Android platform values at both outgoing boundaries without making network calls. */
@RunWith(AndroidJUnit4::class)
public class RuntimeInfoTest {
    @Test
    public fun apiAndRoomMessagesUseCurrentAndroidRuntime() = runBlocking {
        val expected = mapOf(
            "platform" to "android",
            "os_version" to Build.VERSION.RELEASE.trim(),
            "sdk_version" to XmaxSdk.VERSION,
            "device_model" to Build.MODEL.trim(),
        )
        expected.values.forEach { assertFalse(it.isBlank()) }

        val requests = mutableListOf<ApiHttpRequest>()
        val service = ApiService(
            apiKey = "test-key",
            transport = ApiTransport { request ->
                requests += request
                ApiHttpResponse(200, """{"success":true,"data":{}}""".toByteArray())
            },
        )
        ApiMethod.entries.forEach { service.request(it, "/runtime-check") }
        assertEquals(4, requests.size)
        requests.forEach { request ->
            assertEquals(expected["platform"], request.headers["X-Platform"])
            assertEquals(expected["os_version"], request.headers["X-OS-Version"])
            assertEquals(expected["sdk_version"], request.headers["X-SDK-Version"])
            assertEquals(expected["device_model"], request.headers["X-Device-Model"])
        }

        val format = RealtimeVideoFormat(720, 1280, 24)
        val context = RealtimeContext("prompt")
        val events = listOf(
            RoomEvent.start("user", "task", format, context),
            RoomEvent.changeCondition("user", "task", format, context),
            RoomEvent.stop("user", "task"),
            RoomEvent.tracks("user", "task", listOf(RealtimePoint(1.0, 2.0))),
            RoomEvent.heartbeat("user"),
        )
        events.forEach { message ->
            val runtime = JSONObject(message).getJSONObject("runtime")
            assertEquals(expected.keys, runtime.keys().asSequence().toSet())
            expected.forEach { (key, value) -> assertEquals(value, runtime.getString(key)) }
        }
    }
}
