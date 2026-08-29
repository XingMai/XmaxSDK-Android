package ai.xmax.sdk.foundation.rtc

import ai.xmax.sdk.AudioFrame
import ai.xmax.sdk.CameraPosition
import ai.xmax.sdk.VideoContentMode
import ai.xmax.sdk.VideoFrame
import android.view.View

/** 隔离第三方 RTC SDK 的引擎能力。 */
internal interface RtcPlatformEngine {
    fun configureVideoEncoding(configuration: VideoEncodingConfiguration): Int

    fun pushExternalVideoFrame(frame: VideoFrame, seiData: ByteArray?): Int

    fun pushExternalAudioFrame(frame: AudioFrame): Int

    fun useExternalVideoSource(): Int

    fun startVideoCapture(width: Int, height: Int, frameRate: Int): Int

    fun stopVideoCapture(): Int

    fun switchCamera(position: CameraPosition): Int

    fun bindLocalVideo(view: View, contentMode: VideoContentMode): Int

    fun unbindLocalVideo(): Int

    fun bindRemoteVideo(userId: String, view: View, contentMode: VideoContentMode): Int

    fun unbindRemoteVideo(userId: String): Int

    fun setCameraPreviewReadyListener(listener: (() -> Unit)?)

    fun setRemoteVideoFrameReadyListener(
        listener: ((RemoteStream, Int, Int) -> Unit)?,
    )

    fun setRemoteAudioVolume(streamId: String, volume: Int): Int

    fun setEventListener(listener: RtcEventListener?)

    fun setQualityListener(listener: RtcQualityListener?)

    fun createRoom(roomId: String): RtcPlatformRoom?
}

/** 隔离第三方 RTC SDK 的房间能力。 */
internal interface RtcPlatformRoom {
    fun setEventListener(
        listener: (
            roomId: String,
            joined: Boolean,
            reason: String?,
        ) -> Unit,
    ): Int

    fun join(configuration: RoomJoinConfiguration): Int

    fun leave(): Int

    fun publishLocalVideo(publish: Boolean): Int

    fun publishLocalAudio(publish: Boolean): Int

    fun subscribeRemoteVideo(userId: String, subscribe: Boolean): Int

    fun subscribeRemoteAudio(userId: String, subscribe: Boolean): Int

    fun resolveRemoteStreamId(userId: String): String

    fun sendRoomMessage(message: String): Long

    fun destroy()
}
