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

/** Public facade; lifecycle state and operation ownership live in the coordinator. */
internal class XmaxRealtimeManager(
    override val options: RealtimeConfiguration,
    private val componentFactory: ((XmaxError) -> Unit, (XmaxError) -> Unit) -> RealtimeComponents,
    private val callbacks: RealtimeCallbacks = RealtimeCallbacks(),
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : XmaxRealtimeManaging {
    constructor(options: RealtimeConfiguration, context: Context, apiService: ApiServicing) :
        this(options, { onError, onMediaError -> createRealtimeComponents(context, apiService, onError, onMediaError) })

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

    private suspend fun <T> mediaOperation(action: suspend (MediaControlling) -> T): T =
        execute(OperationKind.MEDIA, TerminationScope.ALL) { token, c ->
            requireDisconnected(c)
            action(c.media).also {
                if (currentState.connectionState == RealtimeConnectionState.ERROR) token.commit(RealtimeState(RealtimeConnectionState.IDLE))
            }
        }

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
    private fun forwardFailure(owner: Runtime, error: XmaxError, target: TerminationScope) {
        if (runtime !== owner) return
        if (error.severity == XmaxErrorSeverity.FATAL && error.code != XmaxErrorCode.CANCELLED) {
            coordinator.fatal(error, target)
        } else {
            XmaxLogger.warn({ "Realtime diagnostic: ${ErrorMessageFormatter.format(error)}" }, "Realtime")
        }
    }
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
