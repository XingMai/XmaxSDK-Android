//
//  RoomEvent.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

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
    /**
     * 构造启动实时生成的房间信令。
     *
     * @param userId 房间中的业务用户标识。
     * @param taskId 用于关联本次生成请求和结果流的任务标识。
     * @param videoFormat 视频尺寸、帧率及上行编码参数。
     * @param context 生成提示词及参考素材。
     * @param minimumBitrate 最低编码码率，单位为 kbps。
     * @param maximumBitrate 最高编码码率，单位为 kbps。
     * @param faceRestoration 是否启用人脸修复，对应生成参数 `face_replace`；默认值为 `false`。
     * @param targetVideoFormat 下行输出的视频规格。
     * @param paddingContain 是否让服务端等比适配输入并在输出时移除补边；默认值为 `false`。
     * @return 视频开始生成的 JSON 信令。
     */
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
     *
     * @param userId 房间中的业务用户标识。
     * @param taskId 用于关联本次生成请求和结果流的任务标识。
     * @param targetVideoFormat 下行输出的视频规格。
     * @param minimumBitrate 最低编码码率，单位为 kbps。
     * @param maximumBitrate 最高编码码率，单位为 kbps。
     * @return 更新下行尺寸与码率的 JSON 信令。
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

    /**
     * 构造更新生成条件的房间信令。
     *
     * @param userId 房间中的业务用户标识。
     * @param taskId 用于关联本次生成请求和结果流的任务标识。
     * @param videoFormat 视频尺寸、帧率及上行编码参数。
     * @param context 生成提示词及参考素材。
     * @param faceRestoration 是否启用人脸修复，对应生成参数 `face_replace`；默认值为 `false`。
     * @param paddingContain 是否让服务端等比适配输入并在输出时移除补边；默认值为 `false`。
     * @return 更新生成条件的 JSON 信令。
     */
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

    /**
     * 构造停止指定生成任务的房间信令。
     *
     * @param userId 房间中的业务用户标识。
     * @param taskId 用于关联本次生成请求和结果流的任务标识。
     * @return 停止生成的 JSON 信令。
     */
    fun stop(
        userId: String,
        taskId: String,
    ): String = encode(
        event = "stop",
        userId = userId,
        taskId = taskId,
    )

    /**
     * 构造携带触控轨迹的房间信令。
     *
     * @param userId 房间中的业务用户标识。
     * @param taskId 用于关联本次生成请求和结果流的任务标识。
     * @param points 媒体归一化坐标中的轨迹点集合，范围为 [0, 1]。
     * @return 携带触控轨迹的 JSON 信令。
     */
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

    /**
     * 构造维持房间活跃状态的心跳信令。
     *
     * @param userId 房间中的业务用户标识。
     * @return 房间心跳 JSON 信令。
     */
    fun heartbeat(userId: String): String = encode(
        event = "heartbeat",
        userId = userId,
    )

    /**
     * 构造生成尺寸、提示词、参考素材及人脸修复参数。
     *
     * @param videoFormat 视频尺寸、帧率及上行编码参数。
     * @param context 生成提示词及参考素材。
     * @param faceRestoration 是否启用人脸修复，对应生成参数 `face_replace`。
     * @return 供当前业务使用的 JSON 数据。
     */
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

    /**
     * 构造包含事件名称、用户、任务和业务参数的 JSON 信令。
     *
     * @param event 业务信令事件名称。
     * @param userId 房间中的业务用户标识。
     * @param taskId 用于关联本次生成请求和结果流的任务标识。
     * @param params 业务信令参数；null 表示不携带参数对象。
     * @param tracks 轨迹坐标 JSON 数组；null 表示不携带轨迹。
     * @return 编码后的 JSON 信令。
     */
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
