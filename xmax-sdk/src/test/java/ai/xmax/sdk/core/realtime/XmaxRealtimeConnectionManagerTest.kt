package ai.xmax.sdk

import ai.xmax.sdk.rendering.RenderControlling
import ai.xmax.sdk.media.interaction.InteractionControlling
import ai.xmax.sdk.media.interaction.InteractionFrame
import ai.xmax.sdk.service.realtime.RealtimeSession
import ai.xmax.sdk.service.realtime.RealtimeSessionConnection
import ai.xmax.sdk.service.realtime.RealtimeSessionHeartbeatFailureHandler
import ai.xmax.sdk.service.realtime.RealtimeSessionServicing
import ai.xmax.sdk.stream.StreamControlling
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class XmaxRealtimeConnectionManagerTest {
    @Test
    fun `connect activates session and remote track then disconnect releases all resources`() = runTest {
        val sessionService = SessionServiceStub()
        val streamController = StreamControllerStub()
        val renderController = RenderControllerStub()
        val manager = XmaxRealtimeConnectionManager(
            sessionService,
            InteractionControllerStub(),
            renderController,
            streamController,
        )

        val remoteStream = manager.connect(
            model = RealtimeModel.X2_0,
            videoFormat = RealtimeVideoFormat(704, 1280, 24),
            includeLocalAudio = false,
            isCurrent = { true },
            onHeartbeatFailure = { _, _ -> },
        )

        assertEquals("session-id", manager.currentSessionId)
        assertEquals("stream-remote", remoteStream.id)
        assertEquals("bot-id", remoteStream.videoTrack?.id)
        assertSame(remoteStream.videoTrack, manager.currentRemoteStream?.videoTrack)
        assertTrue(sessionService.heartbeatStarted)
        assertSame(remoteStream.videoTrack, renderController.registeredTrack)

        assertEquals("session-id", manager.disconnect())
        assertEquals("", manager.currentSessionId)
        assertNull(manager.currentRemoteStream)
        assertTrue(streamController.disconnected)
        assertEquals(listOf("session-id"), sessionService.closedSessionIds)
        assertSame(remoteStream.videoTrack, renderController.resetTrack)
    }

    @Test
    fun `failed RTC connection closes the newly created session`() = runTest {
        val sessionService = SessionServiceStub()
        val streamController = StreamControllerStub(
            connectError = XmaxError(XmaxErrorCode.RTC_ERROR, "join failed"),
        )
        val manager = XmaxRealtimeConnectionManager(
            sessionService,
            InteractionControllerStub(),
            RenderControllerStub(),
            streamController,
        )

        val error = try {
            manager.connect(
                model = RealtimeModel.X2_0,
                videoFormat = RealtimeVideoFormat(704, 1280, 24),
                includeLocalAudio = false,
                isCurrent = { true },
                onHeartbeatFailure = { _, _ -> },
            )
            throw AssertionError("Expected XmaxError")
        } catch (error: XmaxError) {
            error
        }

        assertEquals(XmaxErrorCode.RTC_ERROR, error.code)
        assertEquals(listOf("session-id"), sessionService.closedSessionIds)
        assertTrue(streamController.disconnected)
    }
}

private class SessionServiceStub : RealtimeSessionServicing {
    var heartbeatStarted = false
    val closedSessionIds = mutableListOf<String>()

    override suspend fun createSession(model: RealtimeModel): RealtimeSession = RealtimeSession(
        id = "session-id",
        userId = "user-id",
        status = "ACTIVE",
        connection = RealtimeSessionConnection(
            roomId = "room-id",
            userId = "user-id",
            token = "room-token",
            botName = "bot-id",
        ),
        closeReason = null,
    )

    override fun startHeartbeat(
        sessionId: String,
        onFailure: RealtimeSessionHeartbeatFailureHandler,
    ) {
        heartbeatStarted = true
    }

    override fun stopHeartbeat() {
        heartbeatStarted = false
    }

    override suspend fun closeSession(sessionId: String) {
        closedSessionIds += sessionId
    }
}

private class RenderControllerStub : RenderControlling {
    var registeredTrack: RealtimeVideoTrack? = null
    var resetTrack: RealtimeVideoTrack? = null

    override fun setRemoteStream(stream: ai.xmax.sdk.foundation.rtc.RemoteStream?) = Unit

    override fun registerRemoteTrack(
        track: RealtimeVideoTrack,
        interactionListener: (InteractionFrame) -> Unit,
    ) {
        registeredTrack = track
    }

    override fun updateRemoteVideoFormat(
        videoFormat: RealtimeVideoFormat,
        track: RealtimeVideoTrack,
    ) = Unit

    override suspend fun waitUntilRemoteFrameReady() = Unit

    override fun resetRemoteTrack(track: RealtimeVideoTrack?) {
        resetTrack = track
    }
}

private class InteractionControllerStub : InteractionControlling {
    override suspend fun startInteraction(taskId: String, videoFormat: RealtimeVideoFormat) = Unit
    override suspend fun stopInteraction() = Unit
    override fun submitInteraction(frame: InteractionFrame) = Unit
}

private class StreamControllerStub(
    private val connectError: Throwable? = null,
) : StreamControlling {
    var disconnected = false

    override val hasGenerationTask: Boolean = false
    override fun setVideoEncoderConfig(videoFormat: RealtimeVideoFormat) = Unit
    override fun setNetworkQualityListener(listener: RealtimeNetworkQualityListener?) = Unit
    override fun setPerformanceAlarmListener(listener: RealtimePerformanceAlarmListener?) = Unit
    override fun setRemoteAudioVolume(volume: Float) = Unit
    override suspend fun connect(
        connection: RealtimeSessionConnection,
        includeLocalAudio: Boolean,
        ensureActive: () -> Unit,
    ) {
        connectError?.let { throw it }
        ensureActive()
    }
    override suspend fun disconnect() {
        disconnected = true
    }
    override fun setLocalAudioEnabled(enabled: Boolean) = Unit
    override fun pushLocalVideoFrame(frame: VideoFrame) = Unit
    override fun pushLocalAudioFrame(frame: AudioFrame) = Unit
    override suspend fun beginGeneration(
        taskId: String,
        videoFormat: RealtimeVideoFormat,
        context: RealtimeContext,
    ): Deferred<Unit> = CompletableDeferred(Unit)
    override fun activateRemoteAudio() = Unit
    override suspend fun updateGeneration(
        taskId: String,
        videoFormat: RealtimeVideoFormat,
        context: RealtimeContext,
    ) = Unit
    override suspend fun stopGeneration(taskId: String) = Unit
    override suspend fun sendTracks(taskId: String, points: List<RealtimePoint>) = Unit
}
