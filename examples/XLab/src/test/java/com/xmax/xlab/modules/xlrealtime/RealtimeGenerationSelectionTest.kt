package com.xmax.xlab.modules.xlrealtime

import ai.xmax.sdk.RealtimeContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RealtimeGenerationSelectionTest {
    @Test
    fun `failed reference is deselected and one click can select it again`() {
        val selection = RealtimeGenerationSelection()
        val failed = selection.select(RealtimeContext("first", "reference-url"), "reference")

        selection.clear(failed)

        assertNull(selection.referenceId)
        assertNull(selection.current)
        val retry = selection.select(RealtimeContext("retry", "reference-url"), "reference")
        assertNotSame(failed, retry)
        assertEquals("reference", selection.referenceId)
        assertEquals("retry", retry.resolveContext(null).prompt)
    }

    @Test
    fun `late failure cannot deselect the next reference or a retry of the same reference`() {
        val selection = RealtimeGenerationSelection()
        val first = selection.select(RealtimeContext("A"), "A")
        val next = selection.select(RealtimeContext("B"), "B")
        assertFalse(selection.clear(first))
        assertSame(next, selection.current)
        assertEquals("B", selection.referenceId)

        val retry = selection.select(RealtimeContext("B"), "B")
        assertFalse(selection.clear(next))
        assertSame(retry, selection.current)
        assertEquals("B", selection.referenceId)
    }

    @Test
    fun `source replacement reads latest selected conditions after SDK cleanup`() = runTest {
        val selection = RealtimeGenerationSelection()
        val released = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        selection.select(RealtimeContext("old"), "old")
        val sourceChange = async {
            // 模拟 SDK close 尚未完成；页面只保存选择，不负责取消或串行生成。
            released.await()
            events += "old source released"
            selection.current?.let { intent ->
                events += intent.resolveContext("new-source-url").prompt
            }
        }
        runCurrent()
        // 切源期间参考图上传完成，新的条件必须取代切换前保存的条件。
        selection.select(RealtimeContext("latest", "uploaded-reference"), "latest")
        released.complete(Unit)
        sourceChange.await()
        runCurrent()

        assertEquals(listOf("old source released", "latest"), events)
        assertEquals("latest", selection.referenceId)
        assertEquals("uploaded-reference", selection.current?.resolveContext("new-source-url")?.referencePath)
    }

    @Test
    fun `stop while switching sources prevents generation from restarting`() = runTest {
        val selection = RealtimeGenerationSelection()
        val released = CompletableDeferred<Unit>()
        selection.select(RealtimeContext("reference"), "reference")
        var restarted = false
        val sourceChange = async {
            released.await()
            selection.current?.let { restarted = true }
        }
        runCurrent()
        selection.clear()
        released.complete(Unit)
        sourceChange.await()
        runCurrent()

        assertFalse(restarted)
        assertNull(selection.referenceId)
    }

    @Test
    fun `motion generation resolves its reference from each new source`() {
        val selection = RealtimeGenerationSelection()
        val motion = selection.select(context = null)
        assertEquals("image-A", motion.resolveContext("image-A").referencePath)
        assertEquals("image-B", motion.resolveContext("image-B").referencePath)
        assertNull(motion.resolveContext(null).referencePath)
        assertSame(motion, selection.current)
    }
}
