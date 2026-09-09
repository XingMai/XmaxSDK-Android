package ai.xmax.sdk.stream

import ai.xmax.sdk.RealtimeContext
import ai.xmax.sdk.RealtimeNetworkQualityListener
import ai.xmax.sdk.RealtimePerformanceAlarmListener
import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.VideoFormat
import ai.xmax.sdk.VideoFrame
import ai.xmax.sdk.VideoFramePlane
import ai.xmax.sdk.VideoPixelFormat
import ai.xmax.sdk.foundation.rtc.RemoteStream
import ai.xmax.sdk.service.realtime.RealtimeSessionConnection
import ai.xmax.sdk.stream.encoding.EncodingControlling
import ai.xmax.sdk.stream.quality.QualityControlling
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
    fun `startup timing follows actual room join signal and matching SEI only`() = runTest {
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
            rtc.emitSeiMessage(RemoteStream("room", "bot"), "task")
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
    fun `SEI matches the active base task ID regardless of query parameters`() = runTest {
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

        rtc.emitSeiMessage(stream, " task-token?index=12&os=android ")

        confirmation.await()
        assertEquals(listOf(stream), remoteEvents)
        controller.disconnect()
    }

    @Test
    fun `external frame indices increase within a task and reset for a new task`() = runTest {
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

        val frameIds = rtc.calls.mapNotNull { call ->
            (call as? RtcManagingCall.PushExternalVideoFrame)
                ?.seiData
                ?.toByteArray()
                ?.toString(Charsets.UTF_8)
        }
        assertEquals(
            listOf(
                "$taskId&index=0",
                "$taskId&index=1",
                "$taskId&index=2",
                "$nextTaskId&index=0",
            ),
            frameIds,
        )
        controller.disconnect()
    }
}

private data object EncodingStub : EncodingControlling {
    override fun configure(videoFormat: RealtimeVideoFormat) = Unit
}

private data object QualityStub : QualityControlling {
    override fun setNetworkQualityListener(listener: RealtimeNetworkQualityListener?) = Unit

    override fun setPerformanceAlarmListener(listener: RealtimePerformanceAlarmListener?) = Unit
}
