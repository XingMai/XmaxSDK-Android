package ai.xmax.sdk.foundation.rtc

import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
public class RtcEngineManagerTest {
    @Test
    public fun `engine leases are exclusive and queued in order`() = runTest {
        var createCount = 0
        var destroyCount = 0
        val manager = RtcEngineManager(
            makeEngine = {
                createCount += 1
                EngineStub
            },
            destroyEngine = { destroyCount += 1 },
        )
        val first = manager.acquire()
        val secondRequest = async { manager.acquire() }
        runCurrent()

        assertFalse(secondRequest.isCompleted)
        assertEquals(1, createCount)

        manager.release(first)
        val second = secondRequest.await()

        assertNotEquals(first.id, second.id)
        assertEquals(2, createCount)
        assertEquals(1, destroyCount)

        manager.release(second)
        assertEquals(2, destroyCount)
    }

    @Test
    public fun `cancelled queued request does not create engine`() = runTest {
        var createCount = 0
        var destroyCount = 0
        val manager = RtcEngineManager(
            makeEngine = {
                createCount += 1
                EngineStub
            },
            destroyEngine = { destroyCount += 1 },
        )
        val first = manager.acquire()
        val queued = async { manager.acquire() }
        runCurrent()

        queued.cancel()
        runCurrent()
        manager.release(first)

        assertTrue(queued.isCancelled)
        assertEquals(1, createCount)
        assertEquals(1, destroyCount)
    }

    @Test
    public fun `failed creation unlocks manager for retry`() = runTest {
        var attempt = 0
        var destroyCount = 0
        val manager = RtcEngineManager(
            makeEngine = {
                attempt += 1
                if (attempt == 1) null else EngineStub
            },
            destroyEngine = { destroyCount += 1 },
        )

        val error = expectXmaxError { manager.acquire() }
        val lease = manager.acquire()

        assertEquals(XmaxErrorCode.RTC_ERROR, error.code)
        assertEquals("Failed to create RTC Engine", error.message)
        assertEquals(2, attempt)
        assertEquals(0, destroyCount)

        manager.release(lease)
        assertEquals(1, destroyCount)
    }

    @Test
    public fun `thrown creation failure is mapped and unlocks manager`() = runTest {
        var attempt = 0
        val manager = RtcEngineManager(
            makeEngine = {
                attempt += 1
                if (attempt == 1) error("native failure") else EngineStub
            },
            destroyEngine = {},
        )

        val error = expectXmaxError { manager.acquire() }
        val lease = manager.acquire()

        assertEquals(XmaxErrorCode.RTC_ERROR, error.code)
        assertEquals("Failed to create RTC Engine", error.message)
        assertEquals("native failure", error.cause?.message)
        manager.release(lease)
    }

    @Test
    public fun `stale release does not destroy active engine`() = runTest {
        var destroyCount = 0
        val manager = RtcEngineManager(
            makeEngine = { EngineStub },
            destroyEngine = { destroyCount += 1 },
        )
        val lease = manager.acquire()
        val staleLease = RtcEngineLease(engine = EngineStub)

        manager.release(staleLease)

        assertEquals(0, destroyCount)
        manager.release(lease)
        assertEquals(1, destroyCount)
    }

    private suspend fun expectXmaxError(block: suspend () -> Unit): XmaxError = try {
        block()
        throw AssertionError("Expected XmaxError")
    } catch (error: XmaxError) {
        error
    }
}

private object EngineStub : RtcPlatformEngine {
    override fun configureVideoEncoding(configuration: VideoEncodingConfiguration): Int = 0

    override fun setRemoteAudioVolume(streamId: String, volume: Int): Int = 0

    override fun setEventListener(listener: RtcEventListener?) = Unit

    override fun setQualityListener(listener: RtcQualityListener?) = Unit

    override fun createRoom(roomId: String): RtcPlatformRoom? = null
}
