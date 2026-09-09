package ai.xmax.sdk

import ai.xmax.sdk.service.realtime.RealtimeSessionConnection
import ai.xmax.sdk.media.interaction.InteractionControlling
import ai.xmax.sdk.media.interaction.InteractionFrame
import ai.xmax.sdk.stream.StreamControlling
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class XmaxRealtimeGenerationManagerTest {
    @Test
    fun `start waits for stream confirmation and caches context`() = runTest {
        val stream = GenerationStreamStub()
        val interaction = GenerationInteractionStub()
        val manager = XmaxRealtimeGenerationManager(interaction, stream) { "task-fixed" }
        val format = RealtimeVideoFormat(704, 1280, 24)
        val context = RealtimeContext("first")

        val start = async { manager.start(format, context) {} }
        runCurrent()
        assertEquals("task-fixed", stream.startedTaskId)
        stream.confirmation.complete(Unit)
        assertEquals("task-fixed", start.await())
        assertEquals("task-fixed", interaction.startedTaskId)

        val restarted = async { manager.start(format, context = null) {} }
        runCurrent()
        stream.confirmation.complete(Unit)
        assertEquals("task-fixed", restarted.await())
    }

    @Test
    fun `first generation requires context`() = runTest {
        val manager = XmaxRealtimeGenerationManager(
            GenerationInteractionStub(),
            GenerationStreamStub(),
        ) { "task-fixed" }

        val error = try {
            manager.start(RealtimeVideoFormat(704, 1280, 24), null) {}
            throw AssertionError("Expected XmaxError")
        } catch (error: XmaxError) {
            error
        }

        assertEquals(XmaxErrorCode.INVALID_CONFIGURATION, error.code)
    }

    @Test
    fun `task ID uses compact Base64URL token and Android suffix`() {
        val first = XmaxRealtimeGenerationManager.createTaskId()
        val second = XmaxRealtimeGenerationManager.createTaskId()
        val token = first.removePrefix("task-").removeSuffix("?os=android")

        assertTrue(first.startsWith("task-"))
        assertTrue(first.endsWith("?os=android"))
        assertEquals(38, first.length)
        assertNotEquals(first, second)
        assertTrue(token.matches(Regex("[A-Za-z0-9_-]{22}")))
    }
}

private class GenerationInteractionStub : InteractionControlling {
    var startedTaskId: String? = null

    override suspend fun startInteraction(taskId: String, videoFormat: RealtimeVideoFormat) {
        startedTaskId = taskId
    }

    override suspend fun stopInteraction() = Unit
    override fun submitInteraction(frame: InteractionFrame) = Unit
}

private class GenerationStreamStub : StreamControlling {
    var startedTaskId: String? = null
    var confirmation = CompletableDeferred<Unit>()

    override fun setVideoEncoderConfig(videoFormat: RealtimeVideoFormat) = Unit
    override fun setNetworkQualityListener(listener: RealtimeNetworkQualityListener?) = Unit
    override fun setPerformanceAlarmListener(listener: RealtimePerformanceAlarmListener?) = Unit
    override fun setRemoteAudioVolume(volume: Float) = Unit
    override suspend fun connect(
        connection: RealtimeSessionConnection,
        includeLocalAudio: Boolean,
        ensureActive: () -> Unit,
    ) = Unit
    override suspend fun disconnect() = Unit
    override fun pushLocalVideoFrame(frame: VideoFrame) = Unit
    override fun pushLocalAudioFrame(frame: AudioFrame) = Unit
    override suspend fun beginGeneration(
        taskId: String,
        videoFormat: RealtimeVideoFormat,
        context: RealtimeContext,
    ): Deferred<Unit> {
        startedTaskId = taskId
        if (confirmation.isCompleted) confirmation = CompletableDeferred()
        return confirmation
    }
    override fun activateRemoteAudio() = Unit
    override suspend fun updateGeneration(
        taskId: String,
        videoFormat: RealtimeVideoFormat,
        context: RealtimeContext,
    ) = Unit
    override suspend fun stopGeneration(taskId: String) = Unit
    override suspend fun sendTracks(taskId: String, points: List<RealtimePoint>) = Unit
}
