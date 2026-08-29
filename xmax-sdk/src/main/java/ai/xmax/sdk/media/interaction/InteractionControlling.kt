package ai.xmax.sdk.media.interaction

import ai.xmax.sdk.RealtimeVideoFormat

/** 管理生成任务期间的轨迹交互输入。 */
internal interface InteractionControlling {
    suspend fun startInteraction(taskId: String, videoFormat: RealtimeVideoFormat)

    suspend fun stopInteraction()

    fun submitInteraction(frame: InteractionFrame)
}
