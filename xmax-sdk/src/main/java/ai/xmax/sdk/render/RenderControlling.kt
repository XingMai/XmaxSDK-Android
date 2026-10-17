//
//  RenderControlling.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.render

import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.RealtimeVideoTrack
import ai.xmax.sdk.foundation.rtc.RemoteStream
import ai.xmax.sdk.media.interaction.InteractionFrame

/**
 * 定义渲染层向 Core 暴露的远端视频能力。
 */
internal interface RenderControlling {
    /**
     * 设置当前订阅的远端流；null 表示解除远端流绑定。
     *
     * @param stream 由房间和用户标识确定的远端媒体流。
     */
    fun setRemoteStream(stream: RemoteStream?)

    /**
     * 为远端轨道注册视频显示和交互轨迹能力。
     *
     * @param track 需要显示或绑定的视频轨道。
     * @param interactionListener 接收触控轨迹帧的回调。
     */
    fun registerRemoteTrack(
        track: RealtimeVideoTrack,
        interactionListener: (InteractionFrame) -> Unit,
    )

    /**
     * 更新远端轨道及交互坐标使用的视频规格。
     *
     * @param videoFormat 视频尺寸、帧率及上行编码参数。
     * @param track 需要显示或绑定的视频轨道。
     */
    fun updateRemoteVideoFormat(
        videoFormat: RealtimeVideoFormat,
        track: RealtimeVideoTrack,
    )

    /**
     * 等待当前远端流首帧可显示，超时或流失效时抛出异常。
     */
    suspend fun waitUntilRemoteFrameReady()

    /**
     * 注销指定远端轨道并清理相关显示资源。
     *
     * @param track 需要显示或绑定的视频轨道。
     */
    suspend fun resetRemoteTrack(track: RealtimeVideoTrack?)
}
