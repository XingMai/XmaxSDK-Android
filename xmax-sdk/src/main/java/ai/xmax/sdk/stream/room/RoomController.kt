//
//  RoomController.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.stream.room

import ai.xmax.sdk.RealtimeTiming
import kotlinx.coroutines.currentCoroutineContext
import ai.xmax.sdk.cleanupAfterFailure
import kotlinx.coroutines.CancellationException
import ai.xmax.sdk.RealtimeContext
import ai.xmax.sdk.RealtimePoint
import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import ai.xmax.sdk.XmaxLogger
import ai.xmax.sdk.foundation.rtc.RoomJoinConfiguration
import ai.xmax.sdk.foundation.rtc.RtcManaging
import ai.xmax.sdk.service.realtime.RealtimeSessionConnection
import ai.xmax.sdk.stream.encoding.EncodingBitratePolicy
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * 管理 RTC 房间生命周期、心跳和实时生成信令。
 *
 * @param rtcManager 提供 RTC 采集、发布、订阅和渲染能力的管理器。
 * @param heartbeat 维持 RTC 房间活跃状态的心跳组件。
 */
internal class RoomController(
    private val rtcManager: RtcManaging,
    private val heartbeat: RoomHeartbeat = RoomHeartbeat(rtcManager),
) : RoomControlling {
    /**
     * 房间状态及离房操作的并发保护。
     */
    private val stateLock = Any()
    private val leaveMutex = Mutex()

    /**
     * 当前房间生命周期状态。
     */
    private var state: State = State.Idle

    /**
     * 加入 RTC 房间并启动房间心跳；调用完成时房间已可发送信令。
     *
     * @param connection 服务端返回的 RTC 房间、用户、鉴权及结果流信息。
     * @param ensureActive 检查当前操作是否仍可继续的函数，取消时应抛出异常。
     */
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
            }
        }

        try {
            heartbeat.stop()
            val timing = currentCoroutineContext()[RealtimeTiming.Attempt]
            timing?.mark(RealtimeTiming.Stage.ROOM_START)
            rtcManager.joinRoom(
                RoomJoinConfiguration(
                    roomId = connection.roomId,
                    userId = connection.userId,
                    token = connection.token,
                    appId = connection.appId,
                ),
            )
            timing?.mark(RealtimeTiming.Stage.ROOM_END)
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
                throw CancellationException("RTC room join was cancelled")
            }
        } catch (error: Throwable) {
            val shouldRollback = synchronized(stateLock) {
                state == State.Joining(operationId)
            }
            if (shouldRollback) cleanupAfterFailure(error, { leave() })
            throw XmaxError.from(error)
        }
    }

    /**
     * 停止房间心跳并离开当前 RTC 房间。
     */
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
                                true
                    }
                }
            }
            if (!hasResources) return

            try {
                heartbeat.stop()
                rtcManager.leaveRoom()
            } finally {
                synchronized(stateLock) {
                    if (state == State.Leaving) state = State.Idle
                }
            }
        }
    }

    /**
     * 发送包含输入规格、生成条件和回传尺寸的启动信令。
     *
     * @param taskId 用于关联本次生成请求和结果流的任务标识。
     * @param videoFormat 视频尺寸、帧率及上行编码参数。
     * @param context 生成提示词及参考素材。
     * @param faceRestoration 是否启用人脸修复，对应生成参数 `face_replace`。
     * @param targetVideoFormat 下行输出的视频规格。
     * @param paddingContain 是否让服务端等比适配输入并在输出时移除补边。
     */
    override fun startGeneration(
        taskId: String,
        videoFormat: RealtimeVideoFormat,
        context: RealtimeContext,
        faceRestoration: Boolean,
        targetVideoFormat: RealtimeVideoFormat?,
        paddingContain: Boolean,
    ) {
        val (minimum, maximum) = downlinkBitrates(targetVideoFormat ?: videoFormat)
        send(
            RoomEvent.start(
                userId = requireUserId(),
                taskId = taskId,
                videoFormat = videoFormat,
                context = context,
                minimumBitrate = minimum,
                maximumBitrate = maximum,
                faceRestoration = faceRestoration,
                targetVideoFormat = targetVideoFormat,
                paddingContain = paddingContain,
            ),
        )
    }

    /**
     * 更新回传尺寸与码率；发送前检查操作有效性。
     *
     * @param taskId 用于关联本次生成请求和结果流的任务标识。
     * @param targetVideoFormat 下行输出的视频规格。
     * @param ensureActive 检查当前操作是否仍可继续的函数，取消时应抛出异常。
     */
    override fun changeTargetSize(taskId: String, targetVideoFormat: RealtimeVideoFormat, ensureActive: () -> Unit) {
        ensureActive()
        if (taskId.isBlank()) {
            throw XmaxError(XmaxErrorCode.INVALID_CONFIGURATION, "Realtime generation task ID cannot be empty")
        }

        val (minimum, maximum) = downlinkBitrates(targetVideoFormat)
        val message = RoomEvent.changeTargetSize(requireUserId(), taskId, targetVideoFormat, minimum, maximum)
        ensureActive()
        send(message)
    }

    /**
     * 下行最低码率固定为 100 Kbps；最高码率按回传尺寸和 24 fps 计算，限制在 100～10000 Kbps。
     *
     * @param videoFormat 视频尺寸、帧率及上行编码参数。
     * @return 最低与最高下行编码码率，单位为 kbps。
     */
    private fun downlinkBitrates(videoFormat: RealtimeVideoFormat): Pair<Int, Int> {
        val (_, maximum) = EncodingBitratePolicy.resolve(videoFormat.width, videoFormat.height, fps = 24)
        return 100 to maximum.coerceIn(100, 10_000)
    }

    /**
     * 发送当前任务的新生成条件。
     *
     * @param taskId 用于关联本次生成请求和结果流的任务标识。
     * @param videoFormat 视频尺寸、帧率及上行编码参数。
     * @param context 生成提示词及参考素材。
     * @param faceRestoration 是否启用人脸修复，对应生成参数 `face_replace`。
     * @param paddingContain 是否让服务端等比适配输入并在输出时移除补边。
     */
    override fun changeGenerationCondition(
        taskId: String,
        videoFormat: RealtimeVideoFormat,
        context: RealtimeContext,
        faceRestoration: Boolean,
        paddingContain: Boolean,
    ) {
        send(
            RoomEvent.changeCondition(
                userId = requireUserId(),
                taskId = taskId,
                videoFormat = videoFormat,
                context = context,
                faceRestoration = faceRestoration,
                paddingContain = paddingContain,
            ),
        )
    }

    /**
     * 发送停止指定生成任务的信令。
     *
     * @param taskId 用于关联本次生成请求和结果流的任务标识。
     */
    override fun stopGeneration(taskId: String) {
        val userId = synchronized(stateLock) {
            if (taskId.isEmpty()) return
            (state as? State.Joined)?.userId ?: return
        }
        send(RoomEvent.stop(userId, taskId))
    }

    /**
     * 向服务端发送当前任务的媒体归一化坐标轨迹。
     *
     * @param taskId 用于关联本次生成请求和结果流的任务标识。
     * @param points 媒体归一化坐标中的轨迹点集合，范围为 [0, 1]。
     */
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

    /**
     * 向已加入的 RTC 房间发送业务信令。
     *
     * @param message 待发送或记录的消息文本。
     */
    private fun send(message: String) {
        rtcManager.sendRoomMessage(message)
        XmaxLogger.room.debug(
            message = { formatSignalLog(message) },
        )
    }

    /**
     * 将信令格式化为便于阅读的日志文本。
     *
     * @param message 待发送或记录的消息文本。
     * @return 用于日志输出的格式化信令文本。
     */
    internal fun formatSignalLog(message: String): String = try {
        val event = JSONObject(message)
        val eventType = event.optString("event", "unknown")
        val formattedMessage = event.toString(2).replace("\n", "\n   ")
        "发送房间信令 (Outbound Room Signaling)\n" +
            "├─ ${XmaxLogger.localized("类型：", "Type: ")}$eventType\n" +
            "└─ ${XmaxLogger.localized("内容：", "Content: ")}\n" +
            "   $formattedMessage"
    } catch (_: Throwable) {
        "发送房间信令 (Outbound Room Signaling)\n" +
            "└─ ${XmaxLogger.localized("内容：", "Content: ")}$message"
    }

    /**
     * 取得当前已加入房间的用户标识，否则抛出状态错误。
     *
     * @return 当前已加入房间的用户标识。
     */
    private fun requireUserId(): String = synchronized(stateLock) {
        (state as? State.Joined)?.userId ?: throw XmaxError(
            code = XmaxErrorCode.RTC_ERROR,
            message = "RTC room is not joined",
        )
    }

    /**
     * 保存控制器当前的生命周期或发布订阅状态。
     */
    private sealed interface State {
        /**
         * 表示当前未加入 RTC 房间。
         */
        data object Idle : State

        /**
         * 表示当前正在等待指定进房操作完成。
         *
         * @param id 用于区分当前对象或操作的唯一标识。
         */
        data class Joining(val id: UUID) : State

        /**
         * 表示当前已加入房间并可以发送业务信令。
         *
         * @param userId 房间中的业务用户标识。
         */
        data class Joined(val userId: String) : State

        /**
         * 表示正在释放 RTC 房间资源。
         */
        data object Leaving : State
    }
}
