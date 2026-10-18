//
//  RoomControlling.kt
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
import ai.xmax.sdk.service.realtime.RealtimeSessionConnection

/**
 * 定义 RTC 房间生命周期和业务信令发送能力。
 */
internal interface RoomControlling {
    /**
     * 加入 RTC 房间并启动房间心跳；调用完成时房间已可发送信令。
     *
     * @param connection 服务端返回的 RTC 房间、用户、鉴权及结果流信息。
     * @param ensureActive 检查当前操作是否仍可继续的函数，取消时应抛出异常。
     */
    suspend fun join(
        connection: RealtimeSessionConnection,
        ensureActive: () -> Unit,
    )

    /**
     * 停止房间心跳并离开当前 RTC 房间。
     */
    suspend fun leave()

    /**
     * 发送包含输入规格、生成条件和回传尺寸的启动信令。
     *
     * @param taskId 用于关联本次生成请求和结果流的任务标识。
     * @param videoFormat 视频尺寸、帧率及上行编码参数。
     * @param context 生成提示词及参考素材。
     * @param faceRestoration 是否启用人脸修复，对应生成参数 `face_replace`；默认值为 `false`。
     * @param targetVideoFormat 下行输出的视频规格。
     * @param paddingContain 是否让服务端等比适配输入并在输出时移除补边；默认值为 `false`。
     */
    fun startGeneration(
        taskId: String,
        videoFormat: RealtimeVideoFormat,
        context: RealtimeContext,
        faceRestoration: Boolean = false,
        targetVideoFormat: RealtimeVideoFormat? = null,
        paddingContain: Boolean = false,
    )

    /**
     * 更新回传尺寸与码率；发送前检查操作有效性。
     *
     * @param taskId 用于关联本次生成请求和结果流的任务标识。
     * @param targetVideoFormat 下行输出的视频规格。
     * @param ensureActive 检查当前操作是否仍可继续的函数，取消时应抛出异常。
     */
    fun changeTargetSize(taskId: String, targetVideoFormat: RealtimeVideoFormat, ensureActive: () -> Unit)

    /**
     * 发送当前任务的新生成条件。
     *
     * @param taskId 用于关联本次生成请求和结果流的任务标识。
     * @param videoFormat 视频尺寸、帧率及上行编码参数。
     * @param context 生成提示词及参考素材。
     * @param faceRestoration 是否启用人脸修复，对应生成参数 `face_replace`；默认值为 `false`。
     * @param paddingContain 是否让服务端等比适配输入并在输出时移除补边；默认值为 `false`。
     */
    fun changeGenerationCondition(
        taskId: String,
        videoFormat: RealtimeVideoFormat,
        context: RealtimeContext,
        faceRestoration: Boolean = false,
        paddingContain: Boolean = false,
    )

    /**
     * 发送停止指定生成任务的信令。
     *
     * @param taskId 用于关联本次生成请求和结果流的任务标识。
     */
    fun stopGeneration(taskId: String)

    /**
     * 向服务端发送当前任务的媒体归一化坐标轨迹。
     *
     * @param taskId 用于关联本次生成请求和结果流的任务标识。
     * @param points 媒体归一化坐标中的轨迹点集合，范围为 [0, 1]。
     */
    fun sendTracks(
        taskId: String,
        points: List<RealtimePoint>,
    )
}
