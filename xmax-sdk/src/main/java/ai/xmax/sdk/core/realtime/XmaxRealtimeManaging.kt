package ai.xmax.sdk

import android.graphics.Bitmap
import android.net.Uri

/** 定义 SDK 对接入方提供的本地媒体与实时生成控制能力。 */
public interface XmaxRealtimeManaging {
    public val options: RealtimeConfiguration

    public val currentState: RealtimeState

    public suspend fun setStateListener(listener: RealtimeStateListener?)

    public suspend fun setErrorListener(listener: RealtimeErrorListener?)

    public suspend fun setCameraPreviewReadyListener(
        listener: RealtimeCameraPreviewReadyListener?,
    )

    public suspend fun setNetworkQualityListener(listener: RealtimeNetworkQualityListener?)

    public suspend fun setPerformanceAlarmListener(listener: RealtimePerformanceAlarmListener?)

    public suspend fun setRemoteAudioVolume(volume: Float)

    public suspend fun createLocalCameraStream(
        videoFormat: RealtimeVideoFormat,
        position: CameraPosition,
    ): RealtimeMediaStream

    public suspend fun stopLocalCameraStream()

    public suspend fun createLocalImageStream(
        imageData: ByteArray,
        videoFormat: RealtimeVideoFormat? = null,
    ): RealtimeMediaStream

    public suspend fun createLocalImageStream(
        bitmap: Bitmap,
        videoFormat: RealtimeVideoFormat? = null,
    ): RealtimeMediaStream

    public suspend fun createLocalImageStream(
        uri: Uri,
        videoFormat: RealtimeVideoFormat? = null,
    ): RealtimeMediaStream

    public suspend fun stopLocalImageStream()

    public suspend fun switchCamera(): RealtimeMediaStream

    public suspend fun connect(localStream: RealtimeMediaStream): RealtimeMediaStream

    public suspend fun disconnect()

    public suspend fun startGeneration(context: RealtimeContext?)

    public suspend fun startGeneration(
        localStream: RealtimeMediaStream,
        context: RealtimeContext?,
    ): RealtimeMediaStream

    public suspend fun stopGeneration()

    public suspend fun close()
}
