package ai.xmax.sdk.stream.room

import ai.xmax.sdk.RealtimeContext
import ai.xmax.sdk.RealtimePoint
import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.service.realtime.RealtimeSessionConnection

/**
 * 定义 RTC 房间生命周期和业务信令发送能力。
 */
internal interface RoomControlling {
    suspend fun join(
        connection: RealtimeSessionConnection,
        ensureActive: () -> Unit,
    )

    suspend fun leave()

    /**
     * 启动生成并配置下行码率；未指定回传尺寸时使用生成尺寸，码率按固定 24 fps 估算。
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
     */
    fun changeTargetSize(taskId: String, targetVideoFormat: RealtimeVideoFormat, ensureActive: () -> Unit)

    fun changeGenerationCondition(
        taskId: String,
        videoFormat: RealtimeVideoFormat,
        context: RealtimeContext,
        faceRestoration: Boolean = false,
        paddingContain: Boolean = false,
    )

    fun stopGeneration(taskId: String)

    fun sendTracks(
        taskId: String,
        points: List<RealtimePoint>,
    )
}
