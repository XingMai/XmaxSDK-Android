//
//  MediaSourceControllerTest.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.media

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
    fun `video derives dimensions from media and respects the model area limit`() {
        for ((width, height) in listOf(1920 to 1080, 1080 to 1920, 1280 to 720, 640 to 480, 640 to 640)) {
            val result = controller.resolveVideoFormat(metadata(width, height))
            assertEquals(RealtimeVideoFormat(width, height, service.model.defaultFrameRate), result)
        }
        assertEquals(RealtimeVideoFormat(1920, 1080, 30), controller.resolveVideoFormat(metadata(3840, 2160)))
        assertEquals(RealtimeVideoFormat(1080, 1920, 30), controller.resolveVideoFormat(metadata(3840, 2160, VideoRotation.ROTATION_90)))
    }

    @Test
    fun `rotation determines display dimensions and odd dimensions align down to four pixels`() {
        for (rotation in listOf(VideoRotation.ROTATION_90, VideoRotation.ROTATION_270)) {
            assertEquals(RealtimeVideoFormat(1080, 1920, 30), controller.resolveVideoFormat(metadata(1920, 1080, rotation)))
        }
        assertEquals(RealtimeVideoFormat(1920, 1080, 30),
            controller.resolveVideoFormat(metadata(1920, 1080, VideoRotation.ROTATION_180)))
        assertEquals(RealtimeVideoFormat(1920, 1080, 30), controller.resolveVideoFormat(metadata(1921, 1081)))
    }

    @Test
    fun `video rejects invalid or sub-alignment media dimensions before playback`() {
        for ((width, height) in listOf(0 to 1080, -1 to 1080, 1 to 1, 2 to 2, 3 to 3)) {
            assertThrows(XmaxError::class.java) { controller.resolveVideoFormat(metadata(width, height)) }
        }
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
