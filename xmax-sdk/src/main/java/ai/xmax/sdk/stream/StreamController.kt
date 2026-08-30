package ai.xmax.sdk.stream

import ai.xmax.sdk.AudioFrame
import ai.xmax.sdk.RealtimeContext
import ai.xmax.sdk.RealtimeNetworkQualityListener
import ai.xmax.sdk.RealtimePerformanceAlarmListener
import ai.xmax.sdk.RealtimePoint
import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.VideoFrame
import ai.xmax.sdk.ErrorMessageFormatter
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import ai.xmax.sdk.XmaxLogger
import ai.xmax.sdk.foundation.rtc.RemoteStream
import ai.xmax.sdk.foundation.rtc.RtcEventListener
import ai.xmax.sdk.foundation.rtc.RtcManaging
import ai.xmax.sdk.service.realtime.RealtimeSessionConnection
import ai.xmax.sdk.stream.encoding.EncodingController
import ai.xmax.sdk.stream.encoding.EncodingControlling
import ai.xmax.sdk.stream.quality.QualityController
import ai.xmax.sdk.stream.quality.QualityControlling
import ai.xmax.sdk.stream.room.RoomController
import ai.xmax.sdk.stream.room.RoomControlling
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 统一协调 RTC 房间、媒体流、编码和质量事件。 */
internal class StreamController(
    private val rtcManager: RtcManaging,
    private val roomController: RoomControlling = RoomController(rtcManager),
    private val encodingController: EncodingControlling = EncodingController(rtcManager),
    private val qualityController: QualityControlling = QualityController(rtcManager),
    private val errorListener: (XmaxError) -> Unit = {},
    private val remoteStreamListener: (RemoteStream?) -> Unit = {},
    private val generationTiming: StreamGenerationTiming = StreamGenerationTiming.live,
    private val generationScope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default,
    ),
) : StreamControlling, RtcEventListener {
    private val stateLock = Any()
    private var state = State()
    private val remoteAudioVolume = AtomicInteger(100)

    init {
        rtcManager.setEventListener(this)
    }

    override val hasGenerationTask: Boolean
        get() = synchronized(stateLock) { state.generationTask != null }

    override fun setVideoEncoderConfig(videoFormat: RealtimeVideoFormat) {
        encodingController.configure(videoFormat)
    }

    override fun setNetworkQualityListener(listener: RealtimeNetworkQualityListener?) {
        qualityController.setNetworkQualityListener(listener)
    }

    override fun setPerformanceAlarmListener(listener: RealtimePerformanceAlarmListener?) {
        qualityController.setPerformanceAlarmListener(listener)
    }

    override fun setRemoteAudioVolume(volume: Float) {
        val rtcVolume = (volume * 100f).toInt().coerceIn(0, 100)
        val userIds = synchronized(stateLock) {
            state.subscribedRemoteAudioUserIds.toList()
        }
        userIds.forEach { rtcManager.setRemoteAudioVolume(rtcVolume, it) }
        remoteAudioVolume.set(rtcVolume)
    }

    override suspend fun connect(
        connection: RealtimeSessionConnection,
        includeLocalAudio: Boolean,
        ensureActive: () -> Unit,
    ) {
        try {
            roomController.join(connection, ensureActive)
            ensureActive()
            configureRoom(connection.roomId, connection.botName)
            publishLocalStream(includeLocalAudio)
        } catch (error: Throwable) {
            resetStream()
            roomController.leave()
            throw XmaxError.from(error)
        }
    }

    override suspend fun disconnect() {
        resetStream()
        roomController.leave()
    }

    override fun setLocalAudioEnabled(enabled: Boolean) {
        val currentState = synchronized(stateLock) { state.copy() }
        if (currentState.roomId.isEmpty() || !currentState.localVideoPublished) {
            throw XmaxError(
                XmaxErrorCode.INVALID_CONFIGURATION,
                "Publish the local video stream before updating local audio",
            )
        }
        if (currentState.localAudioPublished == enabled) return
        if (enabled) rtcManager.publishLocalAudio() else rtcManager.unpublishLocalAudio()
        synchronized(stateLock) { state.localAudioPublished = enabled }
    }

    override fun pushLocalVideoFrame(frame: VideoFrame) {
        val seiData = synchronized(stateLock) { state.generationTask?.seiData } ?: return
        try {
            rtcManager.pushExternalVideoFrame(frame, seiData)
        } catch (error: Throwable) {
            XmaxLogger.error(
                {
                    "推送 RTC 外部视频帧失败 (Failed to Push External RTC Video Frame)\n" +
                        "├─ 格式：${frame.format.pixelFormat.value}\n" +
                        "├─ 分辨率：${frame.format.width} × ${frame.format.height}\n" +
                        "├─ 时间戳：${frame.timestampUs} us\n" +
                        "└─ 原因：${ErrorMessageFormatter.format(error)}"
                },
                category = "RTC",
            )
            throw error
        }
    }

    override fun pushLocalAudioFrame(frame: AudioFrame) {
        if (!synchronized(stateLock) { state.localAudioPublished }) return
        rtcManager.pushExternalAudioFrame(frame)
    }

    override suspend fun beginGeneration(
        taskId: String,
        videoFormat: RealtimeVideoFormat,
        context: RealtimeContext,
    ): CompletableDeferred<Unit> {
        val normalizedTaskId = taskId.trim()
        if (normalizedTaskId.isEmpty()) {
            throw XmaxError(
                XmaxErrorCode.INVALID_CONFIGURATION,
                "Realtime generation task ID cannot be empty",
            )
        }
        val waiter = synchronized(stateLock) {
            if (state.roomId.isEmpty()) {
                throw XmaxError(XmaxErrorCode.RTC_ERROR, "RTC room is not configured")
            }
            if (state.generationTask != null) {
                throw XmaxError(XmaxErrorCode.RTC_ERROR, "Realtime generation is already active")
            }
            GenerationWaiter(normalizedTaskId).also {
                state.generationTask = GenerationTask(normalizedTaskId)
                state.generationWaiter = it
            }
        }
        waiter.timeoutJob = generationScope.launch {
            delay(generationTiming.timeoutMillis)
            rejectGenerationStart(
                normalizedTaskId,
                XmaxError(XmaxErrorCode.TIMEOUT, "Realtime generation start timed out"),
            )
        }
        try {
            roomController.startGeneration(normalizedTaskId, videoFormat, context)
            return waiter.result
        } catch (error: Throwable) {
            rejectGenerationStart(normalizedTaskId, XmaxError.from(error))
            stopGeneration(normalizedTaskId)
            throw XmaxError.from(error)
        }
    }

    override fun activateRemoteAudio() {
        val remoteStream = synchronized(stateLock) {
            if (state.generationTask == null) null else state.activeRemoteStream
        } ?: throw XmaxError(
            XmaxErrorCode.RTC_ERROR,
            "Remote generation audio stream is unavailable",
        )
        subscribeRemoteAudio(remoteStream.userId)
    }

    override suspend fun updateGeneration(
        taskId: String,
        videoFormat: RealtimeVideoFormat,
        context: RealtimeContext,
    ) {
        roomController.changeGenerationCondition(taskId, videoFormat, context)
    }

    override suspend fun stopGeneration(taskId: String) {
        val result = stopStreamGeneration(taskId) ?: return
        if (taskId.isNotEmpty() && result.taskId.isEmpty()) return
        roomController.stopGeneration(result.taskId)
    }

    override suspend fun sendTracks(taskId: String, points: List<RealtimePoint>) {
        roomController.sendTracks(taskId, points)
    }

    override fun onRemoteVideoPublished(userId: String, published: Boolean) {
        val normalizedUserId = userId.trim()
        if (normalizedUserId.isEmpty()) return
        val currentState = synchronized(stateLock) { state.copy() }
        if (currentState.roomId.isEmpty() ||
            (currentState.botName.isNotEmpty() && currentState.botName != normalizedUserId)
        ) {
            return
        }
        if (published) {
            subscribeRemoteVideo(normalizedUserId)
        } else {
            val shouldClear = synchronized(stateLock) {
                state.subscribedRemoteUserIds.remove(normalizedUserId)
                if (state.activeRemoteStream?.userId == normalizedUserId) {
                    state.activeRemoteStream = null
                    true
                } else {
                    false
                }
            }
            unsubscribeRemoteAudio(normalizedUserId)
            if (shouldClear) clearRemoteStream()
        }
    }

    override fun onSeiMessageReceived(stream: RemoteStream, message: String) {
        val waiter = synchronized(stateLock) {
            val task = state.generationTask
            val pending = state.generationWaiter
            if (task == null || pending == null ||
                message.trim() != task.id ||
                stream.roomId != state.roomId ||
                (state.botName.isNotEmpty() && stream.userId != state.botName)
            ) {
                null
            } else {
                pending
            }
        } ?: return

        try {
            remoteStreamListener(stream)
            synchronized(stateLock) {
                if (state.generationTask?.id == waiter.taskId) {
                    state.activeRemoteStream = stream
                }
            }
            resolveGenerationStart(waiter.taskId)
        } catch (error: Throwable) {
            rejectGenerationStart(waiter.taskId, XmaxError.from(error))
        }
    }

    private fun configureRoom(roomId: String, botName: String?) {
        val normalizedRoomId = roomId.trim()
        if (normalizedRoomId.isEmpty()) {
            throw XmaxError(XmaxErrorCode.INVALID_CONFIGURATION, "RTC room ID cannot be empty")
        }
        synchronized(stateLock) {
            if (state.localVideoPublished || state.localAudioPublished ||
                state.subscribedRemoteUserIds.isNotEmpty() || state.generationTask != null
            ) {
                throw XmaxError(
                    XmaxErrorCode.INVALID_CONFIGURATION,
                    "Reset the current RTC room before configuring another one",
                )
            }
            state.roomId = normalizedRoomId
            state.botName = botName?.trim().orEmpty()
        }
    }

    private fun publishLocalStream(includeAudio: Boolean) {
        val currentState = synchronized(stateLock) { state.copy() }
        if (currentState.roomId.isEmpty()) {
            throw XmaxError(
                XmaxErrorCode.INVALID_CONFIGURATION,
                "Configure an RTC room before publishing the local stream",
            )
        }
        var publishedVideo = false
        try {
            if (!currentState.localVideoPublished) {
                rtcManager.publishLocalVideo()
                synchronized(stateLock) { state.localVideoPublished = true }
                publishedVideo = true
            }
            if (includeAudio && !currentState.localAudioPublished) {
                rtcManager.publishLocalAudio()
                synchronized(stateLock) { state.localAudioPublished = true }
            }
        } catch (error: Throwable) {
            if (publishedVideo) {
                performCleanup(
                    "回滚 RTC 本地视频发布失败 " +
                        "(Failed to Roll Back RTC Local Video Publication)",
                ) {
                    rtcManager.unpublishLocalVideo()
                }
            }
            throw XmaxError.from(error)
        }
    }

    private suspend fun resetStream() {
        stopStreamGeneration("")
        val previous = synchronized(stateLock) {
            state.also { state = State() }
        }
        previous.subscribedRemoteUserIds.sorted().forEach {
            performCleanup(
                "取消订阅 RTC 远端视频失败 (Failed to Unsubscribe RTC Remote Video)",
            ) {
                rtcManager.subscribeRemoteVideo(it, false)
            }
        }
        if (previous.localAudioPublished) {
            performCleanup(
                "取消发布 RTC 本地音频失败 (Failed to Unpublish RTC Local Audio)",
            ) {
                rtcManager.unpublishLocalAudio()
            }
        }
        if (previous.localVideoPublished) {
            performCleanup(
                "取消发布 RTC 本地视频失败 (Failed to Unpublish RTC Local Video)",
            ) {
                rtcManager.unpublishLocalVideo()
            }
        }
        clearRemoteStream()
    }

    private fun stopStreamGeneration(taskId: String): StopResult? {
        val result = synchronized(stateLock) {
            val currentTaskId = state.generationTask?.id.orEmpty()
            if (taskId.isNotEmpty() && taskId != currentTaskId) return null
            StopResult(
                taskId = currentTaskId,
                waiter = state.generationWaiter,
                remoteAudioUserIds = state.subscribedRemoteAudioUserIds.toSet(),
            ).also {
                state.generationTask = null
                state.generationWaiter = null
                state.activeRemoteStream = null
                state.subscribedRemoteAudioUserIds.clear()
            }
        }
        result.waiter?.let {
            it.timeoutJob?.cancel()
            it.result.completeExceptionally(
                XmaxError(XmaxErrorCode.CANCELLED, "Realtime generation start cancelled"),
            )
        }
        result.remoteAudioUserIds.sorted().forEach {
            performCleanup(
                "取消订阅 RTC 远端音频失败 (Failed to Unsubscribe RTC Remote Audio)",
            ) {
                rtcManager.subscribeRemoteAudio(it, false)
            }
        }
        clearRemoteStream()
        return result
    }

    private fun subscribeRemoteVideo(userId: String) {
        if (synchronized(stateLock) { userId in state.subscribedRemoteUserIds }) return
        try {
            rtcManager.subscribeRemoteVideo(userId, true)
            synchronized(stateLock) { state.subscribedRemoteUserIds += userId }
        } catch (error: Throwable) {
            val xmaxError = XmaxError.from(error)
            val taskId = synchronized(stateLock) { state.generationWaiter?.taskId }
            if (taskId != null) rejectGenerationStart(taskId, xmaxError) else reportError(xmaxError)
        }
    }

    private fun subscribeRemoteAudio(userId: String) {
        if (synchronized(stateLock) { userId in state.subscribedRemoteAudioUserIds }) return
        rtcManager.setRemoteAudioVolume(remoteAudioVolume.get(), userId)
        rtcManager.subscribeRemoteAudio(userId, true)
        synchronized(stateLock) { state.subscribedRemoteAudioUserIds += userId }
    }

    private fun unsubscribeRemoteAudio(userId: String) {
        val subscribed = synchronized(stateLock) {
            state.subscribedRemoteAudioUserIds.remove(userId)
        }
        if (subscribed) {
            performCleanup(
                "取消订阅 RTC 远端音频失败 (Failed to Unsubscribe RTC Remote Audio)",
            ) {
                rtcManager.subscribeRemoteAudio(userId, false)
            }
        }
    }

    private fun resolveGenerationStart(taskId: String) {
        val waiter = synchronized(stateLock) {
            state.generationWaiter?.takeIf { it.taskId == taskId }?.also {
                state.generationWaiter = null
            }
        } ?: return
        waiter.timeoutJob?.cancel()
        waiter.result.complete(Unit)
    }

    private fun rejectGenerationStart(taskId: String, error: XmaxError) {
        val waiter = synchronized(stateLock) {
            state.generationWaiter?.takeIf { it.taskId == taskId }?.also {
                state.generationWaiter = null
            }
        } ?: return
        waiter.timeoutJob?.cancel()
        waiter.result.completeExceptionally(error)
    }

    private fun clearRemoteStream() {
        runCatching { remoteStreamListener(null) }
            .onFailure {
                XmaxLogger.error(
                    {
                        "清理 RTC 远端生成流失败 " +
                            "(Failed to Clean Up RTC Remote Generation Stream)\n" +
                            "└─ 原因：${ErrorMessageFormatter.format(it)}"
                    },
                    category = "Stream",
                )
                reportError(XmaxError.from(it))
            }
    }

    private inline fun performCleanup(title: String, action: () -> Unit) {
        runCatching(action).onFailure { error ->
            XmaxLogger.error(
                { "$title\n└─ 原因：${ErrorMessageFormatter.format(error)}" },
                category = "Stream",
            )
        }
    }

    private fun reportError(error: XmaxError) {
        runCatching { errorListener(error) }
    }

    private data class State(
        var roomId: String = "",
        var botName: String = "",
        var localVideoPublished: Boolean = false,
        var localAudioPublished: Boolean = false,
        val subscribedRemoteUserIds: MutableSet<String> = mutableSetOf(),
        val subscribedRemoteAudioUserIds: MutableSet<String> = mutableSetOf(),
        var generationTask: GenerationTask? = null,
        var generationWaiter: GenerationWaiter? = null,
        var activeRemoteStream: RemoteStream? = null,
    )

    private data class GenerationTask(
        val id: String,
        val seiData: ByteArray = id.toByteArray(Charsets.UTF_8),
    )

    private class GenerationWaiter(val taskId: String) {
        val result = CompletableDeferred<Unit>()
        var timeoutJob: Job? = null
    }

    private data class StopResult(
        val taskId: String,
        val waiter: GenerationWaiter?,
        val remoteAudioUserIds: Set<String>,
    )
}

/** 定义生成确认等待超时。 */
internal data class StreamGenerationTiming(val timeoutMillis: Long) {
    companion object {
        val live = StreamGenerationTiming(timeoutMillis = 30_000L)
    }
}
