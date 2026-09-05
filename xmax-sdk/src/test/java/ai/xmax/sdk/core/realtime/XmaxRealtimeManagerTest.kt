package ai.xmax.sdk

import ai.xmax.sdk.foundation.rtc.RemoteStream
import ai.xmax.sdk.media.MediaControlling
import ai.xmax.sdk.media.interaction.InteractionFrame
import ai.xmax.sdk.rendering.RenderControlling
import ai.xmax.sdk.service.realtime.*
import ai.xmax.sdk.stream.StreamControlling
import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class XmaxRealtimeManagerTest {
    @Test fun `close preempts session creation and old rollback cannot clear a new session`() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        val local = f.manager.createLocalCameraStream(format, CameraPosition.FRONT)
        val release = CompletableDeferred<Unit>()
        f.session.createBarrier = release
        val start = async { f.manager.connect(local) }
        runCurrent()
        val close = async { f.manager.close() }
        runCurrent()
        assertFalse(close.isCompleted)
        release.complete(Unit)
        close.await(); start.join()
        assertEquals(listOf("session-1"), f.session.closed)
        assertNull(f.media.currentTrack)
        assertTrue(f.errors.isEmpty())
        f.session.createBarrier = null
        val newLocal = f.manager.createLocalCameraStream(format, CameraPosition.FRONT)
        f.manager.connect(newLocal)
        assertEquals("session-2", f.manager.currentState.sessionId)
        runCurrent()
        assertEquals(RealtimeConnectionState.CONNECTED, f.manager.currentState.connectionState)
        f.manager.close()
    }

    @Test fun `stop while awaiting SEI retains connection and preview and permits restart`() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        val local = f.manager.createLocalCameraStream(format, CameraPosition.FRONT)
        f.manager.connect(local)
        val start = async { f.manager.startGeneration(RealtimeContext("first")) }
        runCurrent()
        f.manager.stopGeneration(); start.join()
        assertTrue(start.isCancelled)
        assertEquals(RealtimeConnectionState.CONNECTED, f.manager.currentState.connectionState)
        assertSame(local.videoTrack, f.media.currentTrack)
        assertFalse(f.media.muted)
        assertTrue(f.session.closed.isEmpty())
        assertTrue(f.errors.isEmpty())
        f.stream.confirmation = CompletableDeferred(Unit)
        f.manager.startGeneration(RealtimeContext("retry"))
        assertEquals(RealtimeConnectionState.GENERATING, f.manager.currentState.connectionState)
        f.manager.close()
    }

    @Test fun `terminal heartbeat failure runs cleanup outside failing job and reports once`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val f = Fixture(dispatcher)
        val local = f.manager.createLocalCameraStream(format, CameraPosition.FRONT)
        f.listen()
        f.manager.connect(local)
        val error = XmaxError(XmaxErrorCode.SESSION_ERROR, "expired")
        f.session.heartbeatJob = backgroundScope.launch(dispatcher) { f.session.failure!!("session-1", error) }
        runCurrent()
        assertEquals(listOf("session-1"), f.session.closed)
        assertEquals(listOf(error), f.errors)
        assertSame(local.videoTrack, f.media.currentTrack)
        assertEquals(RealtimeConnectionState.ERROR, f.manager.currentState.connectionState)
        f.session.failure!!("session-1", error)
        runCurrent()
        assertEquals(1, f.errors.size)
        f.manager.close()
    }

    @Test fun `connection failure throws and callbacks but update failure preserves generation`() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        val local = f.manager.createLocalCameraStream(format, CameraPosition.FRONT)
        f.listen()
        val fatal = XmaxError(XmaxErrorCode.NETWORK_ERROR, "offline")
        f.session.createError = fatal
        val thrown = runCatching { f.manager.connect(local) }.exceptionOrNull()
        runCurrent()
        assertSame(fatal, thrown)
        assertEquals(listOf(fatal), f.errors)
        f.session.createError = null
        f.stream.confirmation = CompletableDeferred(Unit)
        f.manager.startGeneration(local, RealtimeContext("start"))
        f.stream.updateError = XmaxError(XmaxErrorCode.RTC_ERROR, "condition rejected")
        val updateError = runCatching { f.manager.startGeneration(RealtimeContext("change")) }.exceptionOrNull() as XmaxError
        runCurrent()
        assertEquals(XmaxErrorSeverity.RECOVERABLE, updateError.severity)
        assertEquals(RealtimeConnectionState.GENERATING, f.manager.currentState.connectionState)
        assertEquals(1, f.errors.size)
        f.manager.close()
    }

    @Test fun `invalid input leaves manager reusable without fatal callback`() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        f.listen()
        val invalid = runCatching { f.manager.setLocalAudioVolume(Float.NaN) }.exceptionOrNull() as XmaxError
        assertEquals(XmaxErrorSeverity.RECOVERABLE, invalid.severity)
        f.session.createError = XmaxError(XmaxErrorCode.INVALID_API_KEY, "empty key")
        val local = f.manager.createLocalCameraStream(format, CameraPosition.FRONT)
        runCatching { f.manager.connect(local) }
        runCurrent()
        assertEquals(RealtimeConnectionState.DISCONNECTED, f.manager.currentState.connectionState)
        assertTrue(f.errors.isEmpty())
        f.manager.close()
    }

    @Test fun `fatal generation error preserves committed connection for retry`() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        val local = f.manager.createLocalCameraStream(format, CameraPosition.FRONT)
        f.listen()
        f.manager.connect(local)
        val failure = XmaxError(XmaxErrorCode.TIMEOUT, "SEI timed out")
        f.stream.confirmation.completeExceptionally(failure)
        assertSame(failure, runCatching { f.manager.startGeneration(RealtimeContext("first")) }.exceptionOrNull())
        runCurrent()
        assertEquals(listOf(failure), f.errors)
        assertTrue(f.session.closed.isEmpty())
        assertEquals("session-1", f.manager.currentState.sessionId)
        f.stream.confirmation = CompletableDeferred(Unit)
        f.manager.startGeneration(RealtimeContext("retry"))
        assertEquals(RealtimeConnectionState.GENERATING, f.manager.currentState.connectionState)
        assertEquals(1, f.session.count)
        f.manager.close()
    }

    private class Fixture(dispatcher: CoroutineDispatcher) {
        val media = MediaStub()
        val stream = StreamStub()
        val render = RenderStub()
        val session = SessionStub()
        val errors = mutableListOf<XmaxError>()
        val manager = XmaxRealtimeManager(RealtimeConfiguration(), { _, _ ->
            RealtimeComponents(media, stream, render,
                XmaxRealtimeConnectionManager(session, media, render, stream),
                XmaxRealtimeGenerationManager(media, stream))
        }, RealtimeCallbacks(dispatcher), dispatcher)
        suspend fun listen() { manager.setErrorListener { errors += it } }
    }
    companion object { val format = RealtimeVideoFormat(704, 1280, 24) }
}

private class SessionStub : RealtimeSessionServicing {
    var createBarrier: CompletableDeferred<Unit>? = null
    var createError: XmaxError? = null
    var failure: RealtimeSessionHeartbeatFailureHandler? = null
    var heartbeatJob: Job? = null
    var count = 0
    val closed = mutableListOf<String>()
    override suspend fun createSession(model: RealtimeModel): RealtimeSession {
        createError?.let { throw it }
        withContext(NonCancellable) { createBarrier?.await() }
        return RealtimeSession("session-${++count}", userId = "local", status = "ACTIVE", closeReason = null, connection = RealtimeSessionConnection("room-$count", "local", "token", "bot"))
    }
    override fun startHeartbeat(sessionId: String, onFailure: RealtimeSessionHeartbeatFailureHandler) { failure = onFailure }
    override suspend fun stopHeartbeat() { heartbeatJob?.cancelAndJoin(); heartbeatJob = null }
    override suspend fun closeSession(sessionId: String) { currentCoroutineContext().ensureActive(); closed += sessionId }
}
private class MediaStub : MediaControlling {
    override var currentTrack: RealtimeVideoTrack? = null
    override val currentVideoFormat get() = currentTrack?.videoFormat
    override val hasAudio = false
    var muted = false
    override fun setCameraPreviewReadyListener(listener: RealtimeCameraPreviewReadyListener?) = Unit
    override suspend fun createLocalCameraStream(videoFormat: RealtimeVideoFormat, position: CameraPosition): RealtimeMediaStream {
        val track = RealtimeVideoTrack("local", videoFormat)
        currentTrack = track
        return RealtimeMediaStream("local", track)
    }
    override suspend fun createLocalImageStream(imageData: ByteArray, videoFormat: RealtimeVideoFormat?) = error("unused")
    override suspend fun createLocalImageStream(bitmap: Bitmap, videoFormat: RealtimeVideoFormat?) = error("unused")
    override suspend fun createLocalImageStream(uri: Uri, videoFormat: RealtimeVideoFormat?) = error("unused")
    override suspend fun createLocalVideoStream(uri: Uri, videoFormat: RealtimeVideoFormat?) = error("unused")
    override suspend fun stopLocalCameraStream() { currentTrack = null }
    override suspend fun stopLocalImageStream() = Unit
    override suspend fun stopLocalVideoStream() = Unit
    override suspend fun stopLocalStream() { currentTrack = null }
    override suspend fun setLocalAudioPreviewMuted(muted: Boolean) { this.muted = muted }
    override suspend fun setLocalAudioVolume(volume: Float) = Unit
    override suspend fun switchCamera() = RealtimeMediaStream("local", currentTrack)
    override fun owns(stream: RealtimeMediaStream) = stream.videoTrack === currentTrack
    override suspend fun startInteraction(taskId: String, videoFormat: RealtimeVideoFormat) = Unit
    override suspend fun stopInteraction() = Unit
    override fun submitInteraction(frame: InteractionFrame) = Unit
}
private class StreamStub : StreamControlling {
    var confirmation = CompletableDeferred<Unit>()
    var updateError: XmaxError? = null
    override val hasGenerationTask get() = !confirmation.isCompleted
    override fun setVideoEncoderConfig(videoFormat: RealtimeVideoFormat) = Unit
    override fun setNetworkQualityListener(listener: RealtimeNetworkQualityListener?) = Unit
    override fun setPerformanceAlarmListener(listener: RealtimePerformanceAlarmListener?) = Unit
    override fun setRemoteAudioVolume(volume: Float) = Unit
    override suspend fun connect(connection: RealtimeSessionConnection, includeLocalAudio: Boolean, ensureActive: () -> Unit) { ensureActive() }
    override suspend fun disconnect() = Unit
    override fun setLocalAudioEnabled(enabled: Boolean) = Unit
    override fun pushLocalVideoFrame(frame: VideoFrame) = Unit
    override fun pushLocalAudioFrame(frame: AudioFrame) = Unit
    override suspend fun beginGeneration(taskId: String, videoFormat: RealtimeVideoFormat, context: RealtimeContext): Deferred<Unit> = confirmation
    override fun activateRemoteAudio() = Unit
    override suspend fun updateGeneration(taskId: String, videoFormat: RealtimeVideoFormat, context: RealtimeContext) { updateError?.let { throw it } }
    override suspend fun stopGeneration(taskId: String) = Unit
    override suspend fun sendTracks(taskId: String, points: List<RealtimePoint>) = Unit
}
private class RenderStub : RenderControlling {
    override fun setRemoteStream(stream: RemoteStream?) = Unit
    override fun registerRemoteTrack(track: RealtimeVideoTrack, interactionListener: (InteractionFrame) -> Unit) = Unit
    override fun updateRemoteVideoFormat(videoFormat: RealtimeVideoFormat, track: RealtimeVideoTrack) = Unit
    override suspend fun waitUntilRemoteFrameReady() = Unit
    override fun resetRemoteTrack(track: RealtimeVideoTrack?) = Unit
}
