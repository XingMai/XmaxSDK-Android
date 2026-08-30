package ai.xmax.sdk.stream

import ai.xmax.sdk.RealtimeContext
import ai.xmax.sdk.RealtimeNetworkQualityListener
import ai.xmax.sdk.RealtimePerformanceAlarmListener
import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.foundation.rtc.RemoteStream
import ai.xmax.sdk.service.realtime.RealtimeSessionConnection
import ai.xmax.sdk.stream.encoding.EncodingControlling
import ai.xmax.sdk.stream.quality.QualityControlling
import ai.xmax.sdk.stream.room.RoomController
import ai.xmax.sdk.stream.room.RoomHeartbeat
import ai.xmax.sdk.stream.room.RtcManagingCall
import ai.xmax.sdk.stream.room.RtcManagingStub
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamControllerTest {
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
    fun `camera stream connects confirms generation and disconnects`() = runTest {
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
        assertFalse(controller.hasGenerationTask)
        assertTrue(
            rtc.calls.contains(
                RtcManagingCall.SubscribeRemoteAudio("bot-id", false),
            ),
        )

        controller.disconnect()
        assertTrue(rtc.calls.contains(RtcManagingCall.UnpublishLocalVideo))
        assertEquals(null, remoteEvents.last())
    }
}

private data object EncodingStub : EncodingControlling {
    override fun configure(videoFormat: RealtimeVideoFormat) = Unit
}

private data object QualityStub : QualityControlling {
    override fun setNetworkQualityListener(listener: RealtimeNetworkQualityListener?) = Unit

    override fun setPerformanceAlarmListener(listener: RealtimePerformanceAlarmListener?) = Unit
}
