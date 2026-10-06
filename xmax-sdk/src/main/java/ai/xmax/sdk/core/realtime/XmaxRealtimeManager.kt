package ai.xmax.sdk

import ai.xmax.sdk.RealtimeCoordinator.OperationKind
import ai.xmax.sdk.RealtimeCoordinator.TerminationScope
import ai.xmax.sdk.media.MediaControlling
import ai.xmax.sdk.render.video.RealtimeVideoFrameDispatcher
import ai.xmax.sdk.service.network.ApiServicing
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext

/**
 * 实时公共接口的业务编排层，连接媒体源、服务端会话、生成任务和远端呈现。
 * 生命周期状态及操作所有权由 RealtimeCoordinator 管理，用户通知交给 RealtimeCallbacks。
 * 组件按需创建；每代 Runtime 独立标识错误来源，防止已释放组件的迟到回调影响新连接。
 */
internal class XmaxRealtimeManager(
    override val options: RealtimeConfiguration,
    private val componentFactory: ((XmaxError) -> Unit, (XmaxError) -> Unit, RealtimeVideoFrameDispatcher) -> RealtimeComponents,
    private val callbacks: RealtimeCallbacks = RealtimeCallbacks(),
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val timing: RealtimeTiming = RealtimeTiming(),
) : XmaxRealtimeManaging {
    constructor(options: RealtimeConfiguration, context: Context, apiService: ApiServicing) :
        this(options, { onError, onMediaError, frames ->
            createRealtimeComponents(context, apiService, options.model, onError, onMediaError, frames)
        })

    /** 后台回调也会读取运行时身份；创建与释放由协调器串行执行。 */
    @Volatile private var runtime: Runtime? = null
    // 接入方配置属于 Manager，不能随一次媒体生命周期销毁；null 表示使用组件默认值。
    @Volatile private var configuredLocalAudioVolume: Float? = null
    @Volatile private var configuredRemoteAudioVolume: Float? = null
    private var networkQualityListener: RealtimeNetworkQualityListener? = null
    private var performanceAlarmListener: RealtimePerformanceAlarmListener? = null
    private val coordinator = RealtimeCoordinator(
        callbacks,
        dispatcher,
        hasLocalMedia = { runtime?.components?.media?.currentTrack != null },
        cleanup = ::cleanup,
    )
    override val currentState: RealtimeState get() = coordinator.currentState
    override val localAudioVolume: Float get() = configuredLocalAudioVolume ?: 0.45f
    override val remoteAudioVolume: Float get() = configuredRemoteAudioVolume ?: 1f

    override suspend fun setStateListener(listener: RealtimeStateListener?) {
        callbacks.setStateListener(listener, currentState)
    }
    override suspend fun setRemoteVideoFrameListener(listener: RealtimeVideoFrameListener?) {
        callbacks.remoteVideoFrames.setListener(listener)
    }
    override suspend fun setNetworkQualityListener(listener: RealtimeNetworkQualityListener?) {
        execute(OperationKind.SETTING) { _, c ->
            c.stream.setNetworkQualityListener(listener)
            networkQualityListener = listener
        }
    }
    override suspend fun setPerformanceAlarmListener(listener: RealtimePerformanceAlarmListener?) {
        execute(OperationKind.SETTING) { _, c ->
            c.stream.setPerformanceAlarmListener(listener)
            performanceAlarmListener = listener
        }
    }
    override suspend fun setLocalAudioVolume(volume: Float) {
        execute(OperationKind.SETTING) { _, c ->
            validateAudioVolume(volume)
            c.media.setLocalAudioVolume(volume)
            configuredLocalAudioVolume = volume
        }
    }
    override suspend fun setRemoteAudioVolume(volume: Float) {
        execute(OperationKind.SETTING) { _, c ->
            validateAudioVolume(volume)
            c.stream.setRemoteAudioVolume(volume)
            configuredRemoteAudioVolume = (volume * 100f).roundToInt() / 100f
        }
    }

    override suspend fun createLocalCameraStream(
        videoFormat: RealtimeVideoFormat,
        position: CameraPosition,
        useMicrophone: Boolean,
    ): RealtimeMediaStream = mediaOperation(
        sourceRemoteAudioVolume = 0f,
        waitForCameraPreview = true,
    ) {
        it.createLocalCameraStream(videoFormat, position, useMicrophone)
    }
    override suspend fun createLocalImageStream(imageData: ByteArray, videoFormat: RealtimeVideoFormat?): RealtimeMediaStream =
        mediaOperation(sourceRemoteAudioVolume = 0f) { it.createLocalImageStream(imageData, videoFormat) }
    override suspend fun createLocalImageStream(bitmap: Bitmap, videoFormat: RealtimeVideoFormat?): RealtimeMediaStream =
        mediaOperation(sourceRemoteAudioVolume = 0f) { it.createLocalImageStream(bitmap, videoFormat) }
    override suspend fun createLocalImageStream(uri: Uri, videoFormat: RealtimeVideoFormat?): RealtimeMediaStream =
        mediaOperation(sourceRemoteAudioVolume = 0f) { it.createLocalImageStream(uri, videoFormat) }
    override suspend fun createLocalVideoStream(uri: Uri, videoFormat: RealtimeVideoFormat?): RealtimeMediaStream =
        mediaOperation(sourceRemoteAudioVolume = 1f) { it.createLocalVideoStream(uri, videoFormat) }
    override suspend fun createExternalVideoStream(
        source: RealtimeExternalVideoSource,
        videoFormat: RealtimeVideoFormat?,
    ): RealtimeMediaStream =
        mediaOperation(sourceRemoteAudioVolume = 1f) { it.createExternalVideoStream(source, videoFormat) }
    override suspend fun stopExternalVideoStream() { mediaOperation { it.stopExternalVideoStream() } }

    /** 停任务、切输入、恢复任务由同一个操作持有，避免旧帧或迟到回调接管新视频。 */
    override suspend fun replaceExternalVideoStream(
        videoFormat: RealtimeVideoFormat?,
        targetSource: suspend () -> RealtimeExternalVideoSource,
    ): RealtimeStreamReplacement {
        // 保留调用方的调度上下文；回调的取消由本次 SDK 切流操作统一管理。
        val preparationContext = currentCoroutineContext().minusKey(Job)

        return execute(OperationKind.REPLACEMENT) { token, c ->
            // 校验当前输入，并为本次操作保留生成条件，供取消接管或重连后恢复。
            if (!c.media.isExternalVideo) {
                throw invalid("The current local media source is not an external video")
            }

            token.inheritGenerationContext(c.generation.cachedContext)
            token.setFailureScope(TerminationScope.CONNECTION)

            // 停止旧生成任务，暂时保留房间连接；是否需要重连由新视频的规格决定。
            c.generation.stop(currentState.taskId.orEmpty())

            token.commit(
                RealtimeState(
                    if (c.connection.currentSessionId.isNotEmpty()) {
                        RealtimeConnectionState.CONNECTED
                    } else {
                        RealtimeConnectionState.READY
                    },
                    sessionId = c.connection.currentSessionId.takeIf(String::isNotEmpty),
                )
            )

            // 媒体层先解除旧帧输入，再回调 App 切换播放器，最后接入返回的视频源。
            val local = c.media.replaceExternalVideoStream(videoFormat) {
                withContext(preparationContext) {
                    targetSource()
                }.also {
                    token.ensureCurrent()
                }
            }

            token.ensureCurrent()

            // 音视频规格兼容时复用房间；规格变化时断开旧连接，保留引擎和生成条件。
            val format = checkNotNull(local.videoTrack?.videoFormat)

            if (
                c.connection.currentSessionId.isNotEmpty() &&
                !c.connection.acceptsInput(format, c.media.hasAudio)
            ) {
                c.connection.disconnect()
                token.ensureCurrent()
                token.commit(RealtimeState(RealtimeConnectionState.READY))
            }

            // 切换前仅预览时，返回新的本地流，不启动生成。
            if (!token.resumeGeneration) {
                return@execute RealtimeStreamReplacement(local, null)
            }

            // 恢复已有或正在启动的生成请求，沿用参考图和提示词创建新任务。
            val remote = c.connection.currentRemoteStream
                ?: connect(token, c, local, resetGeneration = false)

            timing.measure {
                start(token, c, token.requestedContext)
            }

            RealtimeStreamReplacement(local, remote)
        }
    }

    override suspend fun stopLocalCameraStream() { mediaOperation { it.stopLocalCameraStream() } }
    override suspend fun stopLocalImageStream() { mediaOperation { it.stopLocalImageStream() } }
    override suspend fun stopLocalVideoStream() { mediaOperation { it.stopLocalVideoStream() } }

    /** 本地媒体变更要求已断连；准备成功或开始停止资源后，故障按 ALL 范围清理。 */
    private suspend fun <T> mediaOperation(
        sourceRemoteAudioVolume: Float? = null,
        waitForCameraPreview: Boolean = false,
        action: suspend (MediaControlling) -> T,
    ): T =
        execute(OperationKind.MEDIA) { token, c ->
            requireDisconnected(c)
            if (sourceRemoteAudioVolume != null) {
                token.commit(RealtimeState(RealtimeConnectionState.PREPARING))
            } else {
                token.setFailureScope(TerminationScope.ALL)
            }
            val result = action(c.media)
            token.ensureCurrent()
            token.setFailureScope(TerminationScope.ALL)
            sourceRemoteAudioVolume?.let { volume ->
                c.stream.setRemoteAudioVolume(volume)
                configuredRemoteAudioVolume = volume
            }
            if (!waitForCameraPreview) {
                token.commit(
                    RealtimeState(
                        if (c.media.currentTrack == null) {
                            RealtimeConnectionState.IDLE
                        } else {
                            RealtimeConnectionState.READY
                        },
                    ),
                )
            }
            result
        }

    /** 生成中切换时保留连接，结束旧任务后等待相机稳定，再以缓存条件创建新任务。 */
    override suspend fun switchCamera(): RealtimeMediaStream =
        execute(OperationKind.SWITCH) { token, c ->
            val wasGenerating = currentState.connectionState == RealtimeConnectionState.GENERATING
            if (wasGenerating) {
                token.setFailureScope(TerminationScope.CONNECTION)
                c.generation.stop(currentState.taskId.orEmpty())
                token.commit(currentState.copy(connectionState = RealtimeConnectionState.CONNECTED, taskId = null))
            }
            val stream = c.media.switchCamera()
            token.ensureCurrent()
            if (wasGenerating) {
                delay(500L)
                timing.measure { start(token, c, null) }
            }
            stream
        }

    override suspend fun connect(localStream: RealtimeMediaStream): RealtimeMediaStream =
        execute(OperationKind.CONNECTION) { token, c -> connect(token, c, localStream) }

    /** 验证本地流归属并建立会话；仅当前操作可提交 CONNECTED，连接完成不代表生成已开始。 */
    private suspend fun connect(
        token: RealtimeCoordinator.Token,
        c: RealtimeComponents,
        localStream: RealtimeMediaStream,
        resetGeneration: Boolean = true,
    ): RealtimeMediaStream {
        requireDisconnected(c)
        val videoFormat = localStream.videoTrack?.videoFormat
        if (videoFormat == null || !c.media.owns(localStream)) throw invalid("The local stream must be created and started by this realtime manager")
        token.setFailureScope(TerminationScope.CONNECTION)
        token.commit(RealtimeState(RealtimeConnectionState.CONNECTING))
        try {
            if (resetGeneration) c.generation.reset()
            c.stream.setVideoEncoderConfig(videoFormat)
            c.media.startMicrophoneCapture()
            token.ensureCurrent()
            val owner = runtime
            val remote = c.connection.connect(options.model, videoFormat, c.media.hasAudio,
                isCurrent = { try { token.ensureCurrent(); true } catch (_: CancellationException) { false } },
                onHeartbeatFailure = { sessionId, error ->
                    // 同时校验组件代际与会话，避免旧心跳结束一个后续建立的连接。
                    if (runtime === owner && c.connection.currentSessionId == sessionId) {
                        coordinator.reportFailure(error, TerminationScope.CONNECTION)
                    }
                },
            )
            currentCoroutineContext().ensureActive()
            token.commit(RealtimeState(RealtimeConnectionState.CONNECTED, sessionId = c.connection.currentSessionId))
            return remote
        } catch (error: Throwable) {
            cleanupAfterFailure(
                error,
                { c.connection.disconnect() },
                { c.media.stopMicrophoneCapture() },
            )
            throw error
        }
    }

    override suspend fun startGeneration(context: RealtimeContext?) {
        execute(OperationKind.GENERATION, context) { token, c ->
            measureStartup { start(token, c, context) }
        }
    }
    override suspend fun startGeneration(localStream: RealtimeMediaStream, context: RealtimeContext?): RealtimeMediaStream =
        execute(OperationKind.GENERATION, context) { token, c ->
            measureStartup {
                if (!c.media.owns(localStream)) throw invalid("The local stream must be created and started by this realtime manager")
                val remote = if (c.connection.currentSessionId.isNotEmpty()) {
                    c.connection.currentRemoteStream ?: throw XmaxError(XmaxErrorCode.RTC_ERROR, "Realtime connection has no remote stream")
                } else {
                    try {
                        // 文件视频继续推送媒体帧，但从用户开始生成起就停止本地出声。
                        c.media.setLocalAudioPreviewMuted(true)
                        connect(token, c, localStream)
                    } catch (error: Throwable) {
                        cleanupAfterFailure(error, { c.media.setLocalAudioPreviewMuted(false) })
                        throw error
                    }
                }
                start(token, c, context)
                remote
            }
        }

    private suspend fun <T> measureStartup(action: suspend () -> T): T =
        if (currentState.connectionState == RealtimeConnectionState.GENERATING) action() else timing.measure(action)

    /**
     * 已生成时只更新当前任务；新任务按远端确认、有效视频帧、启用远端音频的顺序启动。
     * GENERATING 仅在整个启动条件满足后提交；失败时停止任务并恢复本地预览音频。
     */
    private suspend fun start(token: RealtimeCoordinator.Token, c: RealtimeComponents, context: RealtimeContext?) {
        val current = currentState
        val format = c.media.currentVideoFormat
        if (c.connection.currentSessionId.isEmpty() || format == null ||
            current.connectionState !in setOf(RealtimeConnectionState.CONNECTED, RealtimeConnectionState.GENERATING)) {
            throw invalid("Realtime connection is not open")
        }
        token.commit(current)
        if (current.connectionState == RealtimeConnectionState.GENERATING && current.taskId != null) {
            c.generation.update(current.taskId, format, context, token::ensureCurrent)
            return
        }
        c.generation.validateContext(context)
        token.setFailureScope(TerminationScope.CONNECTION)
        var taskId = ""
        try {
            c.media.setLocalAudioPreviewMuted(true)
            taskId = c.generation.start(format, context, token::ensureCurrent)
            c.render.waitUntilRemoteFrameReady()
            currentCoroutineContext().ensureActive()
            token.ensureCurrent()
            c.stream.activateRemoteAudio()
            token.commit(current.copy(connectionState = RealtimeConnectionState.GENERATING, taskId = taskId))
            currentCoroutineContext()[RealtimeTiming.Attempt]?.finish(taskId)
        } catch (error: Throwable) {
            cleanupAfterFailure(error,
                { c.generation.stop(taskId) },
                { c.media.setLocalAudioPreviewMuted(false) },
            )
            throw error
        }
    }

    override suspend fun disconnect() { coordinator.disconnect() }
    override suspend fun disconnect(reason: RealtimeReason) { coordinator.disconnect(reason) }
    override suspend fun close() {
        coordinator.terminate(TerminationScope.ALL)
    }

    /**
     * 由协调器独占执行，按 GENERATION、CONNECTION、ALL 逐层扩大资源释放范围。
     * 各步骤独立执行以保留清理异常；ALL 最后解除 Runtime 引用，后续操作重新装配组件。
     */
    private suspend fun cleanup(target: TerminationScope) {
        val owner = runtime ?: return
        val c = owner.components
        cleanupResources(
            { if (target >= TerminationScope.CONNECTION) c.generation.reset(currentState.taskId.orEmpty()) else c.generation.stop(currentState.taskId.orEmpty()) },
            { if (target >= TerminationScope.CONNECTION) c.media.stopMicrophoneCapture() },
            { if (target >= TerminationScope.CONNECTION) c.connection.disconnect() },
            { c.media.setLocalAudioPreviewMuted(false) },
            { if (target == TerminationScope.ALL) {
                cleanupResources(
                    { c.media.stopLocalStream() },
                    { c.media.setCameraPreviewReadyListener(null) },
                    { c.stream.setNetworkQualityListener(null) },
                    { c.stream.setPerformanceAlarmListener(null) },
                )
            } },
            { if (target == TerminationScope.ALL) runtime = null },
        )
    }

    /** 统一操作准入与错误归一化；故障是否清理由操作凭证中的范围决定。 */
    private suspend fun <T> execute(
        kind: OperationKind,
        context: RealtimeContext? = null,
        action: suspend (RealtimeCoordinator.Token, RealtimeComponents) -> T,
    ): T = coordinator.run(kind, failureScope = null, context = context) { token ->
        try { action(token, components()) }
        catch (error: Throwable) {
            currentCoroutineContext().ensureActive()
            token.ensureCurrent()
            val resolved = XmaxError.from(error)
            XmaxLogger.realtime.warn(
                message = {
                    "Realtime ${kind.name.lowercase()} failed: " +
                        ErrorMessageFormatter.format(resolved)
                },
            )
            throw resolved
        }
    }

    /** 仅从协调器的执行门内调用；故障回调捕获创建它的 Runtime 身份。 */
    private suspend fun components(): RealtimeComponents {
        val owner = runtime ?: Runtime().also { created ->
            created.components = componentFactory(
                { error -> forwardFailure(created, error, TerminationScope.CONNECTION) },
                { error -> forwardFailure(created, error, TerminationScope.ALL) },
                callbacks.remoteVideoFrames,
            )
            runtime = created
        }
        if (!owner.audioSettingsApplied) {
            // 在创建、启动媒体之前恢复设置；失败或取消时保持未完成，后续操作重新尝试。
            configuredLocalAudioVolume?.let { owner.components.media.setLocalAudioVolume(it) }
            configuredRemoteAudioVolume?.let { owner.components.stream.setRemoteAudioVolume(it) }
            owner.audioSettingsApplied = true
        }
        if (!owner.listenersApplied) {
            owner.components.media.setCameraPreviewReadyListener {
                if (runtime === owner) {
                    coordinator.localPreviewDidBecomeReady()
                }
            }
            owner.components.stream.setNetworkQualityListener(networkQualityListener)
            owner.components.stream.setPerformanceAlarmListener(performanceAlarmListener)
            owner.listenersApplied = true
        }
        return owner.components
    }
    /** 丢弃旧运行时故障；故障来源决定清理范围，取消事件仅保留诊断日志。 */
    private fun forwardFailure(owner: Runtime, error: XmaxError, target: TerminationScope) {
        if (runtime !== owner) return
        if (error.code != XmaxErrorCode.CANCELLED) {
            coordinator.reportFailure(error, target)
        } else {
            XmaxLogger.realtime.warn(
                message = { "Realtime diagnostic: ${ErrorMessageFormatter.format(error)}" },
            )
        }
    }
    /** 同时检查实际会话资源和公开状态，避免 ERROR 状态下仍持有连接时更换媒体源。 */
    private fun requireDisconnected(c: RealtimeComponents) {
        if (c.connection.currentSessionId.isNotEmpty() || currentState.connectionState in setOf(
                RealtimeConnectionState.CONNECTING, RealtimeConnectionState.CONNECTED,
                RealtimeConnectionState.GENERATING, RealtimeConnectionState.DISCONNECTING,
            )) throw invalid("Disconnect realtime before changing the local stream or opening another connection")
    }
    private fun validateAudioVolume(volume: Float) {
        if (!volume.isFinite() || volume !in 0f..1f) throw invalid("Audio volume must be between 0 and 1")
    }
    private fun invalid(message: String) = XmaxError(XmaxErrorCode.INVALID_CONFIGURATION, message)
    private class Runtime {
        lateinit var components: RealtimeComponents
        var audioSettingsApplied = false
        var listenersApplied = false
    }

}
