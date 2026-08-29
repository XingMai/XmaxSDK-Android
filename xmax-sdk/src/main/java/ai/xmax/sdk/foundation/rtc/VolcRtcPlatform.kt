package ai.xmax.sdk.foundation.rtc

import android.content.Context
import com.ss.bytertc.engine.RTCEngine
import com.ss.bytertc.engine.RTCRoom
import com.ss.bytertc.engine.RTCRoomConfig
import com.ss.bytertc.engine.UserInfo
import com.ss.bytertc.engine.data.EngineConfig
import com.ss.bytertc.engine.handler.IRTCEngineEventHandler
import com.ss.bytertc.engine.handler.IRTCRoomEventHandler
import com.ss.bytertc.engine.type.ChannelProfile
import com.ss.bytertc.engine.type.RoomState
import com.ss.bytertc.engine.type.RoomStateChangeReason
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
    val engine = RTCEngine.createRTCEngine(
        configuration,
        object : IRTCEngineEventHandler() {},
    ) ?: return null
    return object : RtcPlatformEngine {
        override fun configureVideoEncoding(
            configuration: VideoEncodingConfiguration,
        ): Int = engine.setVideoEncoderConfig(
            RtcVideoConverter.makeEncoderConfiguration(configuration),
        )

        override fun createRoom(roomId: String): RtcPlatformRoom? =
            engine.createRTCRoom(roomId)?.let(::createVolcRtcRoom)
    }
}

/** 销毁进程级火山 RTC Engine。 */
internal fun destroyVolcRtcEngine() {
    RTCEngine.destroyRTCEngine()
}

private fun createVolcRtcRoom(room: RTCRoom): RtcPlatformRoom = object : RtcPlatformRoom {
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
                listener(roomId, state == RoomState.JOIN_SUCCESS, reason.name)
            }

            @Suppress("DEPRECATION")
            override fun onRoomStateChanged(
                roomId: String,
                userId: String,
                state: Int,
                extraInfo: String,
            ) {
                listener(roomId, state == 0, extraInfo.takeIf(String::isNotBlank))
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

    override fun leave(): Int = room.leaveRoom()

    override fun sendRoomMessage(message: String): Long = room.sendRoomMessage(message)

    override fun destroy() {
        eventBridge = null
        room.destroy()
    }
}
