package ai.xmax.sdk.media.external

import ai.xmax.sdk.*
import ai.xmax.sdk.foundation.permissions.PermissionManaging
import ai.xmax.sdk.media.MediaController
import ai.xmax.sdk.media.camera.CameraController
import ai.xmax.sdk.service.media.MediaService
import ai.xmax.sdk.stream.room.RtcManagingCall
import ai.xmax.sdk.stream.room.RtcManagingStub
import android.widget.FrameLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** 使用真实媒体控制器验证替换顺序、引擎归属和过期输入隔离。 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExternalVideoReplacementTest {

    /** 换源先解绑旧 sink，不重建引擎；准备失败仍可重试，最终清理只销毁一次。 */
    @Test fun replacementKeepsEngineAndInvalidatesOldInput() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val rtc = RtcManagingStub()
        val service = MediaService()
        val frames = mutableListOf<VideoFrame>()
        val errors = mutableListOf<XmaxError>()
        val external = ExternalVideoController(rtc, service, { true }, frames::add, {}, errors::add)
        val controller = MediaController(rtc, CameraController(rtc, object : PermissionManaging {
            override suspend fun ensureCameraPermission() = error("No camera expected")
            override suspend fun ensureMicrophonePermission() = error("No microphone expected")
        }, service), externalController = external)
        try {
            val source = Source()
            controller.createExternalVideoStream(source, null)
            val oldSink = checkNotNull(source.sink)
            val next = Source()
            val stream = controller.replaceExternalVideoStream(null) {
                assertEquals(1, source.stops)
                assertFalse(oldSink.isRequestingFrames)
                next
            }
            assertTrue(controller.owns(stream))
            oldSink.pushVideo(ByteArray(832 * 1472 * 4), 1)
            oldSink.reportError(IllegalStateException("late"))
            assertTrue(frames.isEmpty())
            assertTrue(errors.isEmpty())
            assertTrue(runCatching {
                controller.replaceExternalVideoStream(null) { error("failed media preparation") }
            }.isFailure)
            assertNull(controller.currentTrack)
            assertTrue(controller.isExternalVideo)
            controller.replaceExternalVideoStream(null) { Source() }
            assertEquals(1, rtc.calls.count { it == RtcManagingCall.Initialize })
            assertFalse(rtc.calls.contains(RtcManagingCall.Destroy))
            assertFalse(rtc.calls.contains(RtcManagingCall.LeaveRoom))
        } finally {
            controller.stopLocalStream()
            Dispatchers.resetMain()
        }
        assertEquals(1, rtc.calls.count { it == RtcManagingCall.Destroy })
    }

    private class Source : RealtimeExternalVideoSource {
        override val videoFormat = RealtimeVideoFormat(832, 1472, 30)
        override val hasAudio = true
        var sink: RealtimeExternalFrameSink? = null
        var stops = 0
        override suspend fun start(sink: RealtimeExternalFrameSink) { this.sink = sink }
        override suspend fun stop() { stops++ }
        override fun attachPreview(container: FrameLayout, contentMode: VideoContentMode) = Unit
        override fun detachPreview(container: FrameLayout) = Unit
        override fun setPreviewAudio(volume: Float, muted: Boolean) = Unit
    }
}
