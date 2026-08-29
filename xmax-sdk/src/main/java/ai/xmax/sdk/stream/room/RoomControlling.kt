package ai.xmax.sdk.stream.room

import ai.xmax.sdk.RealtimeContext
import ai.xmax.sdk.RealtimePoint
import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.service.realtime.RealtimeSessionConnection

/** 定义 RTC 房间生命周期和业务信令发送能力。 */
internal interface RoomControlling {
    suspend fun join(
        connection: RealtimeSessionConnection,
        ensureActive: () -> Unit,
    )

    suspend fun leave()

    fun startGeneration(
        taskId: String,
        videoFormat: RealtimeVideoFormat,
        context: RealtimeContext,
    )

    fun changeGenerationCondition(
        taskId: String,
        videoFormat: RealtimeVideoFormat,
        context: RealtimeContext,
    )

    fun stopGeneration(taskId: String)

    fun sendTracks(
        taskId: String,
        points: List<RealtimePoint>,
    )
}
