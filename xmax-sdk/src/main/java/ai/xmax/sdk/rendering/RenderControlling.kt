package ai.xmax.sdk.rendering

import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.RealtimeVideoTrack
import ai.xmax.sdk.foundation.rtc.RemoteStream
import ai.xmax.sdk.media.interaction.InteractionFrame

/** 定义渲染层向 Core 暴露的远端视频能力。 */
internal interface RenderControlling {
    fun setRemoteStream(stream: RemoteStream?)

    fun registerRemoteTrack(
        track: RealtimeVideoTrack,
        interactionListener: (InteractionFrame) -> Unit,
    )

    fun updateRemoteVideoFormat(
        videoFormat: RealtimeVideoFormat,
        track: RealtimeVideoTrack,
    )

    suspend fun waitUntilRemoteFrameReady()

    suspend fun resetRemoteTrack(track: RealtimeVideoTrack?)
}
