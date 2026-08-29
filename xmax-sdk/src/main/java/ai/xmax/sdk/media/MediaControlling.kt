package ai.xmax.sdk.media

import ai.xmax.sdk.CameraPosition
import ai.xmax.sdk.RealtimeCameraPreviewReadyListener
import ai.xmax.sdk.RealtimeMediaStream
import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.RealtimeVideoTrack
import ai.xmax.sdk.media.interaction.InteractionControlling

/** 定义媒体层向 Core 暴露的相机能力。 */
internal interface MediaControlling : InteractionControlling {
    val currentTrack: RealtimeVideoTrack?

    val currentVideoFormat: RealtimeVideoFormat?

    val hasAudio: Boolean

    fun setCameraPreviewReadyListener(listener: RealtimeCameraPreviewReadyListener?)

    suspend fun createLocalCameraStream(
        videoFormat: RealtimeVideoFormat,
        position: CameraPosition,
    ): RealtimeMediaStream

    suspend fun stopLocalCameraStream()

    suspend fun switchCamera(): RealtimeMediaStream

    suspend fun stopLocalStream()

    fun owns(stream: RealtimeMediaStream): Boolean
}
