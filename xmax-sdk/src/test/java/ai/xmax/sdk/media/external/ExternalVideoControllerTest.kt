package ai.xmax.sdk.media.external

import ai.xmax.sdk.*
import ai.xmax.sdk.render.video.VideoRenderRegistry
import ai.xmax.sdk.service.media.MediaService
import ai.xmax.sdk.stream.room.RtcManagingStub
import ai.xmax.sdk.stream.room.RtcManagingCall
import android.widget.FrameLayout
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExternalVideoControllerTest {
    private val media = object : MediaServicing {
        override val model = RealtimeModel.X2_1_1_PREVIEW_CN
        override fun resolveModelInputSize(size: IntSize) = size
    }

    @Test
    fun `video only source needs no audio or preview implementation`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val rtc = RtcManagingStub()
        val video = mutableListOf<VideoFrame>()
        val audio = mutableListOf<AudioFrame>()
        val errors = mutableListOf<XmaxError>()
        val controller = ExternalVideoController(rtc, media, { true }, video::add, audio::add, errors::add)
        val source = VideoOnlySource()

        try {
            val stream = controller.create(source)
            val track = checkNotNull(stream.videoTrack)
            val sink = checkNotNull(source.sink)
            assertFalse(source.hasAudio)
            assertFalse(controller.hasAudio)
            assertEquals(track.videoFormat, sink.videoFormat)

            controller.setAudio(volume = 0.2f, muted = true)
            sink.pushVideo(ByteArray(16), 1)
            sink.pushAudio(ByteArray(960), 1)

            assertEquals(1, video.size)
            assertTrue(audio.isEmpty())
            assertFalse(rtc.calls.contains(RtcManagingCall.StartExternalAudioSource))

            controller.stop()
            controller.stop()
            assertNull(source.sink)
            assertEquals(1, source.stops)
            assertFalse(sink.isRequestingFrames)
            assertNull(VideoRenderRegistry.binding(track))
            assertFalse(rtc.calls.contains(RtcManagingCall.StopExternalAudioSource))

            sink.pushVideo(ByteArray(16), 2)
            sink.reportError(IllegalStateException("Source already unbound"))
            assertEquals(1, video.size)
            assertTrue(errors.isEmpty())
        } finally {
            controller.stop()
            Dispatchers.resetMain()
        }
    }

    @Test fun `stopping invalidates late frames and errors without owning player`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val rtc = RtcManagingStub()
            val video = mutableListOf<VideoFrame>()
            val audio = mutableListOf<AudioFrame>()
            val errors = mutableListOf<XmaxError>()
            var requesting = false
            val controller = ExternalVideoController(rtc, media, { requesting }, video::add, audio::add, errors::add)
            val first = Source()
            val stream = controller.create(first)
            assertNotNull(VideoRenderRegistry.binding(stream.videoTrack!!))
            val oldSink = first.sink!!
            oldSink.pushVideo(ByteArray(16), 1)
            assertTrue(video.isEmpty())
            requesting = true
            val bytes = ByteArray(16) { 7 }
            oldSink.pushVideo(bytes, 2)
            bytes.fill(9)
            assertEquals(7.toByte(), video.single().planes.single().data.first())
            oldSink.pushVideo(bytes, 2) // duplicate timestamp
            oldSink.pushAudio(ByteArray(960), 3)
            assertEquals(1, video.size)
            assertEquals(1, audio.size)
            controller.stop()
            assertEquals(1, first.stops)
            assertNull(VideoRenderRegistry.binding(stream.videoTrack!!))
            assertFalse(oldSink.isRequestingFrames)
            controller.create(Source())
            oldSink.pushVideo(bytes, 4)
            oldSink.pushAudio(ByteArray(960), 4)
            oldSink.reportError(IllegalStateException("late"))
            assertEquals(1, video.size)
            assertEquals(1, audio.size)
            assertTrue(errors.isEmpty())
            controller.stop()
            assertEquals(2, rtc.calls.count { it == RtcManagingCall.StartExternalAudioSource })
            assertEquals(2, rtc.calls.count { it == RtcManagingCall.StopExternalAudioSource })
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `failed binding releases audio and allows retry`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val rtc = RtcManagingStub()
            val controller = ExternalVideoController(rtc, media, { true }, {}, {}, {})
            val broken = Source(fail = true)
            try { controller.create(broken); fail("Expected source failure") } catch (_: XmaxError) { }
            assertNull(controller.currentTrack)
            assertFalse(controller.hasAudio)
            assertEquals(1, broken.stops)
            assertFalse(broken.sink!!.isRequestingFrames)
            controller.create(Source())
            controller.stop()
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `default output preserves supported bucket sizes`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val controller = ExternalVideoController(RtcManagingStub(), MediaService(), { true }, {}, {}, {})
        try {
            for ((input, expected) in listOf(
                RealtimeVideoFormat(1024, 1920, 24) to RealtimeVideoFormat(1024, 1920, 30),
                RealtimeVideoFormat(1920, 1024, 60) to RealtimeVideoFormat(1920, 1024, 30),
            )) {
                val source = Source(videoFormat = input)
                val stream = controller.create(source)
                assertEquals(expected, stream.videoTrack!!.videoFormat)
                assertEquals(expected, source.sink!!.videoFormat)
                controller.stop()
            }
        } finally { controller.stop(); Dispatchers.resetMain() }
    }

    @Test fun `explicit output controls dimensions fps and encoding settings`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val controller = ExternalVideoController(RtcManagingStub(), MediaService(), { true }, {}, {}, {})
        try {
            val source = Source(videoFormat = RealtimeVideoFormat(1080, 1920, 30))
            val requested = RealtimeVideoFormat(1024, 1920, 24, minimumBitrate = 800,
                maximumBitrate = 2000, encoderPreference = RealtimeVideoEncoderPreference.MAINTAIN_QUALITY)
            val stream = controller.create(source, requested)
            assertEquals(requested, stream.videoTrack!!.videoFormat)
            assertEquals(requested, source.sink!!.videoFormat)
        } finally { controller.stop(); Dispatchers.resetMain() }
    }

    @Test fun `pro accepts explicit resolution bucket and rejects unsupported source size before binding`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val rtc = RtcManagingStub()
        val controller = ExternalVideoController(rtc, MediaService(RealtimeModel.X2_1_1_PREVIEW_CN), { true }, {}, {}, {})
        try {
            val source = Source(videoFormat = RealtimeVideoFormat(1080, 1920, 30))
            try { controller.create(source); fail("Expected unsupported resolution") } catch (_: XmaxError) { }
            assertNull(source.sink)
            assertTrue(rtc.calls.isEmpty())
            val requested = RealtimeVideoFormat(1024, 1920, 30)
            assertEquals(requested, controller.create(source, requested).videoTrack!!.videoFormat)
            assertEquals(requested, source.sink!!.videoFormat)
        } finally { controller.stop(); Dispatchers.resetMain() }
    }

    @Test fun `invalid explicit format is rejected before starting source`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val rtc = RtcManagingStub()
        val controller = ExternalVideoController(rtc, MediaService(), { true }, {}, {}, {})
        try {
            for (format in listOf(RealtimeVideoFormat(832, 1472, 0),
                RealtimeVideoFormat(0, 1472, 30), RealtimeVideoFormat(832, 1472, 30, maximumBitrate = -1))) {
                val source = Source()
                try { controller.create(source, format); fail("Expected invalid format") } catch (_: XmaxError) { }
                assertNull(source.sink)
                assertNull(controller.currentTrack)
            }
            assertTrue(rtc.calls.isEmpty())
        } finally { controller.stop(); Dispatchers.resetMain() }
    }

    private class VideoOnlySource : RealtimeExternalVideoSource {
        override val videoFormat = RealtimeVideoFormat(2, 2, 30)
        var sink: RealtimeExternalFrameSink? = null
        var stops = 0

        override suspend fun start(sink: RealtimeExternalFrameSink) {
            this.sink = sink
        }

        override suspend fun stop() {
            sink = null
            stops++
        }
    }

    private class Source(
        private val fail: Boolean = false,
        override val videoFormat: RealtimeVideoFormat = RealtimeVideoFormat(2, 2, 30),
    ) : RealtimeExternalVideoSource {
        override val hasAudio = true
        var sink: RealtimeExternalFrameSink? = null
        var stops = 0
        override suspend fun start(sink: RealtimeExternalFrameSink) {
            this.sink = sink
            if (fail) error("prepare failed")
        }
        override suspend fun stop() { stops++ }
        override fun attachPreview(container: FrameLayout, contentMode: VideoContentMode) = Unit
        override fun detachPreview(container: FrameLayout) = Unit
        override fun setPreviewAudio(volume: Float, muted: Boolean) = Unit
    }
}
