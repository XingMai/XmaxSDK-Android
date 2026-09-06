package ai.xmax.sdk

import ai.xmax.sdk.media.MediaControlling
import ai.xmax.sdk.service.network.ApiServicing
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.delay

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import ai.xmax.sdk.RealtimeCoordinator.OperationKind
import ai.xmax.sdk.RealtimeCoordinator.TerminationScope

/**
 * 实时公共接口的业务编排层，连接媒体源、服务端会话、生成任务和远端呈现。
 * 生命周期状态及操作所有权由 RealtimeCoordinator 管理，用户通知交给 RealtimeCallbacks。
 * 组件按需创建；每代 Runtime 独立标识错误来源，防止已释放组件的迟到回调影响新连接。
 */
internal class XmaxRealtimeManager(
    override val options: RealtimeConfiguration,
    private val componentFactory: ((XmaxError) -> Unit, (XmaxError) -> Unit) -> RealtimeComponents,
    private val callbacks: RealtimeCallbacks = RealtimeCallbacks(),
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : XmaxRealtimeManaging {
    constructor(options: RealtimeConfiguration, context: Context, apiService: ApiServicing) :
        this(options, { onError, onMediaError -> createRealtimeComponents(context, apiService, onError, onMediaError) })

    /** 后台回调也会读取运行时身份；创建与释放由协调器串行执行。 */
    @Volatile private var runtime: Runtime? = null
    private val coordinator = RealtimeCoordinator(callbacks, dispatcher, ::cleanup)
    override val currentState: RealtimeState get() = coordinator.currentState

    override suspend fun setStateListener(listener: RealtimeStateListener?) {
        callbacks.setStateListener(listener, currentState)
    }
    override suspend fun setErrorListener(listener: RealtimeErrorListener?) {
        callbacks.setErrorListener(listener)
    }
    override suspend fun setCameraPreviewReadyListener(listener: RealtimeCameraPreviewReadyListener?) {
        execute(OperationKind.SETTING) { _, c -> c.media.setCameraPreviewReadyListener(listener) }
    }
    override suspend fun setNetworkQualityListener(listener: RealtimeNetworkQualityListener?) {
        execute(OperationKind.SETTING) { _, c -> c.stream.setNetworkQualityListener(listener) }
    }
    override suspend fun setPerformanceAlarmListener(listener: RealtimePerformanceAlarmListener?) {
        execute(OperationKind.SETTING) { _, c -> c.stream.setPerformanceAlarmListener(listener) }
    }
    override suspend fun setLocalAudioVolume(volume: Float) {
        execute(OperationKind.SETTING) { _, c -> validateAudioVolume(volume); c.media.setLocalAudioVolume(volume) }
    }
    override suspend fun setRemoteAudioVolume(volume: Float) {
        execute(OperationKind.SETTING) { _, c -> validateAudioVolume(volume); c.stream.setRemoteAudioVolume(volume) }
    }

    override suspend fun createLocalCameraStream(videoFormat: RealtimeVideoFormat, position: CameraPosition): RealtimeMediaStream =
        mediaOperation { it.createLocalCameraStream(videoFormat, position) }
    override suspend fun createLocalImageStream(imageData: ByteArray, videoFormat: RealtimeVideoFormat?): RealtimeMediaStream =
        mediaOperation { it.createLocalImageStream(imageData, videoFormat) }
    override suspend fun createLocalImageStream(bitmap: Bitmap, videoFormat: RealtimeVideoFormat?): RealtimeMediaStream =
        mediaOperation { it.createLocalImageStream(bitmap, videoFormat) }
    override suspend fun createLocalImageStream(uri: Uri, videoFormat: RealtimeVideoFormat?): RealtimeMediaStream =
        mediaOperation { it.createLocalImageStream(uri, videoFormat) }
    override suspend fun createLocalVideoStream(uri: Uri, videoFormat: RealtimeVideoFormat?): RealtimeMediaStream =
        mediaOperation { it.createLocalVideoStream(uri, videoFormat) }
    override suspend fun stopLocalCameraStream() { mediaOperation { it.stopLocalCameraStream() } }
    override suspend fun stopLocalImageStream() { mediaOperation { it.stopLocalImageStream() } }
    override suspend fun stopLocalVideoStream() { mediaOperation { it.stopLocalVideoStream() } }

    /** 本地媒体变更要求已断连；创建或释放失败涉及媒体所有权，致命故障按 ALL 范围清理。 */
    private suspend fun <T> mediaOperation(action: suspend (MediaControlling) -> T): T =
        execute(OperationKind.MEDIA, TerminationScope.ALL) { token, c ->
            requireDisconnected(c)
            action(c.media).also {
                if (currentState.connectionState == RealtimeConnectionState.ERROR) token.commit(RealtimeState(RealtimeConnectionState.IDLE))
            }
        }

    /** 生成中切换时保留连接，结束旧任务后等待相机稳定，再以缓存条件创建新任务。 */
    override suspend fun switchCamera(): RealtimeMediaStream =
        execute(OperationKind.SWITCH, TerminationScope.CONNECTION) { token, c ->
            val wasGenerating = currentState.connectionState == RealtimeConnectionState.GENERATING
            if (wasGenerating) {
                c.generation.stop(currentState.taskId.orEmpty())
                token.commit(currentState.copy(connectionState = RealtimeConnectionState.CONNECTED, taskId = null))
            }
            val stream = c.media.switchCamera()
            token.ensureCurrent()
            if (wasGenerating) {
                delay(500L)
                start(token, c, null)
            }
            stream
        }

    override suspend fun connect(localStream: RealtimeMediaStream): RealtimeMediaStream =
        execute(OperationKind.CONNECTION, TerminationScope.CONNECTION) { token, c -> connect(token, c, localStream) }

    /** 验证本地流归属并建立会话；仅当前操作可提交 CONNECTED，连接完成不代表生成已开始。 */
    private suspend fun connect(token: RealtimeCoordinator.Token, c: RealtimeComponents, localStream: RealtimeMediaStream): RealtimeMediaStream {
        requireDisconnected(c)
        val videoFormat = localStream.videoTrack?.videoFormat
        if (videoFormat == null || !c.media.owns(localStream)) throw invalid("The local stream must be created and started by this realtime manager")
        token.commit(RealtimeState(RealtimeConnectionState.CONNECTING))
        try {
            c.generation.reset()
            c.stream.setVideoEncoderConfig(videoFormat)
            val owner = runtime
            val remote = c.connection.connect(options.model, videoFormat, c.media.hasAudio,
                isCurrent = { try { token.ensureCurrent(); true } catch (_: CancellationException) { false } },
                onHeartbeatFailure = { sessionId, error ->
                    // 同时校验组件代际与会话，避免旧心跳结束一个后续建立的连接。
                    if (runtime === owner && c.connection.currentSessionId == sessionId) {
                        coordinator.fatal(error.withSeverity(XmaxErrorSeverity.FATAL), TerminationScope.CONNECTION)
                    }
                },
            )
            currentCoroutineContext().ensureActive()
            token.commit(RealtimeState(RealtimeConnectionState.CONNECTED, sessionId = c.connection.currentSessionId))
            return remote
        } catch (error: Throwable) {
            cleanupAfterFailure(error, { c.connection.disconnect() })
            runCatching { token.commit(RealtimeState(RealtimeConnectionState.DISCONNECTED)) }
            throw error
        }
    }

    override suspend fun startGeneration(context: RealtimeContext?) {
        execute(OperationKind.GENERATION, TerminationScope.GENERATION) { token, c -> start(token, c, context) }
    }
    override suspend fun startGeneration(localStream: RealtimeMediaStream, context: RealtimeContext?): RealtimeMediaStream =
        execute(OperationKind.GENERATION, TerminationScope.GENERATION) { token, c ->
            if (!c.media.owns(localStream)) throw invalid("The local stream must be created and started by this realtime manager")
            val remote = if (c.connection.currentSessionId.isNotEmpty()) {
                c.connection.currentRemoteStream ?: throw XmaxError(XmaxErrorCode.RTC_ERROR, "Realtime connection has no remote stream")
            } else connect(token, c, localStream)
            start(token, c, context)
            remote
        }

    /**
     * 已生成时只更新当前任务；新任务按远端确认、有效视频帧、启用远端音频的顺序启动。
     * GENERATING 仅在整个启动条件满足后提交；失败时停止任务并恢复本地预览音频。
     */
    private suspend fun start(token: RealtimeCoordinator.Token, c: RealtimeComponents, context: RealtimeContext?) {
        val current = currentState.let {
            if (it.connectionState == RealtimeConnectionState.ERROR && c.connection.currentSessionId.isNotEmpty()) {
                it.copy(connectionState = RealtimeConnectionState.CONNECTED, sessionId = c.connection.currentSessionId, taskId = null)
            } else it
        }
        val format = c.media.currentVideoFormat
        if (c.connection.currentSessionId.isEmpty() || format == null ||
            current.connectionState !in setOf(RealtimeConnectionState.CONNECTED, RealtimeConnectionState.GENERATING)) {
            throw invalid("Realtime connection is not open")
        }
        token.commit(current)
        if (current.connectionState == RealtimeConnectionState.GENERATING && current.taskId != null) {
            try { c.generation.update(current.taskId, format, context) }
            catch (error: Throwable) { throw XmaxError.from(error).withSeverity(XmaxErrorSeverity.RECOVERABLE) }
            return
        }
        var taskId = ""
        try {
            c.media.setLocalAudioPreviewMuted(true)
            taskId = c.generation.start(format, context, token::ensureCurrent)
            c.render.waitUntilRemoteFrameReady()
            currentCoroutineContext().ensureActive()
            token.ensureCurrent()
            c.stream.activateRemoteAudio()
            token.commit(current.copy(connectionState = RealtimeConnectionState.GENERATING, taskId = taskId))
        } catch (error: Throwable) {
            cleanupAfterFailure(error,
                { c.generation.stop(taskId) },
                { c.media.setLocalAudioPreviewMuted(false) },
            )
            throw error
        }
    }

    override suspend fun stopGeneration() { coordinator.terminate(TerminationScope.GENERATION) }
    override suspend fun disconnect() { coordinator.terminate(TerminationScope.CONNECTION) }
    override suspend fun close() { coordinator.terminate(TerminationScope.ALL, clearListeners = true) }

    /**
     * 由协调器独占执行，按 GENERATION、CONNECTION、ALL 逐层扩大资源释放范围。
     * 各步骤独立执行以保留清理异常；ALL 最后解除 Runtime 引用，后续操作重新装配组件。
     */
    private suspend fun cleanup(target: TerminationScope) {
        val owner = runtime ?: return
        val c = owner.components
        cleanupResources(
            { if (target >= TerminationScope.CONNECTION) c.generation.reset(currentState.taskId.orEmpty()) else c.generation.stop(currentState.taskId.orEmpty()) },
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

    /**
     * 统一操作准入与错误归一化。无致命清理范围的设置错误按可恢复处理。
     * 协程取消直接传播；致命错误交协调器终止，并在本地清理后通过统一监听器通知。
     */
    private suspend fun <T> execute(
        kind: OperationKind,
        fatalTarget: TerminationScope? = null,
        action: suspend (RealtimeCoordinator.Token, RealtimeComponents) -> T,
    ): T = coordinator.run(kind) { token ->
        try { action(token, components()) }
        catch (error: Throwable) {
            currentCoroutineContext().ensureActive()
            val resolved = XmaxError.from(error).let {
                if (fatalTarget == null) it.withSeverity(XmaxErrorSeverity.RECOVERABLE) else it
            }
            XmaxLogger.warn({ "Realtime ${kind.name.lowercase()} failed: ${ErrorMessageFormatter.format(resolved)}" }, "Realtime")
            if (resolved.severity == XmaxErrorSeverity.FATAL) token.fail(resolved, fatalTarget ?: TerminationScope.ALL)
            throw resolved
        }
    }

    /** 仅从协调器的执行门内调用；故障回调捕获创建它的 Runtime 身份。 */
    private fun components(): RealtimeComponents {
        runtime?.let { return it.components }
        val owner = Runtime()
        owner.components = componentFactory(
            { error -> forwardFailure(owner, error, TerminationScope.CONNECTION) },
            { error -> forwardFailure(owner, error, TerminationScope.ALL) },
        )
        runtime = owner
        return owner.components
    }
    /** 丢弃旧运行时故障；当前致命故障触发清理，可恢复事件仅保留诊断日志。 */
    private fun forwardFailure(owner: Runtime, error: XmaxError, target: TerminationScope) {
        if (runtime !== owner) return
        if (error.severity == XmaxErrorSeverity.FATAL && error.code != XmaxErrorCode.CANCELLED) {
            coordinator.fatal(error, target)
        } else {
            XmaxLogger.warn({ "Realtime diagnostic: ${ErrorMessageFormatter.format(error)}" }, "Realtime")
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
    private class Runtime { lateinit var components: RealtimeComponents }

}
