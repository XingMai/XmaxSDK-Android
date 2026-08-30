package ai.xmax.sdk.stream.room

import ai.xmax.sdk.RealtimeContext
import ai.xmax.sdk.RealtimePoint
import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import ai.xmax.sdk.XmaxLogger
import ai.xmax.sdk.foundation.rtc.RoomJoinConfiguration
import ai.xmax.sdk.foundation.rtc.RtcManaging
import ai.xmax.sdk.service.realtime.RealtimeSessionConnection
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** 管理 RTC 房间生命周期、心跳和实时生成信令。 */
internal class RoomController(
    private val rtcManager: RtcManaging,
    private val heartbeat: RoomHeartbeat = RoomHeartbeat(rtcManager),
) : RoomControlling {
    private val stateLock = Any()
    private val leaveMutex = Mutex()
    private var state: State = State.Idle

    override suspend fun join(
        connection: RealtimeSessionConnection,
        ensureActive: () -> Unit,
    ) {
        try {
            ensureActive()
        } catch (error: Throwable) {
            throw XmaxError.from(error)
        }

        val operationId = synchronized(stateLock) {
            if (state != State.Idle) {
                throw XmaxError(
                    code = XmaxErrorCode.INVALID_CONFIGURATION,
                    message = "Leave the current RTC room before joining another one",
                )
            }
            UUID.randomUUID().also {
                state = State.Joining(it)
                heartbeat.stop()
            }
        }

        try {
            rtcManager.joinRoom(
                RoomJoinConfiguration(
                    roomId = connection.roomId,
                    userId = connection.userId,
                    token = connection.token,
                ),
            )
            ensureActive()

            val activated = synchronized(stateLock) {
                if (state != State.Joining(operationId)) {
                    false
                } else {
                    state = State.Joined(connection.userId)
                    heartbeat.start(connection.userId)
                    true
                }
            }
            if (!activated) {
                throw XmaxError(
                    code = XmaxErrorCode.CANCELLED,
                    message = "RTC room join was cancelled",
                )
            }
        } catch (error: Throwable) {
            val shouldRollback = synchronized(stateLock) {
                state == State.Joining(operationId)
            }
            if (shouldRollback) leave()
            throw XmaxError.from(error)
        }
    }

    override suspend fun leave() {
        leaveMutex.withLock {
            val hasResources = synchronized(stateLock) {
                when (state) {
                    State.Idle,
                    State.Leaving,
                    -> false

                    is State.Joined,
                    is State.Joining,
                    -> {
                        state = State.Leaving
                        heartbeat.stop()
                        true
                    }
                }
            }
            if (!hasResources) return

            try {
                rtcManager.leaveRoom()
            } finally {
                synchronized(stateLock) {
                    if (state == State.Leaving) state = State.Idle
                }
            }
        }
    }

    override fun startGeneration(
        taskId: String,
        videoFormat: RealtimeVideoFormat,
        context: RealtimeContext,
    ) {
        send(
            RoomEvent.start(
                userId = requireUserId(),
                taskId = taskId,
                videoFormat = videoFormat,
                context = context,
            ),
        )
    }

    override fun changeGenerationCondition(
        taskId: String,
        videoFormat: RealtimeVideoFormat,
        context: RealtimeContext,
    ) {
        send(
            RoomEvent.changeCondition(
                userId = requireUserId(),
                taskId = taskId,
                videoFormat = videoFormat,
                context = context,
            ),
        )
    }

    override fun stopGeneration(taskId: String) {
        val userId = synchronized(stateLock) {
            if (taskId.isEmpty()) return
            (state as? State.Joined)?.userId ?: return
        }
        send(RoomEvent.stop(userId, taskId))
    }

    override fun sendTracks(
        taskId: String,
        points: List<RealtimePoint>,
    ) {
        if (taskId.isEmpty() || points.isEmpty()) return
        send(
            RoomEvent.tracks(
                userId = requireUserId(),
                taskId = taskId,
                points = points,
            ),
        )
    }

    private fun send(message: String) {
        rtcManager.sendRoomMessage(message)
        XmaxLogger.debug(
            { formatSignalLog(message) },
            category = "Room",
        )
    }

    internal fun formatSignalLog(message: String): String = try {
        val event = JSONObject(message)
        val eventType = event.optString("event", "unknown")
        val formattedMessage = event.toString(2).replace("\n", "\n   ")
        "发送房间信令 (Outbound Room Signaling)\n" +
            "├─ 类型：$eventType\n" +
            "└─ 内容：\n" +
            "   $formattedMessage"
    } catch (_: Throwable) {
        "发送房间信令 (Outbound Room Signaling)\n└─ 内容：$message"
    }

    private fun requireUserId(): String = synchronized(stateLock) {
        (state as? State.Joined)?.userId ?: throw XmaxError(
            code = XmaxErrorCode.RTC_ERROR,
            message = "RTC room is not joined",
        )
    }

    private sealed interface State {
        data object Idle : State
        data class Joining(val id: UUID) : State
        data class Joined(val userId: String) : State
        data object Leaving : State
    }
}
