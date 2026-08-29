package ai.xmax.sdk.media

import ai.xmax.sdk.CameraPosition
import ai.xmax.sdk.RealtimeCameraPreviewReadyListener
import ai.xmax.sdk.RealtimeMediaStream
import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.RealtimeVideoTrack

/** 定义媒体层向 Core 暴露的相机能力。 */
internal interface MediaControlling {
    val currentTrack: RealtimeVideoTrack?

    fun setCameraPreviewReadyListener(listener: RealtimeCameraPreviewReadyListener?)

    suspend fun createLocalCameraStream(
        videoFormat: RealtimeVideoFormat,
        position: CameraPosition,
    ): RealtimeMediaStream

    suspend fun stopLocalCameraStream()

    suspend fun switchCamera(): RealtimeMediaStream

    suspend fun stopLocalStream()
}
