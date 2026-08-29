package ai.xmax.sdk.stream.room

import ai.xmax.sdk.AudioFrame
import ai.xmax.sdk.CameraPosition
import ai.xmax.sdk.RealtimeCameraPreviewReadyListener
import ai.xmax.sdk.VideoContentMode
import ai.xmax.sdk.VideoFrame
import android.view.View
import ai.xmax.sdk.foundation.rtc.RoomJoinConfiguration
import ai.xmax.sdk.foundation.rtc.RemoteStream
import ai.xmax.sdk.foundation.rtc.RtcEventListener
import ai.xmax.sdk.foundation.rtc.RtcManaging
import ai.xmax.sdk.foundation.rtc.RtcQualityLevel
import ai.xmax.sdk.foundation.rtc.RtcQualityListener
import ai.xmax.sdk.foundation.rtc.VideoEncodingConfiguration

internal sealed interface RtcManagingCall {
    data object Initialize : RtcManagingCall
    data object Destroy : RtcManagingCall
    data class ConfigureVideoEncoding(
        val configuration: VideoEncodingConfiguration,
    ) : RtcManagingCall
    data class PushExternalVideoFrame(
        val frame: VideoFrame,
        val seiData: List<Byte>?,
    ) : RtcManagingCall
    data class PushExternalAudioFrame(val frame: AudioFrame) : RtcManagingCall
    data object UseExternalVideoSource : RtcManagingCall
    data object StartExternalAudioSource : RtcManagingCall
    data object StopExternalAudioSource : RtcManagingCall
    data class JoinRoom(val configuration: RoomJoinConfiguration) : RtcManagingCall
    data object LeaveRoom : RtcManagingCall
    data object PublishLocalVideo : RtcManagingCall
    data object UnpublishLocalVideo : RtcManagingCall
    data object PublishLocalAudio : RtcManagingCall
    data object UnpublishLocalAudio : RtcManagingCall
    data class SubscribeRemoteVideo(
        val userId: String,
        val subscribe: Boolean,
    ) : RtcManagingCall
    data class SubscribeRemoteAudio(
        val userId: String,
        val subscribe: Boolean,
    ) : RtcManagingCall
    data class SetRemoteAudioVolume(
        val volume: Int,
        val userId: String,
    ) : RtcManagingCall
    data class SendRoomMessage(val message: String) : RtcManagingCall
}

internal class RtcManagingStub(
    private val encodingError: Throwable? = null,
    private val joinRoomError: Throwable? = null,
    private val sendRoomMessageError: Throwable? = null,
    private val joinRoomHandler: (suspend (RoomJoinConfiguration) -> Unit)? = null,
) : RtcManaging {
    private val lock = Any()
    private val storedCalls = mutableListOf<RtcManagingCall>()
    private var eventListener: RtcEventListener? = null
    private var qualityListener: RtcQualityListener? = null
    private var remoteVideoFrameReadyListener: ((RemoteStream, Int, Int) -> Unit)? = null

    val calls: List<RtcManagingCall>
        get() = synchronized(lock) { storedCalls.toList() }

    val roomMessages: List<String>
        get() = calls.mapNotNull { call ->
            (call as? RtcManagingCall.SendRoomMessage)?.message
        }

    val encodingConfigurations: List<VideoEncodingConfiguration>
        get() = calls.mapNotNull { call ->
            (call as? RtcManagingCall.ConfigureVideoEncoding)?.configuration
        }

    override suspend fun initialize() {
        record(RtcManagingCall.Initialize)
    }

    override suspend fun destroy() {
        record(RtcManagingCall.Destroy)
    }

    override fun configureVideoEncoding(configuration: VideoEncodingConfiguration) {
        record(RtcManagingCall.ConfigureVideoEncoding(configuration))
        encodingError?.let { throw it }
    }

    override fun pushExternalVideoFrame(
        frame: VideoFrame,
        seiData: ByteArray?,
    ) {
        record(RtcManagingCall.PushExternalVideoFrame(frame, seiData?.toList()))
    }

    override fun pushExternalAudioFrame(frame: AudioFrame) {
        record(RtcManagingCall.PushExternalAudioFrame(frame))
    }

    override fun useExternalVideoSource() {
        record(RtcManagingCall.UseExternalVideoSource)
    }

    override fun startExternalAudioSource() {
        record(RtcManagingCall.StartExternalAudioSource)
    }

    override fun stopExternalAudioSource() {
        record(RtcManagingCall.StopExternalAudioSource)
    }

    override fun startVideoCapture(width: Int, height: Int, frameRate: Int) = Unit

    override fun stopVideoCapture() = Unit

    override fun switchCamera(position: CameraPosition) = Unit

    override fun bindLocalVideo(view: View, contentMode: VideoContentMode) = Unit

    override fun unbindLocalVideo() = Unit

    override fun bindRemoteVideo(
        stream: RemoteStream,
        view: View,
        contentMode: VideoContentMode,
    ) = Unit

    override fun unbindRemoteVideo(stream: RemoteStream) = Unit

    override val renderLibraryName: String = "XmaxSDK"

    override suspend fun joinRoom(configuration: RoomJoinConfiguration) {
        record(RtcManagingCall.JoinRoom(configuration))
        joinRoomError?.let { throw it }
        joinRoomHandler?.invoke(configuration)
    }

    override suspend fun leaveRoom() {
        record(RtcManagingCall.LeaveRoom)
    }

    override fun publishLocalVideo() {
        record(RtcManagingCall.PublishLocalVideo)
    }

    override fun unpublishLocalVideo() {
        record(RtcManagingCall.UnpublishLocalVideo)
    }

    override fun publishLocalAudio() {
        record(RtcManagingCall.PublishLocalAudio)
    }

    override fun unpublishLocalAudio() {
        record(RtcManagingCall.UnpublishLocalAudio)
    }

    override fun subscribeRemoteVideo(
        userId: String,
        subscribe: Boolean,
    ) {
        record(RtcManagingCall.SubscribeRemoteVideo(userId, subscribe))
    }

    override fun subscribeRemoteAudio(
        userId: String,
        subscribe: Boolean,
    ) {
        record(RtcManagingCall.SubscribeRemoteAudio(userId, subscribe))
    }

    override fun setRemoteAudioVolume(
        volume: Int,
        userId: String,
    ) {
        record(RtcManagingCall.SetRemoteAudioVolume(volume, userId))
    }

    override fun sendRoomMessage(message: String) {
        record(RtcManagingCall.SendRoomMessage(message))
        sendRoomMessageError?.let { throw it }
    }

    override fun setEventListener(listener: RtcEventListener?) {
        synchronized(lock) {
            eventListener = listener
        }
    }

    override fun setCameraPreviewReadyListener(listener: RealtimeCameraPreviewReadyListener?) = Unit

    override fun setRemoteVideoFrameReadyListener(
        listener: ((RemoteStream, Int, Int) -> Unit)?,
    ) {
        synchronized(lock) {
            remoteVideoFrameReadyListener = listener
        }
    }

    override fun setQualityListener(listener: RtcQualityListener?) {
        synchronized(lock) {
            qualityListener = listener
        }
    }

    fun emitNetworkQuality(
        uplink: RtcQualityLevel,
        downlink: RtcQualityLevel,
    ) {
        synchronized(lock) { qualityListener }?.onNetworkQuality(uplink, downlink)
    }

    fun emitPerformanceAlarm(
        limited: Boolean,
        suggestedWidth: Int,
        suggestedHeight: Int,
        suggestedFrameRate: Int,
    ) {
        synchronized(lock) { qualityListener }?.onPerformanceAlarm(
            limited = limited,
            suggestedWidth = suggestedWidth,
            suggestedHeight = suggestedHeight,
            suggestedFrameRate = suggestedFrameRate,
        )
    }

    fun emitRemoteVideoPublished(
        userId: String,
        published: Boolean,
    ) {
        synchronized(lock) { eventListener }?.onRemoteVideoPublished(userId, published)
    }

    fun emitSeiMessage(
        stream: RemoteStream,
        message: String,
    ) {
        synchronized(lock) { eventListener }?.onSeiMessageReceived(stream, message)
    }

    fun emitRemoteVideoFrameReady(
        stream: RemoteStream,
        width: Int,
        height: Int,
    ) {
        synchronized(lock) { remoteVideoFrameReadyListener }?.invoke(stream, width, height)
    }

    private fun record(call: RtcManagingCall) {
        synchronized(lock) {
            storedCalls += call
        }
    }
}
