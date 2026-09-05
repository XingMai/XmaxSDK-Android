package ai.xmax.sdk.foundation.rtc

import ai.xmax.sdk.AudioFrame
import ai.xmax.sdk.VideoFormat
import ai.xmax.sdk.VideoFrame
import ai.xmax.sdk.VideoFramePlane
import ai.xmax.sdk.VideoPixelFormat
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.Before
import org.junit.After
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
    @Before fun setupMain() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun resetMain() { Dispatchers.resetMain() }
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
    public fun `external video source is forwarded and vendor failures are mapped`() = runTest {
        val engine = FakeRtcPlatformEngine(FakeRtcPlatformRoom())
        val manager = RtcManager(FakeRtcEngineManager(engine))
        manager.initialize()

        manager.useExternalVideoSource()

        assertEquals(1, engine.externalVideoSourceCount)

        val failingManager = RtcManager(
            FakeRtcEngineManager(
                FakeRtcPlatformEngine(
                    room = FakeRtcPlatformRoom(),
                    externalVideoSourceResult = -5,
                ),
            ),
        )
        failingManager.initialize()
        val error = expectXmaxError { failingManager.useExternalVideoSource() }
        assertEquals(XmaxErrorCode.RTC_ERROR, error.code)
        assertEquals("RTC setVideoSourceType failed: -5", error.message)
    }

    @Test
    public fun `external audio source lifecycle is forwarded`() = runTest {
        val engine = FakeRtcPlatformEngine(FakeRtcPlatformRoom())
        val manager = RtcManager(FakeRtcEngineManager(engine))
        manager.initialize()

        manager.startExternalAudioSource()
        manager.stopExternalAudioSource()

        assertEquals(1, engine.externalAudioStartCount)
        assertEquals(1, engine.externalAudioStopCount)
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
        assertTrue(firstJoin.await().exceptionOrNull() is kotlinx.coroutines.CancellationException)
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

        assertTrue(joining.await().exceptionOrNull() is kotlinx.coroutines.CancellationException)
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
    public fun `external frames require engine and are forwarded`() = runTest {
        val engine = FakeRtcPlatformEngine(FakeRtcPlatformRoom())
        val manager = RtcManager(FakeRtcEngineManager(engine))
        val videoFrame = sampleVideoFrame()
        val audioFrame = AudioFrame(ByteArray(960), timestampUs = 10_000)

        assertEquals(
            XmaxErrorCode.RTC_ERROR,
            expectXmaxError {
                manager.pushExternalVideoFrame(videoFrame, seiData = null)
            }.code,
        )

        manager.initialize()
        manager.pushExternalVideoFrame(videoFrame, "task-id".encodeToByteArray())
        manager.pushExternalAudioFrame(audioFrame)

        assertEquals(
            listOf(videoFrame to "task-id".encodeToByteArray().toList()),
            engine.pushedVideoFrames,
        )
        assertEquals(listOf(audioFrame), engine.pushedAudioFrames)
    }

    @Test
    public fun `local media publication requires room and forwards state`() = runTest {
        val room = FakeRtcPlatformRoom()
        val manager = RtcManager(FakeRtcEngineManager(FakeRtcPlatformEngine(room)))
        manager.initialize()

        assertEquals(
            XmaxErrorCode.RTC_ERROR,
            expectXmaxError { manager.publishLocalVideo() }.code,
        )
        manager.unpublishLocalVideo()
        manager.unpublishLocalAudio()
        assertTrue(room.publishedVideoStates.isEmpty())
        assertTrue(room.publishedAudioStates.isEmpty())

        val joining = async { manager.joinRoom(validConfiguration()) }
        runCurrent()
        room.emit(roomId = "room-1", joined = true)
        joining.await()

        manager.publishLocalVideo()
        manager.publishLocalAudio()
        manager.unpublishLocalAudio()
        manager.unpublishLocalVideo()

        assertEquals(listOf(true, false), room.publishedVideoStates)
        assertEquals(listOf(true, false), room.publishedAudioStates)
    }

    @Test
    public fun `local publication maps vendor failure`() = runTest {
        val room = FakeRtcPlatformRoom(publishVideoResult = -5)
        val manager = RtcManager(FakeRtcEngineManager(FakeRtcPlatformEngine(room)))
        manager.initialize()
        val joining = async { manager.joinRoom(validConfiguration()) }
        runCurrent()
        room.emit(roomId = "room-1", joined = true)
        joining.await()

        val error = expectXmaxError { manager.publishLocalVideo() }

        assertEquals(XmaxErrorCode.RTC_ERROR, error.code)
        assertEquals("RTC publishStreamVideo failed: -5", error.message)
    }

    @Test
    public fun `remote subscriptions normalize user and forward state`() = runTest {
        val room = FakeRtcPlatformRoom()
        val manager = RtcManager(FakeRtcEngineManager(FakeRtcPlatformEngine(room)))
        manager.initialize()
        val joining = async { manager.joinRoom(validConfiguration()) }
        runCurrent()
        room.emit(roomId = "room-1", joined = true)
        joining.await()

        manager.subscribeRemoteVideo(" bot-user ", subscribe = true)
        manager.subscribeRemoteVideo("bot-user", subscribe = false)
        manager.subscribeRemoteAudio(" bot-user ", subscribe = true)
        manager.subscribeRemoteAudio("bot-user", subscribe = false)

        assertEquals(
            listOf("bot-user" to true, "bot-user" to false),
            room.remoteVideoSubscriptions,
        )
        assertEquals(
            listOf("bot-user" to true, "bot-user" to false),
            room.remoteAudioSubscriptions,
        )
        assertEquals(
            XmaxErrorCode.INVALID_CONFIGURATION,
            expectXmaxError {
                manager.subscribeRemoteVideo("  ", subscribe = true)
            }.code,
        )
    }

    @Test
    public fun `remote audio volume validates and resolves stream ID`() = runTest {
        val room = FakeRtcPlatformRoom().apply {
            remoteStreamIds["bot-user"] = "bot-stream"
        }
        val engine = FakeRtcPlatformEngine(room)
        val manager = RtcManager(FakeRtcEngineManager(engine))
        manager.initialize()

        manager.setRemoteAudioVolume(volume = 25, userId = " bot-user ")
        assertEquals(listOf("bot-user" to 25), engine.remoteAudioVolumes)

        val joining = async { manager.joinRoom(validConfiguration()) }
        runCurrent()
        room.emit(roomId = "room-1", joined = true)
        joining.await()
        manager.setRemoteAudioVolume(volume = 35, userId = "bot-user")

        assertEquals(
            listOf("bot-user" to 25, "bot-stream" to 35),
            engine.remoteAudioVolumes,
        )
        assertEquals(
            XmaxErrorCode.INVALID_CONFIGURATION,
            expectXmaxError {
                manager.setRemoteAudioVolume(volume = 101, userId = "bot-user")
            }.code,
        )
    }

    @Test
    public fun `RTC events reach listener only for active room`() = runTest {
        val room = FakeRtcPlatformRoom()
        val engine = FakeRtcPlatformEngine(room)
        val manager = RtcManager(
            engineManager = FakeRtcEngineManager(engine),
            callbackScope = this,
        )
        val listener = EventListenerStub()
        manager.setEventListener(listener)
        manager.initialize()
        val joining = async { manager.joinRoom(validConfiguration()) }
        runCurrent()
        room.emit(roomId = "room-1", joined = true)
        joining.await()

        engine.eventListener?.onRemoteVideoPublished("bot-user", true)
        engine.eventListener?.onSeiMessageReceived(
            RemoteStream(roomId = "another-room", userId = "bot-user"),
            "ignored",
        )
        engine.eventListener?.onSeiMessageReceived(
            RemoteStream(roomId = "room-1", userId = "bot-user"),
            "task-id",
        )
        runCurrent()

        assertEquals(listOf("bot-user" to true), listener.remoteVideoEvents)
        assertEquals(listOf("room-1:bot-user" to "task-id"), listener.seiEvents)

        manager.leaveRoom()
        engine.eventListener?.onRemoteVideoPublished("bot-user", false)
        engine.eventListener?.onSeiMessageReceived(
            RemoteStream(roomId = "room-1", userId = "bot-user"),
            "late",
        )
        runCurrent()

        assertEquals(1, listener.remoteVideoEvents.size)
        assertEquals(1, listener.seiEvents.size)
    }

    @Test
    public fun `cleared event listener ignores queued callback`() = runTest {
        val room = FakeRtcPlatformRoom()
        val engine = FakeRtcPlatformEngine(room)
        val manager = RtcManager(
            engineManager = FakeRtcEngineManager(engine),
            callbackScope = this,
        )
        val listener = EventListenerStub()
        manager.setEventListener(listener)
        manager.initialize()
        val joining = async { manager.joinRoom(validConfiguration()) }
        runCurrent()
        room.emit(roomId = "room-1", joined = true)
        joining.await()

        engine.eventListener?.onRemoteVideoPublished("bot-user", true)
        manager.setEventListener(null)
        runCurrent()

        assertTrue(listener.remoteVideoEvents.isEmpty())
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

    private fun sampleVideoFrame(): VideoFrame = VideoFrame(
        format = VideoFormat(1, 1, VideoPixelFormat.RGBA),
        timestampUs = 0,
        planes = listOf(VideoFramePlane(ByteArray(4), stride = 4)),
    )

    @Test fun `live room termination is reported while reconnect warning is ignored`() = runTest {
        val room = FakeRtcPlatformRoom()
        val engine = FakeRtcPlatformEngine(room)
        val manager = RtcManager(FakeRtcEngineManager(engine), callbackScope = this)
        val errors = mutableListOf<XmaxError>()
        val listener = object : RtcEventListener {
            override fun onRemoteVideoPublished(userId: String, published: Boolean) = Unit
            override fun onSeiMessageReceived(stream: RemoteStream, message: String) = Unit
            override fun onRoomTerminated(roomId: String, error: XmaxError) { errors += error }
        }
        manager.setEventListener(listener)
        manager.initialize()
        val join = async { manager.joinRoom(validConfiguration()) }
        runCurrent(); room.emit("room-1", true); join.await()
        room.emit("room-1", false, "JOIN_ROOM_FAILED"); runCurrent()
        assertTrue(errors.isEmpty())
        room.emit("room-1", false, "KICKED_OUT"); runCurrent()
        assertEquals(1, errors.size)
        manager.leaveRoom()
        room.emit("room-1", false, "KICKED_OUT"); runCurrent()
        assertEquals(1, errors.size)
        manager.destroy()
    }

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
    private val externalVideoSourceResult: Int = 0,
    private val remoteAudioVolumeResult: Int = 0,
) : RtcPlatformEngine {
    val createdRoomIds = mutableListOf<String>()
    val encodingConfigurations = mutableListOf<VideoEncodingConfiguration>()
    val pushedVideoFrames = mutableListOf<Pair<VideoFrame, List<Byte>?>>()
    val pushedAudioFrames = mutableListOf<AudioFrame>()
    var externalVideoSourceCount = 0
    var externalAudioStartCount = 0
    var externalAudioStopCount = 0
    val remoteAudioVolumes = mutableListOf<Pair<String, Int>>()
    var eventListener: RtcEventListener? = null
        private set
    var qualityListener: RtcQualityListener? = null
        private set

    override fun configureVideoEncoding(configuration: VideoEncodingConfiguration): Int {
        encodingConfigurations += configuration
        return encodingResult
    }

    override fun pushExternalVideoFrame(frame: VideoFrame, seiData: ByteArray?) {
        pushedVideoFrames += frame to seiData?.toList()
    }

    override fun pushExternalAudioFrame(frame: AudioFrame) {
        pushedAudioFrames += frame
    }

    override fun useExternalVideoSource(): Int {
        externalVideoSourceCount += 1
        return externalVideoSourceResult
    }

    override fun startExternalAudioSource(): Int {
        externalAudioStartCount += 1
        return 0
    }

    override fun stopExternalAudioSource(): Int {
        externalAudioStopCount += 1
        return 0
    }

    override fun startVideoCapture(width: Int, height: Int, frameRate: Int): Int = 0

    override fun stopVideoCapture(): Int = 0

    override fun switchCamera(position: ai.xmax.sdk.CameraPosition): Int = 0

    override fun bindLocalVideo(
        view: android.view.View,
        contentMode: ai.xmax.sdk.VideoContentMode,
    ): Int = 0

    override fun unbindLocalVideo(): Int = 0

    override fun bindRemoteVideo(
        userId: String,
        view: android.view.View,
        contentMode: ai.xmax.sdk.VideoContentMode,
    ): Int = 0

    override fun unbindRemoteVideo(userId: String): Int = 0

    override fun setCameraPreviewReadyListener(listener: (() -> Unit)?) = Unit

    override fun setRemoteVideoFrameReadyListener(
        listener: ((RemoteStream, Int, Int) -> Unit)?,
    ) = Unit

    override fun setRemoteAudioVolume(streamId: String, volume: Int): Int {
        remoteAudioVolumes += streamId to volume
        return remoteAudioVolumeResult
    }

    override fun setEventListener(listener: RtcEventListener?) {
        eventListener = listener
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

private class EventListenerStub : RtcEventListener {
    val remoteVideoEvents = mutableListOf<Pair<String, Boolean>>()
    val seiEvents = mutableListOf<Pair<String, String>>()

    override fun onRemoteVideoPublished(
        userId: String,
        published: Boolean,
    ) {
        remoteVideoEvents += userId to published
    }

    override fun onSeiMessageReceived(
        stream: RemoteStream,
        message: String,
    ) {
        seiEvents += stream.key to message
    }
}

private class FakeRtcPlatformRoom(
    private val listenerResult: Int = 0,
    private val joinResult: Int = 0,
    private val joinError: Throwable? = null,
    private val sendMessageResult: Long = 1L,
    private val publishVideoResult: Int = 0,
    private val publishAudioResult: Int = 0,
    private val subscribeVideoResult: Int = 0,
    private val subscribeAudioResult: Int = 0,
) : RtcPlatformRoom {
    private var listener: ((String, Boolean, String?) -> Unit)? = null

    var joinedConfiguration: RoomJoinConfiguration? = null
        private set
    var leaveCount: Int = 0
        private set
    var destroyCount: Int = 0
        private set
    val sentMessages = mutableListOf<String>()
    val publishedVideoStates = mutableListOf<Boolean>()
    val publishedAudioStates = mutableListOf<Boolean>()
    val remoteVideoSubscriptions = mutableListOf<Pair<String, Boolean>>()
    val remoteAudioSubscriptions = mutableListOf<Pair<String, Boolean>>()
    val remoteStreamIds = mutableMapOf<String, String>()

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

    override fun publishLocalVideo(publish: Boolean): Int {
        publishedVideoStates += publish
        return publishVideoResult
    }

    override fun publishLocalAudio(publish: Boolean): Int {
        publishedAudioStates += publish
        return publishAudioResult
    }

    override fun subscribeRemoteVideo(userId: String, subscribe: Boolean): Int {
        remoteVideoSubscriptions += userId to subscribe
        return subscribeVideoResult
    }

    override fun subscribeRemoteAudio(userId: String, subscribe: Boolean): Int {
        remoteAudioSubscriptions += userId to subscribe
        return subscribeAudioResult
    }

    override fun resolveRemoteStreamId(userId: String): String = remoteStreamIds[userId] ?: userId

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
