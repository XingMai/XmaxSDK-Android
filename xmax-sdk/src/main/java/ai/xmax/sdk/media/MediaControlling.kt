package ai.xmax.sdk.media

import ai.xmax.sdk.CameraPosition
import ai.xmax.sdk.RealtimeCameraPreviewReadyListener
import ai.xmax.sdk.RealtimeMediaStream
import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.RealtimeVideoTrack
import ai.xmax.sdk.media.interaction.InteractionControlling
import ai.xmax.sdk.RealtimeExternalVideoSource
import android.graphics.Bitmap
import android.net.Uri

/** 定义媒体层向 Core 暴露的本地媒体能力。 */
internal interface MediaControlling : InteractionControlling {
    val currentTrack: RealtimeVideoTrack?

    val currentVideoFormat: RealtimeVideoFormat?

    val hasAudio: Boolean

    val isExternalVideo: Boolean

    fun setCameraPreviewReadyListener(listener: RealtimeCameraPreviewReadyListener?)

    suspend fun createLocalCameraStream(
        videoFormat: RealtimeVideoFormat,
        position: CameraPosition,
        useMicrophone: Boolean = false,
    ): RealtimeMediaStream

    fun startMicrophoneCapture()

    fun stopMicrophoneCapture()

    suspend fun stopLocalCameraStream()

    suspend fun createLocalImageStream(
        imageData: ByteArray,
        videoFormat: RealtimeVideoFormat?,
    ): RealtimeMediaStream

    suspend fun createLocalImageStream(
        bitmap: Bitmap,
        videoFormat: RealtimeVideoFormat?,
    ): RealtimeMediaStream

    suspend fun createLocalImageStream(
        uri: Uri,
        videoFormat: RealtimeVideoFormat?,
    ): RealtimeMediaStream

    suspend fun stopLocalImageStream()

    suspend fun createLocalVideoStream(
        uri: Uri,
        videoFormat: RealtimeVideoFormat?,
    ): RealtimeMediaStream

    suspend fun createExternalVideoStream(
        source: RealtimeExternalVideoSource,
        videoFormat: RealtimeVideoFormat?,
    ): RealtimeMediaStream

    suspend fun stopExternalVideoStream()

    /** 只替换外部输入，保留引擎；准备回调执行前已解绑旧供帧入口。 */
    suspend fun replaceExternalVideoStream(
        videoFormat: RealtimeVideoFormat?,
        targetSource: suspend () -> RealtimeExternalVideoSource,
    ): RealtimeMediaStream

    suspend fun stopLocalVideoStream()

    suspend fun setLocalAudioPreviewMuted(muted: Boolean)

    suspend fun setLocalAudioVolume(volume: Float)

    suspend fun switchCamera(): RealtimeMediaStream

    suspend fun stopLocalStream()

    fun owns(stream: RealtimeMediaStream): Boolean
}
