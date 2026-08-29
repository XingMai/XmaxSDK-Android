package ai.xmax.sdk.stream.room

import ai.xmax.sdk.foundation.rtc.RoomJoinConfiguration
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
    data class JoinRoom(val configuration: RoomJoinConfiguration) : RtcManagingCall
    data object LeaveRoom : RtcManagingCall
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
    private var qualityListener: RtcQualityListener? = null

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

    override suspend fun joinRoom(configuration: RoomJoinConfiguration) {
        record(RtcManagingCall.JoinRoom(configuration))
        joinRoomError?.let { throw it }
        joinRoomHandler?.invoke(configuration)
    }

    override suspend fun leaveRoom() {
        record(RtcManagingCall.LeaveRoom)
    }

    override fun sendRoomMessage(message: String) {
        record(RtcManagingCall.SendRoomMessage(message))
        sendRoomMessageError?.let { throw it }
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

    private fun record(call: RtcManagingCall) {
        synchronized(lock) {
            storedCalls += call
        }
    }
}
