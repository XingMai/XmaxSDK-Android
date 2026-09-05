package ai.xmax.sdk

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ResourceCleanupTest {
    @Test fun `cleanup attempts every resource and preserves original failure`() = runTest {
        val original = IllegalStateException("decode failed")
        val first = IllegalStateException("codec stop failed")
        val second = IllegalStateException("codec release failed")
        var extractorReleased = false
        cleanupAfterFailure(original,
            { throw first },
            { throw second },
            { extractorReleased = true },
        )
        assertTrue(extractorReleased)
        assertSame(first, original.suppressed.single())
        assertSame(second, first.suppressed.single())
    }

    @Test fun `cleanup completes despite cancellation`() = runTest {
        var released = false
        val worker = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() }
            finally { cleanupResources({ yield(); released = true }) }
        }
        worker.cancelAndJoin()
        assertTrue(released)
        assertTrue(worker.isCancelled)
    }
}
