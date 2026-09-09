package ai.xmax.sdk

import ai.xmax.sdk.foundation.rtc.RemoteStream
import ai.xmax.sdk.media.MediaControlling
import ai.xmax.sdk.media.interaction.InteractionFrame
import ai.xmax.sdk.render.RenderControlling
import ai.xmax.sdk.render.RenderController
import ai.xmax.sdk.stream.room.RtcManagingStub
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
    @Test fun `camera microphone starts on connect stops on disconnect and restarts on reconnect`() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        val local = f.manager.createLocalCameraStream(
            format,
            CameraPosition.FRONT,
            useMicrophone = true,
        )
        assertEquals(0, f.media.microphoneStartCount)

        f.manager.connect(local)
        assertEquals(1, f.media.microphoneStartCount)
        assertEquals(listOf(true), f.stream.localAudioSelections)

        f.manager.disconnect()
        assertEquals(1, f.media.microphoneStopCount)
        assertSame(local.videoTrack, f.media.currentTrack)

        f.manager.connect(local)
        assertEquals(2, f.media.microphoneStartCount)
        assertEquals(listOf(true, true), f.stream.localAudioSelections)
        f.manager.close()
        assertEquals(2, f.media.microphoneStopCount)
    }

    @Test fun `audio preferences survive close and are restored before a new local stream starts`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val frameDispatchers = mutableListOf<ai.xmax.sdk.render.video.RealtimeVideoFrameDispatcher>()
        val mediaInstances = mutableListOf<MediaStub>()
        val streamInstances = mutableListOf<StreamStub>()
        val manager = XmaxRealtimeManager(RealtimeConfiguration(), { _, _, frames ->
            frameDispatchers += frames
            val media = MediaStub().also(mediaInstances::add)
            val stream = StreamStub().also(streamInstances::add)
            val render = RenderStub()
            RealtimeComponents(media, stream, render,
                XmaxRealtimeConnectionManager(SessionStub(), media, render, stream),
                XmaxRealtimeGenerationManager(media, stream))
        }, RealtimeCallbacks(dispatcher), dispatcher)

        manager.setLocalAudioVolume(0f)
        manager.setRemoteAudioVolume(0f)
        manager.createLocalCameraStream(format, CameraPosition.FRONT)
        manager.close()
        manager.createLocalCameraStream(format, CameraPosition.FRONT)
        assertSame(frameDispatchers.first(), frameDispatchers.last())
        assertEquals(2, mediaInstances.size)
        assertEquals(0f, mediaInstances.last().volumeAtStart)
        assertEquals(0f, streamInstances.last().volume)

        manager.setLocalAudioVolume(0.2f)
        manager.setRemoteAudioVolume(0.6f)
        mediaInstances.last().volumeError = XmaxError(XmaxErrorCode.MEDIA_ERROR, "volume failed")
        streamInstances.last().volumeError = XmaxError(XmaxErrorCode.RTC_ERROR, "volume failed")
        assertTrue(runCatching { manager.setLocalAudioVolume(0.8f) }.isFailure)
        assertTrue(runCatching { manager.setRemoteAudioVolume(0.9f) }.isFailure)
        assertTrue(runCatching { manager.setLocalAudioVolume(Float.NaN) }.isFailure)
        manager.close()
        manager.setCameraPreviewReadyListener(null)
        assertEquals(3, mediaInstances.size)
        assertEquals(0.6f, streamInstances.last().volume)
        manager.createLocalCameraStream(format, CameraPosition.FRONT)
        assertEquals(0.2f, mediaInstances.last().volumeAtStart)
        assertEquals(0f, streamInstances.last().volume)
        manager.close()
    }

    @Test fun `new camera and image streams reset remote volume to zero`() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        f.manager.setRemoteAudioVolume(0.35f)
        f.manager.createLocalCameraStream(format, CameraPosition.FRONT)
        assertEquals(0f, f.stream.volume)
        f.manager.stopLocalCameraStream()

        f.manager.setRemoteAudioVolume(0.35f)
        f.manager.createLocalImageStream(byteArrayOf(1))
        assertEquals(0f, f.stream.volume)
        f.manager.close()
    }

    @Test fun `failed media creation preserves remote volume`() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        f.manager.setRemoteAudioVolume(0.35f)
        val failure = XmaxError(XmaxErrorCode.MEDIA_ERROR, "capture failed")
        f.media.createError = failure

        val thrown = runCatching {
            f.manager.createLocalCameraStream(format, CameraPosition.FRONT)
        }.exceptionOrNull() as XmaxError
        assertEquals(failure.code, thrown.code)
        assertEquals(0.35f, f.stream.volume)
        f.manager.close()
    }

    @Test fun `public listeners survive close and runtime recreation`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val callbacks = RealtimeCallbacks(dispatcher)
        val mediaInstances = mutableListOf<MediaStub>()
        val streamInstances = mutableListOf<StreamStub>()
        val manager = XmaxRealtimeManager(RealtimeConfiguration(), { _, _, _ ->
            val media = MediaStub().also(mediaInstances::add)
            val stream = StreamStub().also(streamInstances::add)
            val render = RenderStub()
            RealtimeComponents(media, stream, render,
                XmaxRealtimeConnectionManager(SessionStub(), media, render, stream),
                XmaxRealtimeGenerationManager(media, stream))
        }, callbacks, dispatcher)
        val cameraListener = RealtimeCameraPreviewReadyListener { }
        val networkListener = RealtimeNetworkQualityListener { _ -> }
        val performanceListener = RealtimePerformanceAlarmListener { _ -> }
        val states = mutableListOf<RealtimeConnectionState>()
        val errors = mutableListOf<XmaxError>()

        manager.setStateListener { states += it.connectionState }
        manager.setErrorListener(errors::add)
        manager.setCameraPreviewReadyListener(cameraListener)
        manager.setNetworkQualityListener(networkListener)
        manager.setPerformanceAlarmListener(performanceListener)
        manager.close()
        runCurrent()

        assertEquals(RealtimeConnectionState.DISCONNECTED, states.last())
        callbacks.error(XmaxError(XmaxErrorCode.RTC_ERROR, "after close"))
        runCurrent()
        assertEquals(1, errors.size)

        manager.createLocalCameraStream(format, CameraPosition.FRONT)
        assertEquals(2, mediaInstances.size)
        assertSame(cameraListener, mediaInstances.last().registeredCameraPreviewReadyListener)
        assertSame(networkListener, streamInstances.last().registeredNetworkQualityListener)
        assertSame(performanceListener, streamInstances.last().registeredPerformanceAlarmListener)
        manager.close()
    }

    @Test fun `generation mutes preview before connection and cancellation restores it after rollback`() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        val local = f.manager.createLocalCameraStream(format, CameraPosition.FRONT)
        val release = CompletableDeferred<Unit>()
        f.session.createBarrier = release
        val start = async { f.manager.startGeneration(local, RealtimeContext("video")) }
        runCurrent()
        assertEquals(RealtimeConnectionState.CONNECTING, f.manager.currentState.connectionState)
        assertTrue(f.media.muted)

        start.cancel()
        runCurrent()
        assertFalse(start.isCompleted)
        release.complete(Unit)
        start.join()
        assertFalse(f.media.muted)
        assertSame(local.videoTrack, f.media.currentTrack)
        assertEquals(listOf("session-1"), f.session.closed)
        f.manager.close()
    }

    @Test fun `generation connection failure restores preview even for recoverable errors`() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        val local = f.manager.createLocalCameraStream(format, CameraPosition.FRONT)
        f.session.createError = XmaxError(XmaxErrorCode.INVALID_API_KEY, "missing key")
        assertTrue(runCatching { f.manager.startGeneration(local, RealtimeContext("video")) }.isFailure)
        assertEquals(listOf(true, false), f.media.muteChanges)
        assertFalse(f.media.muted)
        assertSame(local.videoTrack, f.media.currentTrack)
        f.manager.close()
    }

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

    @Test fun `disconnect while awaiting SEI releases connection retains preview and permits restart`() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        val local = f.manager.createLocalCameraStream(format, CameraPosition.FRONT)
        f.manager.connect(local)
        val start = async { f.manager.startGeneration(RealtimeContext("first")) }
        runCurrent()
        f.manager.disconnect(); start.join()
        assertTrue(start.isCancelled)
        assertEquals(RealtimeConnectionState.DISCONNECTED, f.manager.currentState.connectionState)
        assertSame(local.videoTrack, f.media.currentTrack)
        assertFalse(f.media.muted)
        assertEquals(listOf("session-1"), f.session.closed)
        assertTrue(f.errors.isEmpty())
        f.stream.confirmation = CompletableDeferred(Unit)
        f.manager.startGeneration(local, RealtimeContext("retry"))
        assertEquals(RealtimeConnectionState.GENERATING, f.manager.currentState.connectionState)
        assertEquals(2, f.session.count)
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

    @Test fun `generating requires a new frame after SEI on every start including reused connections`() = runTest {
        val rtc = RtcManagingStub()
        val render = RenderController(rtc, renderDispatcher = StandardTestDispatcher(testScheduler))
        val f = Fixture(StandardTestDispatcher(testScheduler), render)
        val local = f.manager.createLocalCameraStream(format, CameraPosition.FRONT)
        f.manager.connect(local)
        val remote = RemoteStream("room-1", "bot")
        val start = async { f.manager.startGeneration(RealtimeContext("first")) }
        runCurrent()
        assertEquals(RealtimeConnectionState.CONNECTED, f.manager.currentState.connectionState)
        // StreamController selects the stream before resolving its matching-SEI confirmation.
        render.setRemoteStream(remote)
        val oldFrame = rtc.captureRemoteVideoFrameListener(remote)!!
        f.stream.confirmation.complete(Unit)
        runCurrent()
        assertFalse(start.isCompleted)
        assertEquals(0, f.stream.audioActivationCount)
        assertEquals(RealtimeConnectionState.CONNECTED, f.manager.currentState.connectionState)
        rtc.emitRemoteVideoFrame(remote, 704, 1280)
        start.await()
        assertEquals(RealtimeConnectionState.GENERATING, f.manager.currentState.connectionState)
        assertEquals(1, f.stream.audioActivationCount)

        f.manager.disconnect()
        f.stream.confirmation = CompletableDeferred()
        val restart = async { f.manager.startGeneration(local, RealtimeContext("second")) }
        runCurrent()
        render.setRemoteStream(remote)
        f.stream.confirmation.complete(Unit)
        oldFrame(704, 1280)
        runCurrent()
        assertFalse(restart.isCompleted)
        assertEquals(RealtimeConnectionState.CONNECTED, f.manager.currentState.connectionState)
        assertEquals(1, f.stream.audioActivationCount)
        rtc.emitRemoteVideoFrame(remote, 704, 1280)
        restart.await()
        assertEquals(RealtimeConnectionState.GENERATING, f.manager.currentState.connectionState)
        assertEquals(2, f.stream.audioActivationCount)
        assertEquals(2, f.session.count)
        f.manager.close()
    }

    @Test fun `disconnect during first frame wait invalidates receiver and does not emit fatal error`() = runTest {
        val rtc = RtcManagingStub()
        val render = RenderController(rtc, renderDispatcher = StandardTestDispatcher(testScheduler))
        val f = Fixture(StandardTestDispatcher(testScheduler), render)
        f.listen()
        val local = f.manager.createLocalCameraStream(format, CameraPosition.FRONT)
        f.manager.connect(local)
        val start = async { f.manager.startGeneration(RealtimeContext("first")) }
        runCurrent()
        val remote = RemoteStream("room-1", "bot")
        render.setRemoteStream(remote)
        val oldFrame = rtc.captureRemoteVideoFrameListener(remote)!!
        f.stream.confirmation.complete(Unit)
        runCurrent()
        f.manager.disconnect()
        start.join()
        oldFrame(704, 1280)
        runCurrent()
        assertTrue(start.isCancelled)
        assertNull(rtc.captureRemoteVideoFrameListener(remote))
        assertEquals(RealtimeConnectionState.DISCONNECTED, f.manager.currentState.connectionState)
        assertEquals(0, f.stream.audioActivationCount)
        assertTrue(f.errors.isEmpty())
        f.manager.close()
    }

    @Test fun `frame timeout reports fatal once and retains the connection for a fresh-frame retry`() = runTest {
        val rtc = RtcManagingStub()
        val render = RenderController(rtc, remoteFrameReadyTimeoutMillis = 1_000, renderDispatcher = StandardTestDispatcher(testScheduler))
        val f = Fixture(StandardTestDispatcher(testScheduler), render)
        f.listen()
        val local = f.manager.createLocalCameraStream(format, CameraPosition.FRONT)
        f.manager.connect(local)
        val remote = RemoteStream("room-1", "bot")
        val start = async { runCatching { f.manager.startGeneration(RealtimeContext("first")) } }
        runCurrent()
        render.setRemoteStream(remote)
        f.stream.confirmation.complete(Unit)
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        val failure = start.await().exceptionOrNull() as XmaxError
        assertEquals(XmaxErrorCode.TIMEOUT, failure.code)
        assertEquals(XmaxErrorSeverity.FATAL, failure.severity)
        assertEquals(listOf(failure), f.errors)
        assertNull(rtc.captureRemoteVideoFrameListener(remote))
        assertEquals(0, f.stream.audioActivationCount)
        assertTrue(f.session.closed.isEmpty())

        f.stream.confirmation = CompletableDeferred()
        val restart = async { f.manager.startGeneration(RealtimeContext("retry")) }
        runCurrent()
        render.setRemoteStream(remote)
        f.stream.confirmation.complete(Unit)
        runCurrent()
        assertFalse(restart.isCompleted)
        rtc.emitRemoteVideoFrame(remote, 704, 1280)
        restart.await()
        assertEquals(RealtimeConnectionState.GENERATING, f.manager.currentState.connectionState)
        assertEquals(1, f.errors.size)
        f.manager.close()
    }

    @Test fun `registering and clearing frame listener do not initialize RTC components`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var builds = 0
        val frames = ai.xmax.sdk.render.video.RealtimeVideoFrameDispatcher(dispatcher)
        val manager = XmaxRealtimeManager(RealtimeConfiguration(), { _, _, _ ->
            builds++
            error("Frame listeners must not create a runtime")
        }, RealtimeCallbacks(dispatcher, frames), dispatcher)
        manager.setRemoteVideoFrameListener { }
        manager.setRemoteVideoFrameListener(null)
        manager.close()
        assertEquals(0, builds)
    }

    @Test fun `timing measures reconnects but skips updates and cancellation`() = runTest {
        val logs = mutableListOf<String>()
        val f = Fixture(StandardTestDispatcher(testScheduler), timing = RealtimeTiming({ testScheduler.currentTime * 1_000_000 }, logs::add))
        val local = f.manager.createLocalCameraStream(format, CameraPosition.FRONT)
        f.stream.confirmation.complete(Unit)
        f.manager.startGeneration(local, RealtimeContext("first"))
        assertEquals(1, logs.size)
        assertTrue(logs.single().contains("实时连接"))
        f.manager.startGeneration(RealtimeContext("updated"))
        assertEquals(1, logs.size)
        f.manager.disconnect()
        f.manager.startGeneration(local, RealtimeContext("second"))
        assertEquals(2, logs.size)
        assertTrue(logs.last().contains("实时连接"))
        f.manager.disconnect()
        f.stream.confirmation = CompletableDeferred()
        val cancelled = async { f.manager.startGeneration(local, RealtimeContext("cancelled")) }
        runCurrent()
        f.manager.disconnect()
        cancelled.join()
        assertTrue(cancelled.isCancelled)
        assertEquals(2, logs.size)
        f.manager.close()
    }

    @Test fun `public frame listener survives disconnect and close`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val frameScheduler = kotlinx.coroutines.test.TestCoroutineScheduler()
        val rtc = ai.xmax.sdk.stream.room.RtcManagingStub()
        val frames = ai.xmax.sdk.render.video.RealtimeVideoFrameDispatcher(StandardTestDispatcher(frameScheduler))
        val render = ai.xmax.sdk.render.RenderController(rtc, renderDispatcher = dispatcher,
            frameDispatcher = frames)
        val f = Fixture(dispatcher, render, frames = frames)
        val local = f.manager.createLocalCameraStream(format, CameraPosition.FRONT)
        f.manager.connect(local)
        val received = mutableListOf<Long>()
        f.manager.setRemoteVideoFrameListener { received += it.presentationTimeUs }
        val stream = ai.xmax.sdk.foundation.rtc.RemoteStream("room", "bot")
        val frame = object : ai.xmax.sdk.foundation.rtc.RtcRemoteVideoFrame {
            override val width = 2
            override val height = 2
            override fun copy() = RealtimeVideoFrame(2, 2, 42, null, 0, ByteArray(4), ByteArray(1), ByteArray(1))
        }
        render.setRemoteStream(stream)
        val old = rtc.captureRemoteVideoSink(stream)!!
        old.onFrame(frame)
        f.manager.disconnect()
        frameScheduler.runCurrent()
        assertTrue(received.isEmpty())
        render.setRemoteStream(stream)
        old.onFrame(frame)
        rtc.captureRemoteVideoSink(stream)!!.onFrame(frame)
        frameScheduler.runCurrent()
        assertEquals(listOf(42L), received)
        rtc.captureRemoteVideoSink(stream)!!.onFrame(frame)
        f.manager.close()
        render.setRemoteStream(stream)
        rtc.captureRemoteVideoSink(stream)!!.onFrame(frame)
        frameScheduler.runCurrent()
        assertEquals(listOf(42L, 42L), received)
        render.setRemoteStream(null)
    }

    private class Fixture(
        dispatcher: CoroutineDispatcher,
        frameGate: RenderControlling = RenderStub(),
        timing: RealtimeTiming = RealtimeTiming(),
        frames: ai.xmax.sdk.render.video.RealtimeVideoFrameDispatcher = ai.xmax.sdk.render.video.RealtimeVideoFrameDispatcher(),
    ) {
        val media = MediaStub()
        val stream = StreamStub()
        val render = object : RenderControlling by frameGate {
            // JVM tests exercise the real frame gate; Android view/trajectory binding has device tests.
            override fun registerRemoteTrack(track: RealtimeVideoTrack, interactionListener: (InteractionFrame) -> Unit) = Unit
        }
        val session = SessionStub()
        val errors = mutableListOf<XmaxError>()
        val manager = XmaxRealtimeManager(RealtimeConfiguration(), { _, _, _ ->
            RealtimeComponents(media, stream, render,
                XmaxRealtimeConnectionManager(session, media, render, stream),
                XmaxRealtimeGenerationManager(media, stream))
        }, RealtimeCallbacks(dispatcher, frames), dispatcher, timing)
        init { stream.onStop = { render.setRemoteStream(null) } }
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
    override var hasAudio = false
    var microphoneStartCount = 0
    var microphoneStopCount = 0
    var muted = false
    val muteChanges = mutableListOf<Boolean>()
    var volume = 0.45f
    var volumeAtStart = 0.45f
    var volumeError: XmaxError? = null
    var createError: XmaxError? = null
    var registeredCameraPreviewReadyListener: RealtimeCameraPreviewReadyListener? = null
    override fun setCameraPreviewReadyListener(listener: RealtimeCameraPreviewReadyListener?) {
        registeredCameraPreviewReadyListener = listener
    }
    override suspend fun createLocalCameraStream(
        videoFormat: RealtimeVideoFormat,
        position: CameraPosition,
        useMicrophone: Boolean,
    ): RealtimeMediaStream {
        createError?.let { throw it }
        volumeAtStart = volume
        hasAudio = useMicrophone
        val track = RealtimeVideoTrack("local", videoFormat)
        currentTrack = track
        return RealtimeMediaStream("local", track)
    }
    override fun startMicrophoneCapture() {
        if (hasAudio) microphoneStartCount += 1
    }
    override fun stopMicrophoneCapture() {
        if (hasAudio) microphoneStopCount += 1
    }
    override suspend fun createLocalImageStream(
        imageData: ByteArray,
        videoFormat: RealtimeVideoFormat?,
    ): RealtimeMediaStream {
        createError?.let { throw it }
        hasAudio = false
        val track = RealtimeVideoTrack(
            "local",
            videoFormat ?: RealtimeVideoFormat(704, 1_280, 24),
        )
        currentTrack = track
        return RealtimeMediaStream("local", track)
    }
    override suspend fun createLocalImageStream(bitmap: Bitmap, videoFormat: RealtimeVideoFormat?) = error("unused")
    override suspend fun createLocalImageStream(uri: Uri, videoFormat: RealtimeVideoFormat?) = error("unused")
    override suspend fun createLocalVideoStream(uri: Uri, videoFormat: RealtimeVideoFormat?) = error("unused")
    override suspend fun stopLocalCameraStream() { currentTrack = null; hasAudio = false }
    override suspend fun stopLocalImageStream() { currentTrack = null; hasAudio = false }
    override suspend fun stopLocalVideoStream() = Unit
    override suspend fun stopLocalStream() { currentTrack = null }
    override suspend fun setLocalAudioPreviewMuted(muted: Boolean) { this.muted = muted; muteChanges += muted }
    override suspend fun setLocalAudioVolume(volume: Float) { volumeError?.let { throw it }; this.volume = volume }
    override suspend fun switchCamera() = RealtimeMediaStream("local", currentTrack)
    override fun owns(stream: RealtimeMediaStream) = stream.videoTrack === currentTrack
    override suspend fun startInteraction(taskId: String, videoFormat: RealtimeVideoFormat) = Unit
    override suspend fun stopInteraction() = Unit
    override fun submitInteraction(frame: InteractionFrame) = Unit
}
private class StreamStub : StreamControlling {
    var onStop: () -> Unit = {}
    var audioActivationCount = 0
    var confirmation = CompletableDeferred<Unit>()
    var updateError: XmaxError? = null
    var volume = 1f
    var volumeError: XmaxError? = null
    val localAudioSelections = mutableListOf<Boolean>()
    var registeredNetworkQualityListener: RealtimeNetworkQualityListener? = null
    var registeredPerformanceAlarmListener: RealtimePerformanceAlarmListener? = null
    override fun setVideoEncoderConfig(videoFormat: RealtimeVideoFormat) = Unit
    override fun setNetworkQualityListener(listener: RealtimeNetworkQualityListener?) {
        registeredNetworkQualityListener = listener
    }
    override fun setPerformanceAlarmListener(listener: RealtimePerformanceAlarmListener?) {
        registeredPerformanceAlarmListener = listener
    }
    override fun setRemoteAudioVolume(volume: Float) { volumeError?.let { throw it }; this.volume = volume }
    override suspend fun connect(connection: RealtimeSessionConnection, includeLocalAudio: Boolean, ensureActive: () -> Unit) {
        ensureActive()
        localAudioSelections += includeLocalAudio
    }
    override suspend fun disconnect() = Unit
    override fun pushLocalVideoFrame(frame: VideoFrame) = Unit
    override fun pushLocalAudioFrame(frame: AudioFrame) = Unit
    override suspend fun beginGeneration(taskId: String, videoFormat: RealtimeVideoFormat, context: RealtimeContext): Deferred<Unit> {
        val timing = currentCoroutineContext()[RealtimeTiming.Attempt]
        timing?.beginSignal(taskId)
        confirmation.invokeOnCompletion { error -> if (error == null) timing?.matchSEI(taskId) }
        return confirmation
    }
    override fun activateRemoteAudio() { audioActivationCount++ }
    override suspend fun updateGeneration(taskId: String, videoFormat: RealtimeVideoFormat, context: RealtimeContext) { updateError?.let { throw it } }
    override suspend fun stopGeneration(taskId: String) { onStop() }
    override suspend fun sendTracks(taskId: String, points: List<RealtimePoint>) = Unit
}
private class RenderStub : RenderControlling {
    override fun setRemoteStream(stream: RemoteStream?) = Unit
    override fun registerRemoteTrack(track: RealtimeVideoTrack, interactionListener: (InteractionFrame) -> Unit) = Unit
    override fun updateRemoteVideoFormat(videoFormat: RealtimeVideoFormat, track: RealtimeVideoTrack) = Unit
    override suspend fun waitUntilRemoteFrameReady() = Unit
    override suspend fun resetRemoteTrack(track: RealtimeVideoTrack?) = Unit
}
