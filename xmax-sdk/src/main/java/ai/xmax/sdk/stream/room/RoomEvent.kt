package ai.xmax.sdk.stream.room

import ai.xmax.sdk.RealtimeContext
import ai.xmax.sdk.RealtimePoint
import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import ai.xmax.sdk.foundation.runtime.RuntimeInfo
import org.json.JSONArray
import org.json.JSONObject

/** 生成 SDK 与 RTC 房间之间传输的结构化事件消息。 */
internal object RoomEvent {
    fun start(
        userId: String,
        taskId: String,
        videoFormat: RealtimeVideoFormat,
        context: RealtimeContext,
    ): String = encode(
        event = "start",
        userId = userId,
        taskId = taskId,
        params = generationParameters(videoFormat, context),
    )

    fun changeCondition(
        userId: String,
        taskId: String,
        videoFormat: RealtimeVideoFormat,
        context: RealtimeContext,
    ): String = encode(
        event = "change_condition",
        userId = userId,
        taskId = taskId,
        params = generationParameters(videoFormat, context),
    )

    fun stop(
        userId: String,
        taskId: String,
    ): String = encode(
        event = "stop",
        userId = userId,
        taskId = taskId,
    )

    fun tracks(
        userId: String,
        taskId: String,
        points: List<RealtimePoint>,
    ): String = encode(
        event = "tracks",
        userId = userId,
        taskId = taskId,
        tracks = JSONArray().apply {
            points.forEach { point ->
                put(JSONArray().put(point.x).put(point.y))
            }
        },
    )

    fun heartbeat(userId: String): String = encode(
        event = "heartbeat",
        userId = userId,
    )

    private fun generationParameters(
        videoFormat: RealtimeVideoFormat,
        context: RealtimeContext,
    ): JSONObject = JSONObject()
        .put("model", "default")
        .put("size", JSONArray().put(videoFormat.width).put(videoFormat.height))
        .put("prompt", context.prompt)
        .apply {
            context.referencePath?.let { put("ref_image_path", it) }
        }

    private fun encode(
        event: String,
        userId: String,
        taskId: String? = null,
        params: JSONObject? = null,
        tracks: JSONArray? = null,
    ): String = try {
        JSONObject()
            .put("event", event)
            .put("user_id", userId)
            .put("runtime", RuntimeInfo.current.toJson())
            .apply {
                taskId?.let { put("uid", it) }
                params?.let { put("params", it) }
                tracks?.let { put("tracks", it) }
            }
            .toString()
    } catch (error: Throwable) {
        throw XmaxError(
            code = XmaxErrorCode.INTERNAL_ERROR,
            message = "Failed to encode RTC room event",
            cause = error,
        )
    }
}
