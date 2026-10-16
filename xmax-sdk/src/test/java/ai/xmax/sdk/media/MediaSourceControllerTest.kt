package ai.xmax.sdk.media

import ai.xmax.sdk.RealtimeVideoEncoderPreference
import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.VideoContentMode
import ai.xmax.sdk.VideoRotation
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxVideoView
import ai.xmax.sdk.foundation.media.MediaFileMetadata
import ai.xmax.sdk.foundation.media.MediaFileMetadataManaging
import ai.xmax.sdk.media.video.VideoPlayerControlling
import ai.xmax.sdk.service.media.MediaService
import android.net.Uri
import org.junit.Assert.*
import org.junit.Test

class MediaSourceControllerTest {
    private val service = MediaService()
    private val controller = MediaSourceController(
        object : MediaFileMetadataManaging {
            override suspend fun readMetadata(uri: Uri): MediaFileMetadata = error("unused")
        },
        service,
        PlayerStub,
    )

    @Test
    fun `unspecified format preserves original dimensions instead of requiring a model bucket`() {
        for ((width, height) in listOf(1920 to 1080, 1080 to 1920, 1280 to 720, 640 to 480, 640 to 640, 3840 to 2160)) {
            val result = controller.resolveVideoFormat(metadata(width, height), null)
            assertEquals(RealtimeVideoFormat(width, height, service.model.defaultFrameRate), result)
        }
    }

    @Test
    fun `rotation determines display dimensions and odd dimensions align down to even pixels`() {
        for (rotation in listOf(VideoRotation.ROTATION_90, VideoRotation.ROTATION_270)) {
            assertEquals(RealtimeVideoFormat(1080, 1920, 30), controller.resolveVideoFormat(metadata(1920, 1080, rotation), null))
        }
        assertEquals(RealtimeVideoFormat(1920, 1080, 30),
            controller.resolveVideoFormat(metadata(1920, 1080, VideoRotation.ROTATION_180), null))
        assertEquals(RealtimeVideoFormat(1920, 1080, 30), controller.resolveVideoFormat(metadata(1921, 1081), null))
    }

    @Test
    fun `explicit format controls input size fps bitrate and preference without model resizing`() {
        val requested = RealtimeVideoFormat(1280, 720, 24, minimumBitrate = 100, maximumBitrate = 4000,
            encoderPreference = RealtimeVideoEncoderPreference.MAINTAIN_FRAMERATE)
        assertSame(requested, controller.resolveVideoFormat(metadata(1920, 1080), requested))
        val portrait = requested.copy(width = 720, height = 1280)
        assertSame(portrait, controller.resolveVideoFormat(metadata(1920, 1080, VideoRotation.ROTATION_90), portrait))
    }

    @Test
    fun `explicit aspect ratio changes and invalid formats fail before playback`() {
        for (format in listOf(RealtimeVideoFormat(1920, 1024, 24), RealtimeVideoFormat(720, 720, 24),
            RealtimeVideoFormat(0, 720, 24), RealtimeVideoFormat(1279, 720, 24), RealtimeVideoFormat(1280, 720, 0))) {
            assertThrows(XmaxError::class.java) { controller.resolveVideoFormat(metadata(1920, 1080), format) }
        }
        assertThrows(XmaxError::class.java) { controller.resolveVideoFormat(metadata(0, 1080), null) }
    }

    private fun metadata(width: Int, height: Int, rotation: VideoRotation = VideoRotation.ROTATION_0) =
        MediaFileMetadata(width, height, rotation, durationUs = 1_000_000, hasAudio = false)

    private object PlayerStub : VideoPlayerControlling {
        override suspend fun configure(uri: Uri, outputWidth: Int, outputHeight: Int, rotation: VideoRotation,
            frameRate: Int, hasAudio: Boolean, durationUs: Long) = Unit
        override suspend fun start() = Unit
        override suspend fun stop() = Unit
        override suspend fun setLocalAudioPreviewMuted(muted: Boolean) = Unit
        override suspend fun setLocalAudioVolume(volume: Float) = Unit
        override fun attachPreview(view: XmaxVideoView, contentMode: VideoContentMode) = Unit
        override fun detachPreview(view: XmaxVideoView) = Unit
    }
}
