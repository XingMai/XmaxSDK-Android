package ai.xmax.sdk.stream

import ai.xmax.sdk.RealtimeContext
import ai.xmax.sdk.RealtimeNetworkQualityListener
import ai.xmax.sdk.RealtimeNetworkQuality
import ai.xmax.sdk.RealtimePerformanceAlarmListener
import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.VideoFormat
import ai.xmax.sdk.VideoFrame
import ai.xmax.sdk.UplinkVideoStatisticsListener
import ai.xmax.sdk.DownlinkVideoStatisticsListener
import ai.xmax.sdk.UplinkVideoStatistics
import ai.xmax.sdk.DownlinkVideoStatistics
import ai.xmax.sdk.VideoFramePlane
import ai.xmax.sdk.VideoPixelFormat
import ai.xmax.sdk.foundation.rtc.RemoteStream
import ai.xmax.sdk.service.realtime.RealtimeSessionConnection
import ai.xmax.sdk.stream.encoding.EncodingControlling
import ai.xmax.sdk.stream.quality.QualityControlling
import ai.xmax.sdk.stream.quality.QualityController
import ai.xmax.sdk.stream.room.RoomController
import ai.xmax.sdk.stream.room.RoomHeartbeat
import ai.xmax.sdk.stream.room.RtcManagingCall
import ai.xmax.sdk.stream.room.RtcManagingStub
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StreamControllerTest {
    @Test
    fun `downlink updates require a current ready generation and valid operation`() = runTest {
        val rtc = RtcManagingStub()
        val controller = StreamController(
            rtcManager = rtc,
            roomController = RoomController(rtc, RoomHeartbeat(rtc, sleeper = { awaitCancellation() }, scope = backgroundScope)),
            qualityController = QualityController(rtc, callbackScope = backgroundScope),
            generationScope = backgroundScope,
            renderDispatcher = StandardTestDispatcher(testScheduler),
        )
        val format = RealtimeVideoFormat(1024, 1920, 30)
        val target = RealtimeVideoFormat(640, 480, 30)
        fun changes() = rtc.calls.filterIsInstance<RtcManagingCall.SendRoomMessage>()
            .map { org.json.JSONObject(it.message) }
            .filter { it.getString("event") == "change_target_size" }

        controller.connect(RealtimeSessionConnection("room", "user", "token", "bot"), false) {}
        assertTrue(runCatching { controller.updateDownlinkVideoResolution("task", target) {} }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        val confirmation = controller.beginGeneration("task", format, RealtimeContext("prompt"), targetVideoFormat = target)
        assertTrue(runCatching { controller.updateDownlinkVideoResolution("task", target) {} }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        rtc.emitRemoteVideoPublished("bot", true, roomId = "room")
        confirmation.await()
        controller.updateDownlinkVideoResolution("task", target) {}
        assertEquals(1, changes().size)
        assertEquals(100, changes().single().getJSONObject("params").getInt("min_bitrate"))

        assertTrue(runCatching { controller.updateDownlinkVideoResolution("old-task", target) {} }.isFailure)
        assertTrue(runCatching {
            controller.updateDownlinkVideoResolution("task", target) { throw kotlinx.coroutines.CancellationException("stale operation") }
        }.isFailure)

        val stopping = async(start = CoroutineStart.UNDISPATCHED) { controller.stopGeneration("task") }
        assertTrue(runCatching { controller.updateDownlinkVideoResolution("task", target) {} }.isFailure)
        stopping.await()
        assertTrue(runCatching { controller.updateDownlinkVideoResolution("task", target) {} }.isFailure)

        controller.beginGeneration("new-task", format, RealtimeContext("prompt")).await()
        assertTrue(runCatching { controller.updateDownlinkVideoResolution("task", target) {} }.isFailure)
        controller.updateDownlinkVideoResolution("new-task", target) {}
        controller.disconnect()
        assertTrue(runCatching { controller.updateDownlinkVideoResolution("new-task", target) {} }.isFailure)
        assertEquals(2, changes().size)
    }

    @Test
    fun `stream statistics follow local publication and active result lifecycle`() = runTest {
        val rtc = RtcManagingStub()
        val controller = StreamController(
            rtcManager = rtc,
            roomController = RoomController(rtc, RoomHeartbeat(rtc, sleeper = { awaitCancellation() }, scope = backgroundScope)),
            qualityController = QualityController(rtc, callbackScope = backgroundScope),
            generationScope = backgroundScope,
            renderDispatcher = StandardTestDispatcher(testScheduler),
        )
        val local = mutableListOf<UplinkVideoStatistics?>()
        val remote = mutableListOf<DownlinkVideoStatistics?>()
        controller.setUplinkVideoStatisticsListener(local::add)
        controller.setDownlinkVideoStatisticsListener(remote::add)
        controller.connect(RealtimeSessionConnection("room", "user", "token", "bot"), false) {}
        val confirmation = controller.beginGeneration("task", RealtimeVideoFormat(1024, 1920, 30), RealtimeContext("prompt"))
        rtc.emitRemoteVideoPublished("bot", true, roomId = "room")
        confirmation.await()
        rtc.emitUplinkVideoStatistics(UplinkVideoStatistics(bitrateKbps = 1234))
        rtc.emitDownlinkVideoStatistics(DownlinkVideoStatistics("bot", renderFrameRate = 30))
        rtc.emitDownlinkVideoStatistics(DownlinkVideoStatistics("other", renderFrameRate = 15))
        runCurrent()
        assertEquals(1234, local.last()?.bitrateKbps)
        assertEquals(30, remote.last()?.renderFrameRate)

        controller.stopGeneration("task")
        rtc.emitDownlinkVideoStatistics(DownlinkVideoStatistics("bot", renderFrameRate = 15))
        runCurrent()
        assertEquals(null, remote.last())
        controller.disconnect()
        rtc.emitUplinkVideoStatistics(UplinkVideoStatistics(bitrateKbps = 99))
        runCurrent()
        assertEquals(null, local.last())

        controller.connect(RealtimeSessionConnection("room-2", "user", "token", "bot"), false, false) {}
        rtc.emitUplinkVideoStatistics(UplinkVideoStatistics(bitrateKbps = 99))
        runCurrent()
        assertEquals(null, local.last())
        controller.disconnect()
    }

    @Test
    fun `already published bot cannot confirm until start signaling is sent`() = runTest {
        val rtc = RtcManagingStub()
        val sent = kotlinx.coroutines.CompletableDeferred<Unit>()
        val transport = object : ai.xmax.sdk.foundation.rtc.RtcManaging by rtc {
            override suspend fun awaitPendingMessages() { sent.await() }
        }
        val events = mutableListOf<RemoteStream?>()
        val controller = StreamController(
            rtcManager = transport,
            roomController = RoomController(transport, RoomHeartbeat(transport, sleeper = { awaitCancellation() }, scope = backgroundScope)),
            encodingController = EncodingStub,
            qualityController = QualityStub,
            generationScope = backgroundScope,
            renderDispatcher = StandardTestDispatcher(testScheduler),
            remoteStreamListener = events::add,
        )
        controller.connect(RealtimeSessionConnection("room", "user", "token", "bot"), false) {}
        rtc.emitRemoteVideoPublished("bot", true, roomId = "room")
        val starting = async { controller.beginGeneration("task", RealtimeVideoFormat(1024, 1920, 30), RealtimeContext("prompt")) }
        runCurrent()
        rtc.emitRemoteVideoPublished("bot", true, roomId = "room")
        assertFalse(starting.isCompleted)
        assertTrue(events.isEmpty())
        sent.complete(Unit)
        starting.await().await()
        assertEquals(listOf(RemoteStream("room", "bot")), events)
        rtc.emitRemoteVideoPublished("bot", false, roomId = "room")
        rtc.emitRemoteVideoPublished("bot", true, roomId = "room")
        assertEquals(listOf(RemoteStream("room", "bot"), null, RemoteStream("room", "bot")), events)
        controller.disconnect()
    }

    @Test
    fun `network video joins without publishing and completes only after readiness once`() = runTest {
        val rtc = RtcManagingStub()
        val controller = StreamController(
            rtcManager = rtc,
            roomController = RoomController(rtc, RoomHeartbeat(rtc, sleeper = { awaitCancellation() }, scope = backgroundScope)),
            encodingController = EncodingStub, qualityController = QualityStub,
            generationScope = backgroundScope, renderDispatcher = StandardTestDispatcher(testScheduler),
        )
        controller.connect(RealtimeSessionConnection("room", "user", "token", "bot"), false, false) {}
        assertFalse(rtc.calls.contains(RtcManagingCall.PublishLocalVideo))
        assertFalse(rtc.calls.contains(RtcManagingCall.PublishLocalAudio))
        val context = RealtimeContext("prompt", "image", ai.xmax.sdk.RealtimeReferenceVideo("https://example.test/video.mp4"))
        val confirmation = controller.beginGeneration("task?os=android", RealtimeVideoFormat(704, 1280, 24), context)
        var completions = 0
        fun finish(room: String = "room", user: String = "bot", uid: String = "task?os=android") {
            controller.onUserMessageReceived(RemoteStream(room, user), """{"event":"video_stopped","uid":"$uid"}""")
        }
        controller.onUserMessageReceived(RemoteStream("room", "bot"), "invalid json")
        finish(room = "old-room")
        finish(user = "other-user")
        finish(uid = "old-task")
        finish()
        runCurrent()
        assertEquals(0, completions)
        assertFalse(confirmation.isCompleted)
        rtc.emitRemoteVideoPublished("bot", true, roomId = "room")
        rtc.emitSeiMessage(RemoteStream("room", "bot"), "task?os=android")
        confirmation.await()
        controller.activateNetworkVideoCompletion { completions++ }
        finish()
        runCurrent()
        assertEquals(1, completions)
        assertTrue(rtc.calls.contains(RtcManagingCall.SubscribeRemoteVideo("bot", true)))
        assertFalse(rtc.calls.any { it is RtcManagingCall.SubscribeRemoteAudio })
        finish()
        runCurrent()
        assertEquals(1, completions)
        controller.disconnect()
        finish()
        runCurrent()
        assertEquals(1, completions)
    }

    @Test
    fun `network completion ignores wrong events and queued callback after stop`() = runTest {
        val rtc = RtcManagingStub()
        // 停止直接在当前线程清除任务，完成通知仍排队到测试主调度器。
        val controller = StreamController(
            rtcManager = rtc,
            roomController = RoomController(rtc, RoomHeartbeat(rtc, sleeper = { awaitCancellation() }, scope = backgroundScope)),
            encodingController = EncodingStub, qualityController = QualityStub,
            generationScope = backgroundScope, renderDispatcher = StandardTestDispatcher(testScheduler),
        )
        controller.connect(RealtimeSessionConnection("room", "user", "token", "bot"), false, false) {}
        var completions = 0
        val context = RealtimeContext("prompt", referenceVideo = ai.xmax.sdk.RealtimeReferenceVideo("https://example.test/video.mp4"))
        controller.beginGeneration("first", RealtimeVideoFormat(704, 1280, 24), context)
        rtc.emitSeiMessage(RemoteStream("room", "bot"), "first")
        controller.activateNetworkVideoCompletion { completions++ }
        controller.onUserMessageReceived(RemoteStream("room", "bot"), """{"event":"video_stopped","uid":"wrong"}""")
        controller.onUserMessageReceived(RemoteStream("room", "wrong"), """{"event":"video_stopped","uid":"first"}""")
        runCurrent()
        assertEquals(0, completions)
        // 先排队停止，再排队完成通知，模拟用户返回与服务端结束同时发生。
        val stop = async { controller.stopGeneration("first") }
        controller.onUserMessageReceived(RemoteStream("room", "bot"), """{"event":"video_stopped","uid":"first"}""")
        stop.await()
        runCurrent()
        assertEquals(0, completions)
        controller.beginGeneration("second", RealtimeVideoFormat(704, 1280, 24), context)
        rtc.emitSeiMessage(RemoteStream("room", "bot"), "second")
        controller.activateNetworkVideoCompletion { completions++ }
        controller.onUserMessageReceived(RemoteStream("room", "bot"), """{"event":"video_stopped","uid":"first"}""")
        runCurrent()
        assertEquals(0, completions)
        controller.onUserMessageReceived(RemoteStream("room", "bot"), """{"event":"video_stopped","uid":"second"}""")
        runCurrent()
        assertEquals(1, completions)
        controller.disconnect()
    }

    @Test
    fun `local generation does not dispatch network completion`() = runTest {
        val rtc = RtcManagingStub()
        val controller = StreamController(
            rtcManager = rtc,
            roomController = RoomController(rtc, RoomHeartbeat(rtc, sleeper = { awaitCancellation() }, scope = backgroundScope)),
            encodingController = EncodingStub, qualityController = QualityStub,
            generationScope = backgroundScope, renderDispatcher = StandardTestDispatcher(testScheduler),
        )
        controller.connect(RealtimeSessionConnection("room", "user", "token", "bot"), false) {}
        controller.beginGeneration("task", RealtimeVideoFormat(704, 1280, 24), RealtimeContext("prompt"))
        rtc.emitRemoteVideoPublished("bot", true, roomId = "room")
        var completions = 0
        controller.activateNetworkVideoCompletion { completions++ }
        controller.onUserMessageReceived(RemoteStream("room", "bot"), """{"event":"video_stopped","uid":"task"}""")
        runCurrent()
        assertEquals(0, completions)
        assertTrue(rtc.calls.contains(RtcManagingCall.PublishLocalVideo))
        controller.disconnect()
    }

    @Test
    fun `remote publication at join completion subscribes without losing room context`() = runTest {
        lateinit var rtc: RtcManagingStub
        rtc = RtcManagingStub(joinRoomHandler = {
            rtc.emitRemoteVideoPublished("bot-id", true)
        })
        val controller = StreamController(
            rtcManager = rtc,
            roomController = RoomController(rtc, RoomHeartbeat(rtc, sleeper = { awaitCancellation() }, scope = backgroundScope)),
            encodingController = EncodingStub,
            qualityController = QualityStub,
            generationScope = backgroundScope,
            renderDispatcher = StandardTestDispatcher(testScheduler),
        )

        controller.connect(RealtimeSessionConnection("room-id", "user", "token", "bot-id"), false) {}
        assertTrue(rtc.calls.contains(RtcManagingCall.SubscribeRemoteVideo("bot-id", true)))

        rtc.emitRemoteVideoPublished("bot-id", false, roomId = "old-room")
        rtc.emitRemoteVideoPublished("bot-id", true)
        assertEquals(1, rtc.calls.count { it == RtcManagingCall.SubscribeRemoteVideo("bot-id", true) })
        controller.disconnect()
    }

    @Test
    fun `startup timing waits for target bot publication`() = runTest {
        val rtc = RtcManagingStub()
        val controller = StreamController(
            rtcManager = rtc,
            roomController = RoomController(rtc, RoomHeartbeat(rtc, sleeper = { awaitCancellation() }, scope = backgroundScope)),
            encodingController = EncodingStub,
            qualityController = QualityStub,
            generationScope = backgroundScope,
            renderDispatcher = StandardTestDispatcher(testScheduler),
        )
        val logs = mutableListOf<String>()
        ai.xmax.sdk.RealtimeTiming({ testScheduler.currentTime * 1_000_000 }, logs::add).measure {
            val attempt = kotlinx.coroutines.currentCoroutineContext()[ai.xmax.sdk.RealtimeTiming.Attempt]!!
            attempt.mark(ai.xmax.sdk.RealtimeTiming.Stage.CONNECTION_START)
            controller.connect(RealtimeSessionConnection("room", "user", "token", "bot"), false) {}
            attempt.mark(ai.xmax.sdk.RealtimeTiming.Stage.CONNECTION_END)
            val confirmation = controller.beginGeneration("task", RealtimeVideoFormat(704, 1280, 24), RealtimeContext("prompt"))
            kotlinx.coroutines.delay(10)
            rtc.emitSeiMessage(RemoteStream("room", "bot"), "old-task")
            rtc.emitSeiMessage(RemoteStream("room", "other-bot"), "task")
            assertFalse(confirmation.isCompleted)
            kotlinx.coroutines.delay(20)
            rtc.emitRemoteVideoPublished("bot", true, roomId = "room")
            confirmation.await()
            kotlinx.coroutines.delay(5)
            attempt.finish("task")
        }
        assertTrue(logs.single().contains("RTC 房间连接：0.0 ms"))
        assertTrue(logs.single().contains("等待生成结果流确认：30.0 ms"))
        assertTrue(logs.single().contains("结果流确认到首帧就绪：5.0 ms"))
        controller.disconnect()
    }

    @Test
    fun `disconnect awaits presentation cleanup before unsubscribing even when its caller is cancelled`() = runTest {
        val rtc = RtcManagingStub()
        var renderCleared = false
        val controller = StreamController(
            rtcManager = rtc,
            roomController = RoomController(
                rtc,
                RoomHeartbeat(rtc, sleeper = { awaitCancellation() }, scope = this),
            ),
            encodingController = EncodingStub,
            qualityController = QualityStub,
            remoteStreamListener = { stream ->
                if (stream == null) {
                    assertFalse(rtc.calls.contains(RtcManagingCall.UnpublishLocalVideo))
                    assertFalse(rtc.calls.contains(RtcManagingCall.SubscribeRemoteVideo("bot-id", false)))
                    renderCleared = true
                }
            },
            generationScope = this,
            renderDispatcher = StandardTestDispatcher(testScheduler),
        )
        controller.connect(RealtimeSessionConnection(
            roomId = "room-id", userId = "user-id", token = "token", botName = "bot-id",
        ), includeLocalAudio = false) {}
        val confirmation = controller.beginGeneration(
            "task-id", RealtimeVideoFormat(704, 1280, 24), RealtimeContext("prompt"),
        )
        rtc.emitRemoteVideoPublished("bot-id", true)
        rtc.emitSeiMessage(RemoteStream("room-id", "bot-id"), "task-id")
        confirmation.await()

        val disconnect = async(start = CoroutineStart.UNDISPATCHED) { controller.disconnect() }
        assertFalse(renderCleared)
        assertFalse(disconnect.isCompleted)
        assertFalse(rtc.calls.contains(RtcManagingCall.UnpublishLocalVideo))
        disconnect.cancel()
        runCurrent()
        disconnect.join()
        assertTrue(renderCleared)
        assertTrue(disconnect.isCancelled)
        assertTrue(rtc.calls.contains(RtcManagingCall.SubscribeRemoteVideo("bot-id", false)))
        assertTrue(rtc.calls.contains(RtcManagingCall.UnpublishLocalVideo))
    }

    @Test
    fun `remote audio volume rounds like iOS and applies before subscription`() = runTest {
        val rtc = RtcManagingStub()
        val controller = StreamController(
            rtcManager = rtc,
            roomController = RoomController(
                rtc,
                RoomHeartbeat(rtc, sleeper = { awaitCancellation() }, scope = this),
            ),
            encodingController = EncodingStub,
            qualityController = QualityStub,
            generationScope = this,
            renderDispatcher = StandardTestDispatcher(testScheduler),
        )
        val connection = RealtimeSessionConnection(
            roomId = "room-id",
            userId = "user-id",
            token = "room-token",
            botName = "bot-id",
        )

        controller.setRemoteAudioVolume(0.455f)
        controller.connect(connection, includeLocalAudio = false) {}
        val confirmation = controller.beginGeneration(
            taskId = "task-id",
            videoFormat = RealtimeVideoFormat(704, 1280, 24),
            context = RealtimeContext("prompt"),
        )
        rtc.emitRemoteVideoPublished("bot-id", true)
        rtc.emitSeiMessage(RemoteStream("room-id", "bot-id"), "task-id")
        confirmation.await()
        controller.activateRemoteAudio()

        val volumeCall = RtcManagingCall.SetRemoteAudioVolume(46, "bot-id")
        assertTrue(rtc.calls.contains(volumeCall))
        assertTrue(
            rtc.calls.indexOf(volumeCall) <
                rtc.calls.indexOf(RtcManagingCall.SubscribeRemoteAudio("bot-id", true)),
        )
        controller.disconnect()
    }

    @Test
    fun `camera stream can start another generation after stopping`() = runTest {
        val rtc = RtcManagingStub()
        val remoteEvents = mutableListOf<RemoteStream?>()
        val controller = StreamController(
            rtcManager = rtc,
            roomController = RoomController(
                rtc,
                RoomHeartbeat(rtc, sleeper = { awaitCancellation() }, scope = this),
            ),
            encodingController = EncodingStub,
            qualityController = QualityStub,
            remoteStreamListener = { remoteEvents += it },
            generationScope = this,
            renderDispatcher = StandardTestDispatcher(testScheduler),
        )
        val connection = RealtimeSessionConnection(
            roomId = "room-id",
            userId = "user-id",
            token = "room-token",
            botName = "bot-id",
        )

        controller.connect(connection, includeLocalAudio = false) {}

        assertTrue(rtc.calls.contains(RtcManagingCall.PublishLocalVideo))
        assertFalse(rtc.calls.contains(RtcManagingCall.PublishLocalAudio))

        val confirmation = controller.beginGeneration(
            taskId = "task-id",
            videoFormat = RealtimeVideoFormat(704, 1280, 24),
            context = RealtimeContext("prompt"),
        )
        rtc.emitRemoteVideoPublished("bot-id", true)
        val remoteStream = RemoteStream("room-id", "bot-id")
        rtc.emitSeiMessage(remoteStream, "task-id")

        confirmation.await()
        assertEquals(listOf(remoteStream), remoteEvents.filterNotNull())
        assertTrue(
            rtc.calls.contains(
                RtcManagingCall.SubscribeRemoteVideo("bot-id", true),
            ),
        )

        controller.activateRemoteAudio()
        assertTrue(
            rtc.calls.contains(
                RtcManagingCall.SubscribeRemoteAudio("bot-id", true),
            ),
        )

        controller.stopGeneration("task-id")
        assertTrue(
            rtc.calls.contains(
                RtcManagingCall.SubscribeRemoteAudio("bot-id", false),
            ),
        )

        val restarted = controller.beginGeneration(
            taskId = "next-task-id",
            videoFormat = RealtimeVideoFormat(704, 1280, 24),
            context = RealtimeContext("next prompt"),
        )
        rtc.emitSeiMessage(remoteStream, "next-task-id")
        restarted.await()
        assertEquals(listOf(remoteStream, remoteStream), remoteEvents.filterNotNull())

        controller.disconnect()
        assertTrue(rtc.calls.contains(RtcManagingCall.UnpublishLocalVideo))
        assertEquals(null, remoteEvents.last())
    }

    @Test
    fun `SEI cannot confirm generation without target bot publication`() = runTest {
        val rtc = RtcManagingStub()
        val remoteEvents = mutableListOf<RemoteStream?>()
        val controller = StreamController(
            rtcManager = rtc,
            roomController = RoomController(
                rtc,
                RoomHeartbeat(rtc, sleeper = { awaitCancellation() }, scope = this),
            ),
            encodingController = EncodingStub,
            qualityController = QualityStub,
            remoteStreamListener = { remoteEvents += it },
            generationScope = this,
            renderDispatcher = StandardTestDispatcher(testScheduler),
        )
        controller.connect(
            RealtimeSessionConnection("room-id", "user-id", "token", "bot-id"),
            includeLocalAudio = false,
        ) {}
        val taskId = "task-token?os=android"
        val confirmation = controller.beginGeneration(
            taskId,
            RealtimeVideoFormat(704, 1280, 24),
            RealtimeContext("prompt"),
        )
        val stream = RemoteStream("room-id", "bot-id")

        listOf(
            "",
            "?os=android",
            "task-token-other?os=android&index=0",
            "task-toke?os=android&index=0",
            "task-other?os=android&index=0",
        ).forEach { rtc.emitSeiMessage(stream, it) }
        rtc.emitSeiMessage(RemoteStream("other-room", "bot-id"), "$taskId&index=0")
        rtc.emitSeiMessage(RemoteStream("room-id", "other-bot"), "$taskId&index=0")

        assertFalse(confirmation.isCompleted)
        assertTrue(remoteEvents.isEmpty())

        rtc.emitRemoteVideoPublished("other-bot", true)
        rtc.emitRemoteVideoPublished("bot-id", true, roomId = "other-room")
        assertFalse(confirmation.isCompleted)
        rtc.emitRemoteVideoPublished("bot-id", true)

        confirmation.await()
        assertEquals(listOf(stream), remoteEvents)
        controller.disconnect()
    }

    @Test
    fun `external frames are gated by generation without legacy SEI`() = runTest {
        val rtc = RtcManagingStub()
        val controller = StreamController(
            rtcManager = rtc,
            roomController = RoomController(
                rtc,
                RoomHeartbeat(rtc, sleeper = { awaitCancellation() }, scope = this),
            ),
            encodingController = EncodingStub,
            qualityController = QualityStub,
            generationScope = this,
            renderDispatcher = StandardTestDispatcher(testScheduler),
        )
        controller.connect(
            RealtimeSessionConnection("room-id", "user-id", "token", "bot-id"),
            includeLocalAudio = false,
        ) {}
        val frame = VideoFrame(
            format = VideoFormat(2, 2, VideoPixelFormat.RGBA),
            timestampUs = 0L,
            planes = listOf(VideoFramePlane(ByteArray(16), stride = 8)),
        )
        val taskId = "task-first?os=android"

        controller.pushLocalVideoFrame(frame)
        controller.beginGeneration(
            taskId,
            RealtimeVideoFormat(704, 1280, 24),
            RealtimeContext("first"),
        )
        controller.pushLocalVideoFrame(frame)
        controller.pushLocalVideoFrame(frame)
        controller.updateGeneration(
            taskId,
            RealtimeVideoFormat(704, 1280, 24),
            RealtimeContext("second"),
        )
        controller.pushLocalVideoFrame(frame)
        controller.stopGeneration(taskId)
        controller.pushLocalVideoFrame(frame)

        val nextTaskId = "task-second?os=android"
        controller.beginGeneration(
            nextTaskId,
            RealtimeVideoFormat(704, 1280, 24),
            RealtimeContext("next"),
        )
        controller.pushLocalVideoFrame(frame)

        val submissions = rtc.calls.filterIsInstance<RtcManagingCall.PushExternalVideoFrame>()
        assertEquals(4, submissions.size)
        assertTrue(submissions.all { it.seiData == null })
        controller.disconnect()
    }
}

private data object EncodingStub : EncodingControlling {
    override fun configure(videoFormat: RealtimeVideoFormat) = Unit
}

private data object QualityStub : QualityControlling {
    override fun setAdaptiveQualityListener(listener: ((RealtimeNetworkQuality) -> Unit)?) = Unit
    override fun setUplinkVideoStatisticsListener(listener: UplinkVideoStatisticsListener?) = Unit
    override fun setDownlinkVideoStatisticsListener(listener: DownlinkVideoStatisticsListener?) = Unit
    override fun setUplinkVideoStatisticsEnabled(enabled: Boolean) = Unit
    override fun setDownlinkVideoStatisticsUser(userId: String?) = Unit
    override fun setNetworkQualityListener(listener: RealtimeNetworkQualityListener?) = Unit

    override fun setPerformanceAlarmListener(listener: RealtimePerformanceAlarmListener?) = Unit
}
