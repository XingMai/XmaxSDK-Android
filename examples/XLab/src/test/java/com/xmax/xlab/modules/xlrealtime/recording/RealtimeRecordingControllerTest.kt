package com.xmax.xlab.modules.xlrealtime.recording

import ai.xmax.sdk.RealtimeVideoFrame
import ai.xmax.sdk.RealtimeVideoFrameListener
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class RealtimeRecordingControllerTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `stop source change and exit share one save and release listener before encoding finishes`() = runTest {
        val recording = Recording()
        val listeners = mutableListOf<RealtimeVideoFrameListener?>()
        val saveBarrier = CompletableDeferred<Unit>()
        val messages = mutableListOf<String>()
        var saves = 0
        var starts = 0
        val controller = RealtimeRecordingController(
            { listeners += it }, { starts++; recording },
            { saves++; saveBarrier.await() }, messages::add, StandardTestDispatcher(testScheduler),
        )
        controller.start(); controller.start()
        assertEquals(1, starts)
        assertNotNull(listeners.last())
        controller.stopAndSave(); controller.stopAndSave()
        runCurrent()
        assertNull(listeners.last())
        assertEquals(RecordingState.SAVING, controller.state.value)
        controller.start()
        assertEquals(1, starts)
        val file = temporary.newFile("output.mp4")
        recording.result.complete(file)
        val closing = async { controller.close() }
        runCurrent()
        assertFalse(closing.isCompleted)
        assertEquals(1, saves)
        saveBarrier.complete(Unit)
        closing.await()
        assertFalse(file.exists())
        assertEquals(RecordingState.IDLE, controller.state.value)
        assertEquals(1, messages.size)
        controller.start()
        assertEquals(1, starts)
    }

    @Test fun `encoding failure detaches listener reports once and allows new recording`() = runTest {
        var recording = Recording()
        val listeners = mutableListOf<RealtimeVideoFrameListener?>()
        val messages = mutableListOf<String>()
        val controller = RealtimeRecordingController(
            { listeners += it }, { recording }, { error("must not save") }, messages::add,
            StandardTestDispatcher(testScheduler),
        )
        controller.start()
        recording.result.completeExceptionally(IllegalStateException("codec failed"))
        runCurrent()
        assertNull(listeners.last())
        assertEquals(RecordingState.IDLE, controller.state.value)
        assertEquals(1, messages.size)
        recording = Recording()
        controller.start()
        assertNotNull(listeners.last())
        recording.result.completeExceptionally(IllegalStateException("no frames"))
        controller.close()
        assertEquals(2, messages.size)
    }

    @Test fun `save failure deletes temporary video and never emits success`() = runTest {
        val recording = Recording()
        val messages = mutableListOf<String>()
        val controller = RealtimeRecordingController({}, { recording }, { error("storage full") }, messages::add,
            StandardTestDispatcher(testScheduler))
        controller.start()
        val file = temporary.newFile("failed.mp4")
        recording.result.complete(file)
        runCurrent()
        assertFalse(file.exists())
        assertEquals(1, messages.size)
        assertTrue(messages.single().contains("storage full"))
        controller.close()
    }

    private class Recording : VideoRecording {
        override val result = CompletableDeferred<File>()
        override fun append(frame: RealtimeVideoFrame) = Unit
        override fun stop() = Unit
    }
}
