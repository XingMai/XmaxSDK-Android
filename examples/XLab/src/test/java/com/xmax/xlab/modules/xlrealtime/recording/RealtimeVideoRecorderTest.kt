package com.xmax.xlab.modules.xlrealtime.recording

import java.io.File
import java.nio.ByteBuffer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class RealtimeVideoRecorderTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `slow encoding keeps only latest pending frame and stop drains it exactly once`() = runTest {
        val encoder = Encoder()
        lateinit var recorder: RealtimeVideoRecorder
        encoder.onAppend = { frame ->
            if (frame.presentationTimeUs == 1L) {
                (2L..100L).forEach { recorder.append(frame(it)) }
                recorder.stop()
                recorder.stop()
                recorder.append(frame(101))
            }
        }
        recorder = RealtimeVideoRecorder(temporary.root, StandardTestDispatcher(testScheduler)) { _, _ -> encoder }
        recorder.append(frame(1))
        runCurrent()
        val file = recorder.result.await()
        assertEquals(listOf(1L, 100L), encoder.timestamps)
        assertEquals(1, encoder.finishes)
        assertEquals(1, encoder.closes)
        assertTrue(file.exists())
    }

    @Test fun `stopping before first frame creates no output and late frames are ignored`() = runTest {
        var created = 0
        val recorder = RealtimeVideoRecorder(temporary.root, StandardTestDispatcher(testScheduler)) { _, _ -> created++; Encoder() }
        recorder.stop()
        recorder.append(frame(1))
        runCurrent()
        assertTrue(runCatching { recorder.result.await() }.exceptionOrNull()!!.message!!.contains("尚未录制到"))
        assertEquals(0, created)
        assertTrue(temporary.root.listFiles()!!.isEmpty())
    }

    @Test fun `format changes fail recording close encoder and remove incomplete output`() = runTest {
        val encoder = Encoder()
        val recorder = RealtimeVideoRecorder(temporary.root, StandardTestDispatcher(testScheduler)) { _, _ -> encoder }
        recorder.append(frame(1))
        runCurrent()
        recorder.append(frame(2).copy(rotationDegrees = 90))
        runCurrent()
        assertTrue(runCatching { recorder.result.await() }.isFailure)
        assertEquals(1, encoder.closes)
        assertEquals(0, encoder.finishes)
        assertTrue(temporary.root.listFiles()!!.isEmpty())
    }

    @Test fun `encoder setup and finalization failures remove temporary files and permit independent restart`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val setupFailure = IllegalStateException("setup failed")
        val first = RealtimeVideoRecorder(temporary.root, dispatcher) { _, _ -> throw setupFailure }
        first.append(frame(1)); first.stop(); runCurrent()
        val failure = runCatching { first.result.await() }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals(setupFailure.message, failure?.message)
        assertTrue(temporary.root.listFiles()!!.isEmpty())
        val encoder = Encoder().apply { onFinish = { error("EOS failed") } }
        val second = RealtimeVideoRecorder(temporary.root, dispatcher) { _, _ -> encoder }
        first.append(frame(20))
        second.append(frame(2)); second.stop(); runCurrent()
        assertTrue(runCatching { second.result.await() }.isFailure)
        assertEquals(listOf(2L), encoder.timestamps)
        assertEquals(1, encoder.closes)
        assertTrue(temporary.root.listFiles()!!.isEmpty())
    }

    private class Encoder : RecordingEncoder {
        val timestamps = mutableListOf<Long>()
        var finishes = 0
        var closes = 0
        var onAppend: (RecordingFrame) -> Unit = {}
        var onFinish: () -> Unit = {}
        override fun append(frame: RecordingFrame) { timestamps += frame.presentationTimeUs; onAppend(frame) }
        override fun finish() { finishes++; onFinish() }
        override fun close() { closes++ }
    }

    private fun frame(timestamp: Long) = RecordingFrame(4, 2, 0, timestamp, null,
        ByteBuffer.allocate(8), ByteBuffer.allocate(2), ByteBuffer.allocate(2))
}
