package ai.xmax.sdk.foundation.rtc

import android.content.Context
import com.ss.bytertc.engine.RTCEngine
import com.ss.bytertc.engine.RTCRoom
import com.ss.bytertc.engine.RTCRoomConfig
import com.ss.bytertc.engine.UserInfo
import com.ss.bytertc.engine.data.EngineConfig
import com.ss.bytertc.engine.data.StreamInfo
import com.ss.bytertc.engine.handler.IRTCEngineEventHandler
import com.ss.bytertc.engine.handler.IRTCRoomEventHandler
import com.ss.bytertc.engine.type.ChannelProfile
import com.ss.bytertc.engine.type.NetworkQualityStats
import com.ss.bytertc.engine.type.PerformanceAlarmMode
import com.ss.bytertc.engine.type.PerformanceAlarmReason
import com.ss.bytertc.engine.type.RoomState
import com.ss.bytertc.engine.type.RoomStateChangeReason
import com.ss.bytertc.engine.type.SourceWantedData
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

/** 创建火山 RTC Engine，并将供应商类型限制在 Foundation 内部。 */
internal fun createVolcRtcEngine(
    context: Context,
    appId: String,
): RtcPlatformEngine? {
    val normalizedAppId = appId.trim()
    if (normalizedAppId.isEmpty()) return null

    val configuration = EngineConfig().apply {
        this.context = context
        appID = normalizedAppId
        parameters = JSONObject()
        isGameScene = false
    }
    val eventListener = AtomicReference<WeakReference<RtcEventListener>?>(null)
    val qualityListener = AtomicReference<WeakReference<RtcQualityListener>?>(null)
    val activeRoomId = AtomicReference<String?>(null)
    val remoteStreamIds = ConcurrentHashMap<RemoteStream, String>()
    val engine = RTCEngine.createRTCEngine(configuration, object : IRTCEngineEventHandler() {
        override fun onSEIMessageReceived(
            streamId: String,
            streamInfo: StreamInfo,
            message: ByteBuffer,
        ) {
            val roomId = streamInfo.roomId?.trim().orEmpty()
            val userId = streamInfo.userId?.trim().orEmpty()
            if (roomId.isEmpty() || userId.isEmpty() || activeRoomId.get() != roomId) return
            val decodedMessage = decodeUtf8(message) ?: return
            val stream = RemoteStream(roomId = roomId, userId = userId)
            remoteStreamIds[stream] = streamInfo.streamId
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?: streamId.trim().takeIf(String::isNotEmpty)
                ?: userId
            eventListener.get()?.get()?.onSeiMessageReceived(stream, decodedMessage)
        }

        override fun onPerformanceAlarms(
            roomId: String,
            streamInfo: StreamInfo,
            mode: PerformanceAlarmMode,
            reason: PerformanceAlarmReason,
            sourceWantedData: SourceWantedData,
        ) {
            if (activeRoomId.get() != streamInfo.roomId) return
            qualityListener.get()?.get()?.onPerformanceAlarm(
                limited = RtcQualityConverter.resolvePerformanceLimited(reason),
                suggestedWidth = sourceWantedData.width,
                suggestedHeight = sourceWantedData.height,
                suggestedFrameRate = sourceWantedData.frameRate,
            )
        }
    }) ?: return null
    return object : RtcPlatformEngine {
        override fun configureVideoEncoding(
            configuration: VideoEncodingConfiguration,
        ): Int = engine.setVideoEncoderConfig(
            RtcVideoConverter.makeEncoderConfiguration(configuration),
        )

        override fun setRemoteAudioVolume(streamId: String, volume: Int): Int =
            engine.setRemoteAudioPlaybackVolume(streamId, volume)

        override fun setEventListener(listener: RtcEventListener?) {
            eventListener.set(listener?.let(::WeakReference))
        }

        override fun setQualityListener(listener: RtcQualityListener?) {
            qualityListener.set(listener?.let(::WeakReference))
        }

        override fun createRoom(roomId: String): RtcPlatformRoom? =
            engine.createRTCRoom(roomId)?.let { room ->
                createVolcRtcRoom(
                    room = room,
                    roomId = roomId,
                    activeRoomId = activeRoomId,
                    remoteStreamIds = remoteStreamIds,
                    eventListener = { eventListener.get()?.get() },
                    qualityListener = { qualityListener.get()?.get() },
                )
            }
    }
}

/** 销毁进程级火山 RTC Engine。 */
internal fun destroyVolcRtcEngine() {
    RTCEngine.destroyRTCEngine()
}

private fun createVolcRtcRoom(
    room: RTCRoom,
    roomId: String,
    activeRoomId: AtomicReference<String?>,
    remoteStreamIds: ConcurrentHashMap<RemoteStream, String>,
    eventListener: () -> RtcEventListener?,
    qualityListener: () -> RtcQualityListener?,
): RtcPlatformRoom = object : RtcPlatformRoom {
    private var eventBridge: IRTCRoomEventHandler? = null

    override fun setEventListener(
        listener: (String, Boolean, String?) -> Unit,
    ): Int {
        val bridge = object : IRTCRoomEventHandler() {
            override fun onRoomStateChangedWithReason(
                roomId: String,
                userId: String,
                state: RoomState,
                reason: RoomStateChangeReason,
            ) {
                val joined = state == RoomState.JOIN_SUCCESS
                if (joined) {
                    activeRoomId.set(roomId)
                } else {
                    activeRoomId.compareAndSet(roomId, null)
                }
                listener(roomId, joined, reason.name)
            }

            @Suppress("DEPRECATION")
            override fun onRoomStateChanged(
                roomId: String,
                userId: String,
                state: Int,
                extraInfo: String,
            ) {
                val joined = state == 0
                if (joined) {
                    activeRoomId.set(roomId)
                } else {
                    activeRoomId.compareAndSet(roomId, null)
                }
                listener(roomId, joined, extraInfo.takeIf(String::isNotBlank))
            }

            override fun onNetworkQuality(
                localQuality: NetworkQualityStats,
                remoteQualities: Array<out NetworkQualityStats>,
            ) {
                if (activeRoomId.get() != roomId) return
                qualityListener()?.onNetworkQuality(
                    uplink = RtcQualityConverter.convertLevel(localQuality.txQuality),
                    downlink = RtcQualityConverter.resolveDownlinkLevel(remoteQualities),
                )
            }

            override fun onUserPublishStreamVideo(
                streamId: String,
                streamInfo: StreamInfo,
                isPublish: Boolean,
            ) {
                if (activeRoomId.get() != roomId) return
                val userId = streamInfo.userId?.trim().orEmpty()
                if (userId.isEmpty()) return
                val stream = RemoteStream(roomId = roomId, userId = userId)
                if (isPublish) {
                    remoteStreamIds[stream] = streamInfo.streamId
                        ?.trim()
                        ?.takeIf(String::isNotEmpty)
                        ?: streamId.trim().takeIf(String::isNotEmpty)
                        ?: userId
                } else {
                    remoteStreamIds.remove(stream)
                }
                eventListener()?.onRemoteVideoPublished(userId, isPublish)
            }
        }
        eventBridge = bridge
        return room.setRTCRoomEventHandler(bridge)
    }

    override fun join(configuration: RoomJoinConfiguration): Int {
        val roomConfiguration = RTCRoomConfig(
            ChannelProfile.CHANNEL_PROFILE_COMMUNICATION,
            configuration.userId,
            false,
            false,
            false,
            true,
        )
        return room.joinRoom(
            configuration.token,
            UserInfo.create(configuration.userId, ""),
            true,
            roomConfiguration,
        )
    }

    override fun leave(): Int {
        activeRoomId.compareAndSet(roomId, null)
        remoteStreamIds.keys.removeAll { it.roomId == roomId }
        return room.leaveRoom()
    }

    override fun publishLocalVideo(publish: Boolean): Int = room.publishStreamVideo(publish)

    override fun publishLocalAudio(publish: Boolean): Int = room.publishStreamAudio(publish)

    override fun subscribeRemoteVideo(userId: String, subscribe: Boolean): Int =
        room.subscribeStreamVideo(resolveRemoteStreamId(userId), subscribe)

    override fun subscribeRemoteAudio(userId: String, subscribe: Boolean): Int =
        room.subscribeStreamAudio(resolveRemoteStreamId(userId), subscribe)

    override fun resolveRemoteStreamId(userId: String): String =
        remoteStreamIds[RemoteStream(roomId = roomId, userId = userId)] ?: userId

    override fun sendRoomMessage(message: String): Long = room.sendRoomMessage(message)

    override fun destroy() {
        activeRoomId.compareAndSet(roomId, null)
        remoteStreamIds.keys.removeAll { it.roomId == roomId }
        eventBridge = null
        room.destroy()
    }
}

private fun decodeUtf8(buffer: ByteBuffer): String? = runCatching {
    StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(buffer.duplicate())
        .toString()
}.getOrNull()
