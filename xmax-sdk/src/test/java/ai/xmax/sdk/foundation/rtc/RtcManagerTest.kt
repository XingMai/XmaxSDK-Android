package ai.xmax.sdk.foundation.rtc

import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
public class RtcManagerTest {
    @Test
    public fun `initialize and destroy are idempotent`() = runTest {
        val room = FakeRtcPlatformRoom()
        val engineManager = FakeRtcEngineManager(FakeRtcPlatformEngine(room))
        val manager = RtcManager(engineManager)

        manager.initialize()
        manager.initialize()
        manager.destroy()
        manager.destroy()

        assertEquals(1, engineManager.acquireCount)
        assertEquals(1, engineManager.releasedLeases.size)
        assertEquals(0, room.leaveCount)
        assertEquals(0, room.destroyCount)
    }

    @Test
    public fun `initialize preserves RTC engine failure`() = runTest {
        val expected = XmaxError(XmaxErrorCode.RTC_ERROR, "engine unavailable")
        val engineManager = FakeRtcEngineManager(
            engine = FakeRtcPlatformEngine(FakeRtcPlatformRoom()),
            acquireError = expected,
        )
        val manager = RtcManager(engineManager)

        val error = expectXmaxError { manager.initialize() }

        assertTrue(error === expected)
    }

    @Test
    public fun `configure video encoding validates and forwards configuration`() = runTest {
        val engine = FakeRtcPlatformEngine(FakeRtcPlatformRoom())
        val manager = RtcManager(FakeRtcEngineManager(engine))
        manager.initialize()
        val configuration = VideoEncodingConfiguration(
            width = 1_024,
            height = 768,
            frameRate = 30,
        )

        manager.configureVideoEncoding(configuration)

        assertEquals(listOf(configuration), engine.encodingConfigurations)
    }

    @Test
    public fun `configure video encoding requires engine and valid dimensions`() = runTest {
        val engine = FakeRtcPlatformEngine(FakeRtcPlatformRoom())
        val manager = RtcManager(FakeRtcEngineManager(engine))
        val configuration = VideoEncodingConfiguration(1_024, 768, 30)

        val inactiveError = expectXmaxError {
            manager.configureVideoEncoding(configuration)
        }
        assertEquals(XmaxErrorCode.RTC_ERROR, inactiveError.code)

        manager.initialize()
        val invalidError = expectXmaxError {
            manager.configureVideoEncoding(configuration.copy(width = 1_023))
        }
        assertEquals(XmaxErrorCode.INVALID_CONFIGURATION, invalidError.code)
        assertTrue(engine.encodingConfigurations.isEmpty())
    }

    @Test
    public fun `configure video encoding maps vendor failure`() = runTest {
        val engine = FakeRtcPlatformEngine(
            room = FakeRtcPlatformRoom(),
            encodingResult = -4,
        )
        val manager = RtcManager(FakeRtcEngineManager(engine))
        manager.initialize()

        val error = expectXmaxError {
            manager.configureVideoEncoding(VideoEncodingConfiguration(1_024, 768, 30))
        }

        assertEquals(XmaxErrorCode.RTC_ERROR, error.code)
        assertEquals("RTC setVideoEncoderConfig failed: -4", error.message)
    }

    @Test
    public fun `quality listener is retained across initialization and cleared on destroy`() = runTest {
        val engine = FakeRtcPlatformEngine(FakeRtcPlatformRoom())
        val manager = RtcManager(FakeRtcEngineManager(engine))
        val listener = QualityListenerStub()

        manager.setQualityListener(listener)
        manager.initialize()
        assertTrue(engine.qualityListener === listener)

        manager.destroy()
        assertEquals(null, engine.qualityListener)
    }

    @Test
    public fun `join requires initialized engine`() = runTest {
        val manager = RtcManager(
            FakeRtcEngineManager(FakeRtcPlatformEngine(FakeRtcPlatformRoom())),
        )

        val error = expectXmaxError {
            manager.joinRoom(validConfiguration())
        }

        assertEquals(XmaxErrorCode.RTC_ERROR, error.code)
        assertEquals("RTC Engine is not initialized", error.message)
    }

    @Test
    public fun `join normalizes configuration and waits for matching success`() = runTest {
        val room = FakeRtcPlatformRoom()
        val engine = FakeRtcPlatformEngine(room)
        val manager = RtcManager(FakeRtcEngineManager(engine))
        manager.initialize()

        val joining = async {
            manager.joinRoom(
                RoomJoinConfiguration(
                    roomId = " room-1 ",
                    userId = " user-1 ",
                    token = " token-1 ",
                ),
            )
        }
        runCurrent()

        assertFalse(joining.isCompleted)
        assertEquals(listOf("room-1"), engine.createdRoomIds)
        assertEquals(validConfiguration(), room.joinedConfiguration)

        room.emit(roomId = "another-room", joined = true)
        runCurrent()
        assertFalse(joining.isCompleted)

        room.emit(roomId = "room-1", joined = true)
        joining.await()

        assertEquals(0, room.leaveCount)
        assertEquals(0, room.destroyCount)
    }

    @Test
    public fun `join rejects a second active operation`() = runTest {
        val room = FakeRtcPlatformRoom()
        val manager = RtcManager(
            FakeRtcEngineManager(FakeRtcPlatformEngine(room)),
        )
        manager.initialize()
        val firstJoin = async { runCatching { manager.joinRoom(validConfiguration()) } }
        runCurrent()

        val error = expectXmaxError {
            manager.joinRoom(validConfiguration(roomId = "room-2"))
        }

        assertEquals(XmaxErrorCode.RTC_ERROR, error.code)
        assertEquals("RTC room is already active", error.message)
        manager.leaveRoom()
        assertEquals(
            XmaxErrorCode.CANCELLED,
            expectXmaxError { firstJoin.await().getOrThrow() }.code,
        )
    }

    @Test
    public fun `negative join result rolls room back`() = runTest {
        val room = FakeRtcPlatformRoom(joinResult = -3)
        val manager = RtcManager(
            FakeRtcEngineManager(FakeRtcPlatformEngine(room)),
        )
        manager.initialize()

        val error = expectXmaxError {
            manager.joinRoom(validConfiguration())
        }

        assertEquals(XmaxErrorCode.RTC_ERROR, error.code)
        assertEquals("RTC joinRoom failed: -3", error.message)
        assertEquals(0, room.leaveCount)
        assertEquals(1, room.destroyCount)
    }

    @Test
    public fun `thrown join failure is mapped and rolls room back`() = runTest {
        val room = FakeRtcPlatformRoom(
            joinError = IllegalStateException("native failure"),
        )
        val manager = RtcManager(
            FakeRtcEngineManager(FakeRtcPlatformEngine(room)),
        )
        manager.initialize()

        val error = expectXmaxError {
            manager.joinRoom(validConfiguration())
        }

        assertEquals(XmaxErrorCode.RTC_ERROR, error.code)
        assertEquals("RTC joinRoom failed: native failure", error.message)
        assertEquals(0, room.leaveCount)
        assertEquals(1, room.destroyCount)
    }

    @Test
    public fun `room failure callback includes vendor reason and rolls back`() = runTest {
        val room = FakeRtcPlatformRoom()
        val manager = RtcManager(
            FakeRtcEngineManager(FakeRtcPlatformEngine(room)),
        )
        manager.initialize()
        val joining = async { runCatching { manager.joinRoom(validConfiguration()) } }
        runCurrent()

        room.emit(roomId = "room-1", joined = false, reason = "INVALID_TOKEN")

        val error = expectXmaxError { joining.await().getOrThrow() }
        assertEquals(XmaxErrorCode.RTC_ERROR, error.code)
        assertEquals("RTC join room failed: INVALID_TOKEN", error.message)
        assertEquals(0, room.leaveCount)
        assertEquals(1, room.destroyCount)
    }

    @Test
    public fun `join timeout rolls room back`() = runTest {
        val room = FakeRtcPlatformRoom()
        val manager = RtcManager(
            engineManager = FakeRtcEngineManager(FakeRtcPlatformEngine(room)),
            joinTimeoutMillis = 1_000L,
        )
        manager.initialize()
        val joining = async { runCatching { manager.joinRoom(validConfiguration()) } }
        runCurrent()

        advanceTimeBy(1_001L)
        runCurrent()

        val error = expectXmaxError { joining.await().getOrThrow() }
        assertEquals(XmaxErrorCode.TIMEOUT, error.code)
        assertEquals("RTC join room timed out", error.message)
        assertEquals(0, room.leaveCount)
        assertEquals(1, room.destroyCount)
    }

    @Test
    public fun `leave cancels pending join and ignores late callback`() = runTest {
        val room = FakeRtcPlatformRoom()
        val manager = RtcManager(
            FakeRtcEngineManager(FakeRtcPlatformEngine(room)),
        )
        manager.initialize()
        val joining = async { runCatching { manager.joinRoom(validConfiguration()) } }
        runCurrent()

        manager.leaveRoom()
        room.emit(roomId = "room-1", joined = true)

        val error = expectXmaxError { joining.await().getOrThrow() }
        assertEquals(XmaxErrorCode.CANCELLED, error.code)
        assertEquals(0, room.leaveCount)
        assertEquals(1, room.destroyCount)
    }

    @Test
    public fun `destroy leaves active room before releasing engine`() = runTest {
        val room = FakeRtcPlatformRoom()
        val engineManager = FakeRtcEngineManager(FakeRtcPlatformEngine(room))
        val manager = RtcManager(engineManager)
        manager.initialize()
        val joining = async { manager.joinRoom(validConfiguration()) }
        runCurrent()
        room.emit(roomId = "room-1", joined = true)
        joining.await()

        manager.destroy()

        assertEquals(1, room.leaveCount)
        assertEquals(1, room.destroyCount)
        assertEquals(1, engineManager.releasedLeases.size)
    }

    @Test
    public fun `send room message requires active room and forwards message`() = runTest {
        val room = FakeRtcPlatformRoom()
        val manager = RtcManager(
            FakeRtcEngineManager(FakeRtcPlatformEngine(room)),
        )
        manager.initialize()

        val inactiveError = expectXmaxError {
            manager.sendRoomMessage("hello")
        }
        assertEquals(XmaxErrorCode.RTC_ERROR, inactiveError.code)
        assertEquals("RTC room is not joined", inactiveError.message)

        val joining = async { manager.joinRoom(validConfiguration()) }
        runCurrent()
        room.emit(roomId = "room-1", joined = true)
        joining.await()

        manager.sendRoomMessage("hello")

        assertEquals(listOf("hello"), room.sentMessages)
    }

    @Test
    public fun `send room message validates payload and vendor result`() = runTest {
        val room = FakeRtcPlatformRoom(sendMessageResult = -8L)
        val manager = RtcManager(
            FakeRtcEngineManager(FakeRtcPlatformEngine(room)),
        )
        manager.initialize()

        val emptyError = expectXmaxError {
            manager.sendRoomMessage("")
        }
        assertEquals(XmaxErrorCode.INVALID_CONFIGURATION, emptyError.code)

        val joining = async { manager.joinRoom(validConfiguration()) }
        runCurrent()
        room.emit(roomId = "room-1", joined = true)
        joining.await()

        val sendError = expectXmaxError {
            manager.sendRoomMessage("hello")
        }
        assertEquals(XmaxErrorCode.RTC_ERROR, sendError.code)
        assertEquals("RTC sendRoomMessage failed: -8", sendError.message)
    }

    @Test
    public fun `join rejects blank connection values`() = runTest {
        val manager = RtcManager(
            FakeRtcEngineManager(FakeRtcPlatformEngine(FakeRtcPlatformRoom())),
        )
        manager.initialize()

        val error = expectXmaxError {
            manager.joinRoom(validConfiguration(token = "  "))
        }

        assertEquals(XmaxErrorCode.RTC_ERROR, error.code)
        assertEquals("RTC room token cannot be empty", error.message)
    }

    private fun validConfiguration(
        roomId: String = "room-1",
        userId: String = "user-1",
        token: String = "token-1",
    ): RoomJoinConfiguration = RoomJoinConfiguration(roomId, userId, token)

    private suspend fun expectXmaxError(block: suspend () -> Unit): XmaxError = try {
        block()
        throw AssertionError("Expected XmaxError")
    } catch (error: XmaxError) {
        error
    }
}

private class FakeRtcEngineManager(
    engine: RtcPlatformEngine,
    private val acquireError: Throwable? = null,
) : RtcEngineManager(
    makeEngine = { engine },
    destroyEngine = {},
) {
    private val lease = RtcEngineLease(id = UUID.randomUUID(), engine = engine)

    var acquireCount: Int = 0
        private set
    val releasedLeases = mutableListOf<RtcEngineLease>()

    override suspend fun acquire(): RtcEngineLease {
        acquireCount += 1
        acquireError?.let { throw it }
        return lease
    }

    override fun release(lease: RtcEngineLease) {
        releasedLeases += lease
    }
}

private class FakeRtcPlatformEngine(
    private val room: RtcPlatformRoom,
    private val encodingResult: Int = 0,
) : RtcPlatformEngine {
    val createdRoomIds = mutableListOf<String>()
    val encodingConfigurations = mutableListOf<VideoEncodingConfiguration>()
    var qualityListener: RtcQualityListener? = null
        private set

    override fun configureVideoEncoding(configuration: VideoEncodingConfiguration): Int {
        encodingConfigurations += configuration
        return encodingResult
    }

    override fun setQualityListener(listener: RtcQualityListener?) {
        qualityListener = listener
    }

    override fun createRoom(roomId: String): RtcPlatformRoom {
        createdRoomIds += roomId
        return room
    }
}

private class QualityListenerStub : RtcQualityListener {
    override fun onNetworkQuality(
        uplink: RtcQualityLevel,
        downlink: RtcQualityLevel,
    ) = Unit

    override fun onPerformanceAlarm(
        limited: Boolean,
        suggestedWidth: Int,
        suggestedHeight: Int,
        suggestedFrameRate: Int,
    ) = Unit
}

private class FakeRtcPlatformRoom(
    private val listenerResult: Int = 0,
    private val joinResult: Int = 0,
    private val joinError: Throwable? = null,
    private val sendMessageResult: Long = 1L,
) : RtcPlatformRoom {
    private var listener: ((String, Boolean, String?) -> Unit)? = null

    var joinedConfiguration: RoomJoinConfiguration? = null
        private set
    var leaveCount: Int = 0
        private set
    var destroyCount: Int = 0
        private set
    val sentMessages = mutableListOf<String>()

    override fun setEventListener(
        listener: (String, Boolean, String?) -> Unit,
    ): Int {
        this.listener = listener
        return listenerResult
    }

    override fun join(configuration: RoomJoinConfiguration): Int {
        joinedConfiguration = configuration
        joinError?.let { throw it }
        return joinResult
    }

    override fun leave(): Int {
        leaveCount += 1
        return 0
    }

    override fun sendRoomMessage(message: String): Long {
        sentMessages += message
        return sendMessageResult
    }

    override fun destroy() {
        destroyCount += 1
    }

    fun emit(
        roomId: String,
        joined: Boolean,
        reason: String? = null,
    ) {
        listener?.invoke(roomId, joined, reason)
    }
}
