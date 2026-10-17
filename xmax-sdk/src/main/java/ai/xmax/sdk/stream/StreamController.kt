//
//  StreamController.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.stream

import ai.xmax.sdk.cleanupResources
import ai.xmax.sdk.cleanupAfterFailure
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CancellationException
import ai.xmax.sdk.AudioFrame
import ai.xmax.sdk.RealtimeTiming
import ai.xmax.sdk.RealtimeContext
import ai.xmax.sdk.RealtimeNetworkQualityListener
import ai.xmax.sdk.RealtimeNetworkQuality
import ai.xmax.sdk.RealtimePerformanceAlarmListener
import ai.xmax.sdk.RealtimePoint
import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.VideoFrame
import ai.xmax.sdk.UplinkVideoStatisticsListener
import ai.xmax.sdk.DownlinkVideoStatisticsListener
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
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.math.roundToInt

/**
 * 统一协调 RTC 房间、媒体流、编码和质量事件。
 *
 * @param rtcManager 提供 RTC 采集、发布、订阅和渲染能力的管理器。
 * @param roomController 负责 RTC 进房和业务信令的控制器。
 * @param encodingController 负责计算并应用上行编码参数的控制器。
 * @param qualityController 负责网络质量统计及自动档位建议的控制器。
 * @param errorListener 接收媒体错误的回调。
 * @param remoteStreamListener 当前远端流变化时的回调；null 流表示没有活动结果流。
 * @param generationTiming 生成启动的等待超时配置；默认值为 `StreamGenerationTiming.live`。
 * @param generationScope 处理生成结果确认及完成通知的协程作用域。
 * @param renderDispatcher 执行视图绑定和显示更新的协程调度器；默认值为 `Dispatchers.Main.immediate`。
 */
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
    private val renderDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : StreamControlling, RtcEventListener {
    /**
     * 发布订阅状态与 RTC 事件处理的并发保护。
     */
    private val stateLock = Any()
    private val eventGate = ReentrantLock()

    /**
     * 当前房间、媒体发布和生成任务状态。
     */
    private var state = State()

    /**
     * 远端结果音频的播放音量。
     */
    private val remoteAudioVolume = AtomicInteger(100)

    init {
        rtcManager.setEventListener(this)
    }

    /**
     * 更新上行视频的尺寸、帧率和编码码率配置。
     *
     * @param videoFormat 视频尺寸、帧率及上行编码参数。
     */
    override fun setVideoEncoderConfig(videoFormat: RealtimeVideoFormat) {
        encodingController.configure(videoFormat)
    }

    /**
     * 接收自动调档使用的网络质量样本，不占用公共监听器。
     *
     * @param listener 接收对应事件的监听器；null 表示注销。
     */
    override fun setAdaptiveQualityListener(listener: ((RealtimeNetworkQuality) -> Unit)?) {
        qualityController.setAdaptiveQualityListener(listener)
    }

    /**
     * 设置上行视频统计监听器，回调在主线程执行；传 null 注销，断开连接后仍保留监听器。
     *
     * @param listener 接收对应事件的监听器；null 表示注销。
     */
    override fun setUplinkVideoStatisticsListener(listener: UplinkVideoStatisticsListener?) {
        qualityController.setUplinkVideoStatisticsListener(listener)
    }

    /**
     * 设置下行结果流统计监听器，回调在主线程执行；传 null 注销，断开连接后仍保留监听器。
     *
     * @param listener 接收对应事件的监听器；null 表示注销。
     */
    override fun setDownlinkVideoStatisticsListener(listener: DownlinkVideoStatisticsListener?) {
        qualityController.setDownlinkVideoStatisticsListener(listener)
    }

    /**
     * 设置 RTC 网络质量监听器；传 null 注销。
     *
     * @param listener 接收对应事件的监听器；null 表示注销。
     */
    override fun setNetworkQualityListener(listener: RealtimeNetworkQualityListener?) {
        qualityController.setNetworkQualityListener(listener)
    }

    /**
     * 设置 RTC 性能告警监听器；传 null 注销。
     *
     * @param listener 接收对应事件的监听器；null 表示注销。
     */
    override fun setPerformanceAlarmListener(listener: RealtimePerformanceAlarmListener?) {
        qualityController.setPerformanceAlarmListener(listener)
    }

    /**
     * 设置当前结果流的音频播放音量，范围为 0 到 1，并保留供后续订阅使用。
     *
     * @param volume 播放音量，范围为 0 到 1。
     */
    override fun setRemoteAudioVolume(volume: Float) {
        val rtcVolume = (volume * 100f).roundToInt().coerceIn(0, 100)
        val userIds = synchronized(stateLock) {
            state.subscribedRemoteAudioUserIds.toList()
        }
        userIds.forEach { rtcManager.setRemoteAudioVolume(rtcVolume, it) }
        remoteAudioVolume.set(rtcVolume)
    }

    /**
     * 加入 RTC 房间，按输入来源发布本地媒体并启用对应上行统计。
     *
     * @param connection 服务端返回的 RTC 房间、用户、鉴权及结果流信息。
     * @param includeLocalAudio 是否同时发布本地音频。
     * @param publishLocalMedia 是否发布本地媒体；服务端直接读取网络视频时为 false。
     * @param ensureActive 检查当前操作是否仍可继续的函数，取消时应抛出异常。
     */
    override suspend fun connect(
        connection: RealtimeSessionConnection,
        includeLocalAudio: Boolean,
        publishLocalMedia: Boolean,
        ensureActive: () -> Unit,
    ) {
        try {
            /**
             * 入房完成时远端发布事件即可到达，必须先准备好订阅所需的房间信息。
             */
            configureRoom(connection.roomId, connection.botName)
            roomController.join(connection, ensureActive)
            ensureActive()
            if (publishLocalMedia) {
                val timing = currentCoroutineContext()[RealtimeTiming.Attempt]
                timing?.mark(RealtimeTiming.Stage.PUBLICATION_START)
                publishLocalStream(includeLocalAudio)
                timing?.mark(RealtimeTiming.Stage.PUBLICATION_END)
            }
            qualityController.setUplinkVideoStatisticsEnabled(publishLocalMedia)
        } catch (error: Throwable) {
            cleanupAfterFailure(error, { resetStream() }, { roomController.leave() })
            throw XmaxError.from(error)
        }
    }

    /**
     * 解除媒体发布、远端订阅和生成等待，并离开当前 RTC 房间。
     */
    override suspend fun disconnect() {
        cleanupResources({ resetStream() }, { roomController.leave() })
    }

    /**
     * 当前生成对外部输入帧的需求。
     */
    internal val isRequestingExternalFrames: Boolean
        get() = synchronized(stateLock) { state.generationTask != null }

    /**
     * 在生成需要输入帧时向 RTC 提交本地视频帧。
     *
     * @param frame 待处理、显示或提交的视频帧。
     */
    override fun pushLocalVideoFrame(frame: VideoFrame) {
        if (!isRequestingExternalFrames) return
        try {
            rtcManager.pushExternalVideoFrame(frame, null)
        } catch (error: Throwable) {
            XmaxLogger.rtc.error(
                message = {
                    "推送 RTC 外部视频帧失败 (Failed to Push External RTC Video Frame)\n" +
                        "├─ ${XmaxLogger.localized("格式：", "Format: ")}${frame.format.pixelFormat.value}\n" +
                        "├─ ${XmaxLogger.localized("分辨率：", "Resolution: ")}${frame.format.width} × ${frame.format.height}\n" +
                        "├─ ${XmaxLogger.localized("时间戳：", "Timestamp: ")}${frame.timestampUs} us\n" +
                        "└─ ${XmaxLogger.localized("原因：", "Reason: ")}${ErrorMessageFormatter.format(error)}"
                },
            )
            throw error
        }
    }

    /**
     * 在本地音频已发布时向 RTC 提交音频帧。
     *
     * @param frame 待播放或提交的 PCM 音频帧。
     */
    override fun pushLocalAudioFrame(frame: AudioFrame) {
        if (!synchronized(stateLock) { state.localAudioPublished }) return
        rtcManager.pushExternalAudioFrame(frame)
    }

    /**
     * 启动生成并等待结果流确认；回传规格缺省时沿用生成规格，上传码率字段不影响下行。
     *
     * @param taskId 用于关联本次生成请求和结果流的任务标识。
     * @param videoFormat 视频尺寸、帧率及上行编码参数。
     * @param context 生成提示词及参考素材。
     * @param faceRestoration 是否启用人脸修复，对应生成参数 `face_replace`。
     * @param targetVideoFormat 下行输出的视频规格。
     * @param paddingContain 是否让服务端等比适配输入并在输出时移除补边。
     * @return 生成结果流确认时完成的等待器，启动失败时以异常结束。
     */
    override suspend fun beginGeneration(
        taskId: String,
        videoFormat: RealtimeVideoFormat,
        context: RealtimeContext,
        faceRestoration: Boolean,
        targetVideoFormat: RealtimeVideoFormat?,
        paddingContain: Boolean,
    ): CompletableDeferred<Unit> {
        val normalizedTaskId = taskId.trim()
        if (normalizedTaskId.isEmpty()) {
            throw XmaxError(
                XmaxErrorCode.INVALID_CONFIGURATION,
                "Realtime generation task ID cannot be empty",
            )
        }
        val timing = currentCoroutineContext()[RealtimeTiming.Attempt]
        val waiter = synchronized(stateLock) {
            if (state.roomId.isEmpty()) {
                throw XmaxError(XmaxErrorCode.RTC_ERROR, "RTC room is not configured")
            }
            if (state.generationTask != null) {
                throw XmaxError(XmaxErrorCode.RTC_ERROR, "Realtime generation is already active")
            }
            GenerationWaiter(normalizedTaskId, timing).also {
                state.generationTask = GenerationTask(normalizedTaskId, context.referenceVideo != null)
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
            timing?.beginSignal(normalizedTaskId)
            roomController.startGeneration(normalizedTaskId, videoFormat, context, faceRestoration, targetVideoFormat, paddingContain)
            withTimeout(generationTiming.timeoutMillis) { rtcManager.awaitPendingMessages() }
            synchronized(stateLock) { waiter.signalSent = true }
            /**
             * 声网机器人可能在 start 之前已发布，复用订阅并重新等待本轮首帧。
             */
            withContext(renderDispatcher) {
                eventGate.withLock {
                    val users = synchronized(stateLock) { state.subscribedRemoteUserIds.toList() }
                    users.forEach { confirmRemoteStream(RemoteStream(state.roomId, it)) }
                }
            }
            return waiter.result
        } catch (error: Throwable) {
            rejectGenerationStart(normalizedTaskId, error)
            cleanupAfterFailure(error, { stopGeneration(normalizedTaskId) })
            throw XmaxError.from(error)
        }
    }

    /**
     * 启用网络视频完成回调，满足生成首帧和服务端完成条件后通知。
     *
     * @param onFinish 网络视频任务完成的通知；null 表示不接收完成通知。
     */
    override fun activateNetworkVideoCompletion(onFinish: (() -> Unit)?) {
        val task = synchronized(stateLock) {
            state.generationTask?.takeIf { it.isNetworkVideo && !it.completionCancelled }?.also {
                it.onFinish = onFinish
                it.completionReady = true
            }
        } ?: return
        scheduleNetworkVideoCompletion(task)
    }

    /**
     * 接收房间内远端用户的结构化任务通知。
     *
     * @param stream 由房间和用户标识确定的远端媒体流。
     * @param message 待发送或记录的消息文本。
     */
    override fun onUserMessageReceived(stream: RemoteStream, message: String) {
        val event = runCatching { org.json.JSONObject(message) }.getOrNull() ?: return
        if (event.optString("event") != "video_stopped") return
        val task = synchronized(stateLock) {
            state.generationTask?.takeIf {
                it.isNetworkVideo && event.optString("uid") == it.id &&
                    stream.roomId == state.roomId && stream.userId.isNotBlank() &&
                    (state.botName.isEmpty() || stream.userId == state.botName)
            }?.also { it.finished = true }
        } ?: return
        scheduleNetworkVideoCompletion(task)
    }

    /**
     * 为当前网络视频任务安排完成通知。
     *
     * @param task 当前生成任务状态。
     */
    private fun scheduleNetworkVideoCompletion(task: GenerationTask) {
        val shouldSchedule = synchronized(stateLock) {
            if (state.generationTask !== task || task.completionCancelled || !task.finished || !task.completionReady ||
                task.completionScheduled || task.onFinish == null
            ) false else {
                task.completionScheduled = true
                true
            }
        }
        if (!shouldSchedule) return
        generationScope.launch(renderDispatcher) {
            /**
             * 主线程执行前再次核对任务，避免关闭或重试后的排队通知串入新任务。
             */
            val callback = synchronized(stateLock) {
                task.onFinish.takeIf { state.generationTask === task && !task.completionCancelled }
            }
            runCatching { callback?.invoke() }.onFailure {
                XmaxLogger.stream.warn(message = { "Network video completion listener failed: ${it.message}" })
            }
        }
    }

    /**
     * 订阅当前结果流的音频并应用远端播放音量。
     */
    override fun activateRemoteAudio() {
        eventGate.withLock {
            val remoteStream = synchronized(stateLock) {
                if (state.generationTask == null) null else state.activeRemoteStream
            } ?: throw XmaxError(
                XmaxErrorCode.RTC_ERROR,
                "Remote generation audio stream is unavailable",
            )
            subscribeRemoteAudio(remoteStream.userId)
        }
    }

    /**
     * 更新当前任务的生成条件，不重新创建服务端会话。
     *
     * @param taskId 用于关联本次生成请求和结果流的任务标识。
     * @param videoFormat 视频尺寸、帧率及上行编码参数。
     * @param context 生成提示词及参考素材。
     * @param faceRestoration 是否启用人脸修复，对应生成参数 `face_replace`。
     * @param paddingContain 是否让服务端等比适配输入并在输出时移除补边。
     */
    override suspend fun updateGeneration(
        taskId: String,
        videoFormat: RealtimeVideoFormat,
        context: RealtimeContext,
        faceRestoration: Boolean,
        paddingContain: Boolean,
    ) {
        roomController.changeGenerationCondition(taskId, videoFormat, context, faceRestoration, paddingContain)
    }

    /**
     * 发送停止指定生成任务的信令。
     *
     * @param taskId 用于关联本次生成请求和结果流的任务标识。
     */
    override suspend fun stopGeneration(taskId: String) {
        val result = stopStreamGeneration(taskId) ?: return
        if (taskId.isNotEmpty() && result.taskId.isEmpty()) return
        roomController.stopGeneration(result.taskId)
    }

    /**
     * 更新当前任务的回传尺寸与码率；下行码率按固定 24 fps 估算。
     *
     * @param taskId 用于关联本次生成请求和结果流的任务标识。
     * @param targetVideoFormat 下行输出的视频规格。
     * @param ensureActive 检查当前操作是否仍可继续的函数，取消时应抛出异常。
     */
    override suspend fun updateDownlinkVideoResolution(
        taskId: String,
        targetVideoFormat: RealtimeVideoFormat,
        ensureActive: () -> Unit,
    ) {
        eventGate.withLock {
            synchronized(stateLock) {
                ensureActive()
                val task = state.generationTask
                if (task == null || task.id != taskId || task.completionCancelled || state.activeRemoteStream == null) {
                    throw CancellationException("Downlink video update requires the current active generation")
                }

                roomController.changeTargetSize(taskId, targetVideoFormat, ensureActive)
            }
        }
    }

    /**
     * 向服务端发送当前任务的画面像素坐标轨迹。
     *
     * @param taskId 用于关联本次生成请求和结果流的任务标识。
     * @param points 视频画面像素坐标中的轨迹点集合。
     */
    override suspend fun sendTracks(taskId: String, points: List<RealtimePoint>) {
        roomController.sendTracks(taskId, points)
    }

    /**
     * 处理指定房间内远端用户的视频发布状态变化，避免旧房间事件串入新连接。
     *
     * @param stream 由房间和用户标识确定的远端媒体流。
     * @param published 远端视频是否处于发布状态。
     */
    override fun onRemoteVideoPublished(stream: RemoteStream, published: Boolean) {
        eventGate.withLock {
            val normalizedUserId = stream.userId.trim()
            if (normalizedUserId.isEmpty()) return
            val currentState = synchronized(stateLock) { state.copy() }
            if (currentState.roomId.isEmpty() || currentState.roomId != stream.roomId ||
                (currentState.botName.isNotEmpty() && currentState.botName != normalizedUserId)
            ) {
                return
            }
            if (published) {
                subscribeRemoteVideo(normalizedUserId)
                confirmRemoteStream(stream)
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
    }

    /**
     * 报告当前 RTC 房间已不可继续使用的终止故障。
     *
     * @param roomId RTC 房间标识。
     * @param error 本次操作失败的异常或 SDK 错误。
     */
    override fun onRoomTerminated(roomId: String, error: XmaxError) {
        if (synchronized(stateLock) { state.roomId == roomId }) reportError(error)
    }

    /**
     * 处理远端视频流携带的 SEI 消息。
     *
     * @param stream 由房间和用户标识确定的远端媒体流。
     * @param message 待发送或记录的消息文本。
     */
    override fun onSeiMessageReceived(stream: RemoteStream, message: String) {
        /**
         * 声网按目标机器人发布确认，不使用 SEI 判断任务就绪。
         */
    }

    /**
     * 确认当前任务的结果流已可用，忽略其他任务的确认。
     *
     * @param stream 由房间和用户标识确定的远端媒体流。
     */
    private fun confirmRemoteStream(stream: RemoteStream) {
        eventGate.withLock {
            val taskId: String
            val waiter: GenerationWaiter?
            synchronized(stateLock) {
                val task = state.generationTask ?: return
                if (stream.roomId != state.roomId || stream.userId !in state.subscribedRemoteUserIds ||
                    (state.botName.isNotEmpty() && stream.userId != state.botName)
                ) return

                waiter = state.generationWaiter
                if (waiter != null && !waiter.signalSent) return
                if (waiter == null && state.activeRemoteStream == stream) return
                taskId = task.id
            }

            try {
                waiter?.timing?.confirmRemoteStream(taskId)
                remoteStreamListener(stream)
                qualityController.setDownlinkVideoStatisticsUser(stream.userId)
                synchronized(stateLock) {
                    if (state.generationTask?.id == taskId) state.activeRemoteStream = stream
                }
                resolveGenerationStart(taskId)
            } catch (error: Throwable) {
                if (waiter != null) rejectGenerationStart(taskId, XmaxError.from(error))
                else reportError(XmaxError.from(error))
            }
        }
    }

    /**
     * 设置订阅和信令路由使用的房间与机器人标识。
     *
     * @param roomId RTC 房间标识。
     * @param botName 生成结果发布者的用户标识。
     */
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

    /**
     * 发布本地视频及可选音频，发布失败时撤销已完成的部分。
     *
     * @param includeAudio 是否同时发布本地音频。
     */
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

    /**
     * 清空流状态并解除本地发布及远端订阅。
     */
    private suspend fun resetStream() {
        qualityController.setUplinkVideoStatisticsEnabled(false)
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
    }

    /**
     * 移除当前生成任务及其等待器，保留需要清理的资源快照。
     *
     * @param taskId 用于关联本次生成请求和结果流的任务标识。
     * @return 已停止任务需要清理的资源；无匹配任务时为 null。
     */
    private suspend fun stopStreamGeneration(taskId: String): StopResult? {
        /**
         * 切换主线程清理画面前，先使排队中的完成通知失效。
         */
        synchronized(stateLock) {
            state.generationTask?.takeIf { taskId.isEmpty() || it.id == taskId }?.let {
                it.completionCancelled = true
                it.onFinish = null
            }
        }
        return withContext(NonCancellable + renderDispatcher) {
            /**
             * 在主线程先切回本地预览再清空远端画布；线程切换时不持有事件或渲染锁。
             */
            eventGate.withLock {
                val result = synchronized(stateLock) {
                    val currentTaskId = state.generationTask?.id.orEmpty()
                    if (taskId.isNotEmpty() && taskId != currentTaskId) return@withContext null
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
                        CancellationException("Realtime generation start cancelled"),
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
                result
            }
        }
    }

    /**
     * 订阅当前生成结果发布者的视频。
     *
     * @param userId 房间中的业务用户标识。
     */
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

    /**
     * 订阅当前生成结果发布者的音频。
     *
     * @param userId 房间中的业务用户标识。
     */
    private fun subscribeRemoteAudio(userId: String) {
        if (synchronized(stateLock) { userId in state.subscribedRemoteAudioUserIds }) return
        rtcManager.setRemoteAudioVolume(remoteAudioVolume.get(), userId)
        rtcManager.subscribeRemoteAudio(userId, true)
        synchronized(stateLock) { state.subscribedRemoteAudioUserIds += userId }
    }

    /**
     * 取消指定用户的远端音频订阅。
     *
     * @param userId 房间中的业务用户标识。
     */
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

    /**
     * 完成匹配任务的生成启动等待。
     *
     * @param taskId 用于关联本次生成请求和结果流的任务标识。
     */
    private fun resolveGenerationStart(taskId: String) {
        val waiter = synchronized(stateLock) {
            state.generationWaiter?.takeIf { it.taskId == taskId }?.also {
                state.generationWaiter = null
            }
        } ?: return
        waiter.timeoutJob?.cancel()
        waiter.result.complete(Unit)
    }

    /**
     * 以指定错误结束匹配任务的生成启动等待。
     *
     * @param taskId 用于关联本次生成请求和结果流的任务标识。
     * @param error 本次操作失败的异常或 SDK 错误。
     */
    private fun rejectGenerationStart(taskId: String, error: Throwable) {
        val waiter = synchronized(stateLock) {
            state.generationWaiter?.takeIf { it.taskId == taskId }?.also {
                state.generationWaiter = null
            }
        } ?: return
        waiter.timeoutJob?.cancel()
        waiter.result.completeExceptionally(error)
    }

    /**
     * 清除当前结果流并通知渲染层解除绑定。
     */
    private fun clearRemoteStream() {
        qualityController.setDownlinkVideoStatisticsUser(null)
        runCatching { remoteStreamListener(null) }
            .onFailure {
                XmaxLogger.stream.error(
                    message = {
                        "清理 RTC 远端生成流失败 " +
                            "(Failed to Clean Up RTC Remote Generation Stream)\n" +
                            "└─ ${XmaxLogger.localized("原因：", "Reason: ")}${ErrorMessageFormatter.format(it)}"
                    },
                )
            }
    }

    /**
     * 执行清理并记录失败，避免清理异常阻断其他资源释放。
     *
     * @param title 日志条目的说明标题。
     * @param action 需要执行的操作。
     */
    private inline fun performCleanup(title: String, action: () -> Unit) {
        runCatching(action).onFailure { error ->
            XmaxLogger.stream.error(
                message = {
                    "$title\n└─ ${XmaxLogger.localized("原因：", "Reason: ")}${ErrorMessageFormatter.format(error)}"
                },
            )
        }
    }

    /**
     * 报告当前媒体输入或生成任务的失败。
     *
     * @param error 本次操作失败的异常或 SDK 错误。
     */
    private fun reportError(error: XmaxError) {
        runCatching { errorListener(error) }
    }

    /**
     * 保存控制器当前的生命周期或发布订阅状态。
     *
     * @param roomId RTC 房间标识。
     * @param botName 生成结果发布者的用户标识。
     * @param localVideoPublished 本地视频是否已发布；默认值为 `false`。
     * @param localAudioPublished 本地音频是否已发布；默认值为 `false`。
     * @param subscribedRemoteUserIds 已订阅视频的远端用户标识集合。
     * @param subscribedRemoteAudioUserIds 已订阅音频的远端用户标识集合。
     * @param generationTask 当前生成任务；null 表示没有正在生成的任务。
     * @param generationWaiter 当前生成启动的等待器；null 表示没有等待中的启动。
     * @param activeRemoteStream 当前结果流；null 表示尚无活动结果流。
     */
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

    /**
     * 保存当前任务的生成类型及完成通知状态。
     *
     * @param id 用于区分当前对象或操作的唯一标识。
     * @param isNetworkVideo 是否为服务端直接读取网络视频的生成任务。
     */
    private class GenerationTask(val id: String, val isNetworkVideo: Boolean) {
        /**
         * 网络视频任务的完成条件与通知状态。
         */
        var finished = false
        var completionCancelled = false
        var completionReady = false
        var completionScheduled = false
        var onFinish: (() -> Unit)? = null
    }

    /**
     * 关联当前生成任务及其启动结果等待器。
     *
     * @param taskId 用于关联本次生成请求和结果流的任务标识。
     * @param timing 记录本次生成启动耗时的统计对象。
     */
    private class GenerationWaiter(val taskId: String, val timing: RealtimeTiming.Attempt?) {
        /**
         * 生成结果流确认、超时任务及信令提交状态。
         */
        val result = CompletableDeferred<Unit>()
        var timeoutJob: Job? = null
        var signalSent = false
    }

    /**
     * 保存终止任务后需要清理的等待器和音频订阅。
     *
     * @param taskId 用于关联本次生成请求和结果流的任务标识。
     * @param waiter 需要完成或取消的生成等待器。
     * @param remoteAudioUserIds 需要取消音频订阅的远端用户标识集合。
     */
    private data class StopResult(
        val taskId: String,
        val waiter: GenerationWaiter?,
        val remoteAudioUserIds: Set<String>,
    )
}

/**
 * 定义生成确认等待超时。
 *
 * @param timeoutMillis 等待操作完成的最大时长，单位为毫秒。
 */
internal data class StreamGenerationTiming(val timeoutMillis: Long) {
    /**
     * 提供 StreamGenerationTiming 使用的构造入口、共享配置或常量。
     */
    companion object {
        /**
         * 共享常量、默认配置与工厂入口。
         */
        val live = StreamGenerationTiming(timeoutMillis = 30_000L)
    }
}
