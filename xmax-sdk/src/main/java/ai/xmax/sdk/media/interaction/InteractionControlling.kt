//
//  InteractionControlling.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.media.interaction

import ai.xmax.sdk.RealtimeVideoFormat

/**
 * 管理生成任务期间的轨迹交互输入。
 */
internal interface InteractionControlling {
    /**
     * 为指定生成任务启用交互轨迹提交。
     *
     * @param taskId 用于关联本次生成请求和结果流的任务标识。
     * @param videoFormat 视频尺寸、帧率及上行编码参数。
     */
    suspend fun startInteraction(taskId: String, videoFormat: RealtimeVideoFormat)

    /**
     * 停止当前任务的交互轨迹提交并丢弃未发送的数据。
     */
    suspend fun stopInteraction()

    /**
     * 提交当前显示画面对应的触控轨迹。
     *
     * @param frame 当前触控轨迹及显示画面尺寸。
     */
    fun submitInteraction(frame: InteractionFrame)
}
