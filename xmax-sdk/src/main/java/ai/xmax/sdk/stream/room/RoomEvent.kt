package ai.xmax.sdk.stream.room

import ai.xmax.sdk.RealtimeContext
import ai.xmax.sdk.RealtimePoint
import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import ai.xmax.sdk.foundation.runtime.RuntimeInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * 生成 SDK 与 RTC 房间之间传输的结构化事件消息。
 */
internal object RoomEvent {
    fun start(
        userId: String,
        taskId: String,
        videoFormat: RealtimeVideoFormat,
        context: RealtimeContext,
        minimumBitrate: Int,
        maximumBitrate: Int,
        faceRestoration: Boolean = false,
        targetVideoFormat: RealtimeVideoFormat? = null,
        paddingContain: Boolean = false,
    ): String = encode(
        event = "start",
        userId = userId,
        taskId = taskId,
        params = generationParameters(videoFormat, context, faceRestoration).apply {
            if (paddingContain) {
                if (targetVideoFormat == null || context.referenceVideo != null) {
                    throw XmaxError(XmaxErrorCode.INVALID_CONFIGURATION, "Contain padding requires a target size and a realtime frame input")
                }
                put("padding", "contain")
            }
            put("mirror", false)
            put("min_bitrate", minimumBitrate)
            put("max_bitrate", maximumBitrate)
            val target = targetVideoFormat ?: videoFormat.takeIf { context.referenceVideo != null }
            target?.let { put("target_size", JSONArray().put(it.width).put(it.height)) }
            context.referenceVideo?.let {
                put("ref_video_path", it.path)
                put("sample_method", it.sampleMethod.value)
            }
        },
    )

    /**
     * 编码回传尺寸与服务端码率更新信令，码率单位为 Kbps。
     */
    fun changeTargetSize(
        userId: String,
        taskId: String,
        targetVideoFormat: RealtimeVideoFormat,
        minimumBitrate: Int,
        maximumBitrate: Int,
    ): String = encode(
        event = "change_target_size",
        userId = userId,
        taskId = taskId,
        params = JSONObject()
            .put("target_size", JSONArray().put(targetVideoFormat.width).put(targetVideoFormat.height))
            .put("min_bitrate", minimumBitrate)
            .put("max_bitrate", maximumBitrate),
    )

    fun changeCondition(
        userId: String,
        taskId: String,
        videoFormat: RealtimeVideoFormat,
        context: RealtimeContext,
        faceRestoration: Boolean = false,
        paddingContain: Boolean = false,
    ): String = encode(
        event = "change_condition",
        userId = userId,
        taskId = taskId,
        params = generationParameters(videoFormat, context, faceRestoration).apply {
            if (paddingContain) put("padding", "contain")
        },
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
        faceRestoration: Boolean,
    ): JSONObject = JSONObject()
        .put("model", "default")
        .put("size", JSONArray().put(videoFormat.width).put(videoFormat.height))
        .put("prompt", context.prompt)
        .put("face_replace", faceRestoration)
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
                taskId?.let { put("uid", it); put("session_uid", it) }
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
