package ai.xmax.sdk

import ai.xmax.sdk.foundation.rtc.RtcManager
import ai.xmax.sdk.media.MediaController
import ai.xmax.sdk.media.MediaControlling
import ai.xmax.sdk.media.camera.CameraController
import ai.xmax.sdk.media.interaction.InteractionController
import ai.xmax.sdk.rendering.RenderController
import ai.xmax.sdk.service.network.ApiServicing
import ai.xmax.sdk.service.realtime.RealtimeSessionService
import ai.xmax.sdk.stream.StreamController
import ai.xmax.sdk.stream.StreamControlling
import android.content.Context
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 实时生成业务公共入口，统一编排摄像头、连接、生成和状态通知。 */
internal class XmaxRealtimeManager(
    override val options: RealtimeConfiguration,
    context: Context,
    apiService: ApiServicing,
) : XmaxRealtimeManaging {
    private val stateLock = Any()
    private val operationVersion = AtomicLong(0L)
    private val terminationMutex = Mutex()
    private var state = RealtimeState(RealtimeConnectionState.IDLE)
    private var stateListener: RealtimeStateListener? = null
    private var errorListener: RealtimeErrorListener? = null

    private val rtcManager = RtcManager(context)
    private val renderController = RenderController(rtcManager)
    private val streamController: StreamControlling = StreamController(
        rtcManager = rtcManager,
        errorListener = ::forwardError,
        remoteStreamListener = renderController::setRemoteStream,
    )
    private val mediaController: MediaControlling = MediaController(
        rtcManager = rtcManager,
        cameraController = CameraController(
            context = context,
            rtcManager = rtcManager,
            errorListener = ::forwardError,
        ),
        interactionController = InteractionController(
            listener = { taskId, points ->
                streamController.sendTracks(taskId, points)
            },
        ),
    )
    private val connectionManager = XmaxRealtimeConnectionManager(
        sessionService = RealtimeSessionService(apiService),
        interactionController = mediaController,
        renderController = renderController,
        streamController = streamController,
    )
    private val generationManager = XmaxRealtimeGenerationManager(
        interactionController = mediaController,
        streamController = streamController,
    )

    override val currentState: RealtimeState
        get() = synchronized(stateLock) { state }

    override suspend fun setStateListener(listener: RealtimeStateListener?) {
        val current = synchronized(stateLock) {
            stateListener = listener
            state
        }
        listener?.onStateChanged(current)
    }

    override suspend fun setErrorListener(listener: RealtimeErrorListener?) {
        synchronized(stateLock) { errorListener = listener }
    }

    override suspend fun setCameraPreviewReadyListener(
        listener: RealtimeCameraPreviewReadyListener?,
    ) {
        mediaController.setCameraPreviewReadyListener(listener)
    }

    override suspend fun setNetworkQualityListener(listener: RealtimeNetworkQualityListener?) {
        streamController.setNetworkQualityListener(listener)
    }

    override suspend fun setPerformanceAlarmListener(listener: RealtimePerformanceAlarmListener?) {
        streamController.setPerformanceAlarmListener(listener)
    }

    override suspend fun setRemoteAudioVolume(volume: Float) {
        try {
            validateAudioVolume(volume)
            streamController.setRemoteAudioVolume(volume)
        } catch (error: Throwable) {
            throw reportError(error)
        }
    }

    override suspend fun createLocalCameraStream(
        videoFormat: RealtimeVideoFormat,
        position: CameraPosition,
    ): RealtimeMediaStream {
        ensureLocalMediaCanChange("Local camera stream is unavailable during a realtime connection")
        return try {
            mediaController.createLocalCameraStream(videoFormat, position)
        } catch (error: Throwable) {
            throw reportError(error)
        }
    }

    override suspend fun stopLocalCameraStream() {
        ensureLocalMediaCanChange("Disconnect realtime before stopping the local camera stream")
        mediaController.stopLocalCameraStream()
    }

    override suspend fun switchCamera(): RealtimeMediaStream {
        val current = currentState.connectionState
        if (current == RealtimeConnectionState.CONNECTING ||
            current == RealtimeConnectionState.DISCONNECTING
        ) {
            throw reportError(
                XmaxError(
                    XmaxErrorCode.INVALID_CONFIGURATION,
                    "Camera switching is unavailable while realtime is transitioning",
                ),
            )
        }
        val wasGenerating = current == RealtimeConnectionState.GENERATING
        if (!wasGenerating && streamController.hasGenerationTask) {
            throw reportError(
                XmaxError(
                    XmaxErrorCode.INVALID_CONFIGURATION,
                    "Camera switching is unavailable while realtime generation is starting",
                ),
            )
        }
        if (wasGenerating) stopGeneration()
        val stream = try {
            mediaController.switchCamera()
        } catch (error: Throwable) {
            throw reportError(error)
        }
        if (wasGenerating) {
            delay(500L)
            performStartGeneration(null)
        }
        return stream
    }

    override suspend fun connect(localStream: RealtimeMediaStream): RealtimeMediaStream {
        if (!canBeginConnecting()) {
            throw connectionAlreadyOpenError()
        }
        val videoFormat = localStream.videoTrack?.videoFormat
        if (videoFormat == null || !mediaController.owns(localStream)) {
            throw reportError(
                XmaxError(
                    XmaxErrorCode.INVALID_CONFIGURATION,
                    "The local stream must be created and started by this realtime manager",
                ),
            )
        }

        generationManager.reset()
        val version = beginConnecting()
        try {
            ensureOperation(version)
            streamController.setVideoEncoderConfig(videoFormat)
            val remoteStream = connectionManager.connect(
                model = options.model,
                videoFormat = videoFormat,
                includeLocalAudio = mediaController.hasAudio,
                isCurrent = { operationVersion.get() == version },
                onHeartbeatFailure = ::handleHeartbeatFailure,
            )
            ensureOperation(version)
            val sessionId = connectionManager.currentSessionId
            if (sessionId.isEmpty()) throw cancelledError()
            emitState(
                RealtimeState(
                    connectionState = RealtimeConnectionState.CONNECTED,
                    sessionId = sessionId,
                ),
            )
            return remoteStream
        } catch (error: Throwable) {
            if (operationVersion.get() != version) throw cancelledError()
            val xmaxError = reportError(error)
            emitState(RealtimeState(RealtimeConnectionState.ERROR))
            throw xmaxError
        }
    }

    override suspend fun disconnect() {
        terminationMutex.withLock {
            val current = currentState.connectionState
            if (current == RealtimeConnectionState.IDLE ||
                current == RealtimeConnectionState.DISCONNECTED
            ) {
                return@withLock
            }
            terminate(RealtimeConnectionState.DISCONNECTED)
        }
    }

    private suspend fun terminate(finalState: RealtimeConnectionState) {
        operationVersion.incrementAndGet()
        emitState(RealtimeState(RealtimeConnectionState.DISCONNECTING))
        val previousSessionId = connectionManager.currentSessionId.takeIf(String::isNotEmpty)
        runCatching { generationManager.reset(currentState.taskId.orEmpty()) }
            .onFailure { reportError(it) }
        val closedSessionId = try {
            connectionManager.disconnect()
        } catch (error: Throwable) {
            reportError(error)
            previousSessionId
        }
        emitState(
            RealtimeState(
                connectionState = finalState,
                sessionId = closedSessionId,
            ),
        )
    }

    override suspend fun startGeneration(context: RealtimeContext?) {
        performStartGeneration(context)
    }

    override suspend fun startGeneration(
        localStream: RealtimeMediaStream,
        context: RealtimeContext?,
    ): RealtimeMediaStream {
        if (!mediaController.owns(localStream)) {
            throw reportError(
                XmaxError(
                    XmaxErrorCode.INVALID_CONFIGURATION,
                    "The local stream must be created and started by this realtime manager",
                ),
            )
        }
        val remoteStream = if (
            currentState.connectionState == RealtimeConnectionState.CONNECTED ||
            currentState.connectionState == RealtimeConnectionState.GENERATING
        ) {
            connectionManager.currentRemoteStream ?: throw reportError(
                XmaxError(XmaxErrorCode.RTC_ERROR, "Realtime connection has no remote stream"),
            )
        } else {
            connect(localStream)
        }
        performStartGeneration(context)
        return remoteStream
    }

    override suspend fun stopGeneration() {
        val sessionId = connectionManager.currentSessionId
        val current = currentState
        if (sessionId.isEmpty() ||
            (current.connectionState != RealtimeConnectionState.CONNECTED &&
                current.connectionState != RealtimeConnectionState.GENERATING)
        ) {
            return
        }
        runCatching { generationManager.stop(current.taskId.orEmpty()) }
            .onFailure { reportError(it) }
        if (current.connectionState == RealtimeConnectionState.GENERATING) {
            emitState(
                RealtimeState(
                    connectionState = RealtimeConnectionState.CONNECTED,
                    sessionId = sessionId,
                ),
            )
        }
    }

    override suspend fun close() {
        disconnect()
        mediaController.stopLocalStream()
        setCameraPreviewReadyListener(null)
        setNetworkQualityListener(null)
        setPerformanceAlarmListener(null)
        setStateListener(null)
        setErrorListener(null)
    }

    private suspend fun performStartGeneration(context: RealtimeContext?) {
        val sessionId = connectionManager.currentSessionId
        val current = currentState
        val videoFormat = mediaController.currentVideoFormat
        if (sessionId.isEmpty() || videoFormat == null ||
            (current.connectionState != RealtimeConnectionState.CONNECTED &&
                current.connectionState != RealtimeConnectionState.GENERATING)
        ) {
            throw reportError(
                XmaxError(XmaxErrorCode.RTC_ERROR, "Realtime connection is not open"),
            )
        }
        if (current.connectionState == RealtimeConnectionState.GENERATING &&
            current.taskId != null
        ) {
            try {
                generationManager.update(current.taskId, videoFormat, context)
                return
            } catch (error: Throwable) {
                throw reportError(error)
            }
        }

        val version = operationVersion.get()
        var startedTaskId = ""
        try {
            startedTaskId = generationManager.start(videoFormat, context) {
                ensureOperation(version)
            }
            renderController.waitUntilRemoteFrameReady()
            streamController.activateRemoteAudio()
            ensureOperation(version)
            if (connectionManager.currentSessionId != sessionId) throw cancelledError()
            emitState(
                RealtimeState(
                    connectionState = RealtimeConnectionState.GENERATING,
                    sessionId = sessionId,
                    taskId = startedTaskId,
                ),
            )
        } catch (error: Throwable) {
            if (startedTaskId.isNotEmpty()) {
                runCatching { generationManager.stop(startedTaskId) }
            }
            throw reportError(error)
        }
    }

    private suspend fun handleHeartbeatFailure(sessionId: String, error: XmaxError) {
        if (connectionManager.currentSessionId != sessionId) return
        reportError(error)
        if (connectionManager.currentSessionId == sessionId) {
            terminationMutex.withLock {
                if (connectionManager.currentSessionId == sessionId) {
                    terminate(RealtimeConnectionState.ERROR)
                }
            }
        }
    }

    private fun ensureLocalMediaCanChange(message: String) {
        val current = currentState.connectionState
        if (connectionManager.currentSessionId.isNotEmpty() ||
            current == RealtimeConnectionState.CONNECTING ||
            current == RealtimeConnectionState.DISCONNECTING
        ) {
            throw reportError(XmaxError(XmaxErrorCode.INVALID_CONFIGURATION, message))
        }
    }

    private fun ensureOperation(version: Long) {
        if (operationVersion.get() != version) throw cancelledError()
    }

    private fun beginConnecting(): Long {
        var listener: RealtimeStateListener? = null
        val version = synchronized(stateLock) {
            val current = state.connectionState
            if (connectionManager.currentSessionId.isNotEmpty() ||
                current == RealtimeConnectionState.CONNECTING ||
                current == RealtimeConnectionState.CONNECTED ||
                current == RealtimeConnectionState.GENERATING ||
                current == RealtimeConnectionState.DISCONNECTING
            ) {
                0L
            } else {
                operationVersion.incrementAndGet().also {
                    state = RealtimeState(RealtimeConnectionState.CONNECTING)
                    listener = stateListener
                }
            }
        }
        if (version == 0L) {
            throw connectionAlreadyOpenError()
        }
        runCatching { listener?.onStateChanged(currentState) }
        return version
    }

    private fun canBeginConnecting(): Boolean = synchronized(stateLock) {
        val current = state.connectionState
        connectionManager.currentSessionId.isEmpty() &&
            current != RealtimeConnectionState.CONNECTING &&
            current != RealtimeConnectionState.CONNECTED &&
            current != RealtimeConnectionState.GENERATING &&
            current != RealtimeConnectionState.DISCONNECTING
    }

    private fun connectionAlreadyOpenError(): XmaxError = reportError(
        XmaxError(
            XmaxErrorCode.INVALID_CONFIGURATION,
            "Realtime connection is already open",
        ),
    )

    private fun emitState(newState: RealtimeState) {
        val listener = synchronized(stateLock) {
            state = newState
            stateListener
        }
        runCatching { listener?.onStateChanged(newState) }
    }

    private fun forwardError(error: XmaxError) {
        reportError(error)
    }

    private fun reportError(error: Throwable): XmaxError {
        val xmaxError = XmaxError.from(error)
        runCatching { synchronized(stateLock) { errorListener }?.onError(xmaxError) }
        return xmaxError
    }

    private fun validateAudioVolume(volume: Float) {
        if (!volume.isFinite() || volume !in 0f..1f) {
            throw XmaxError(
                XmaxErrorCode.INVALID_CONFIGURATION,
                "Audio volume must be between 0 and 1",
            )
        }
    }

    private fun cancelledError(): XmaxError = XmaxError(
        XmaxErrorCode.CANCELLED,
        "Realtime connection was cancelled",
    )
}
