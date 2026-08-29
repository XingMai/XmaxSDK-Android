package ai.xmax.sdk.media.image

import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.VideoFormat
import ai.xmax.sdk.VideoFrame
import ai.xmax.sdk.VideoFramePlane
import ai.xmax.sdk.VideoPixelFormat
import ai.xmax.sdk.foundation.media.image.DecodedImage
import ai.xmax.sdk.rendering.video.VideoRenderRegistry
import ai.xmax.sdk.stream.room.RtcManagingCall
import ai.xmax.sdk.stream.room.RtcManagingStub
import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageControllerTest {
    @Test
    fun `image stream uses external source and registers reusable preview`() = runTest {
        val rtc = RtcManagingStub()
        val source = ImageSourceStub()
        val controller = ImageController(rtc, source)

        val stream = controller.createLocalImageStream(
            imageData = byteArrayOf(1, 2, 3),
            videoFormat = null,
        )

        assertEquals("stream-local", stream.id)
        assertEquals(source.prepared.first, stream.videoTrack?.videoFormat)
        assertTrue(source.started)
        assertTrue(RtcManagingCall.UseExternalVideoSource in rtc.calls)
        assertNotNull(stream.videoTrack?.let(VideoRenderRegistry::binding))

        controller.stopLocalImageStream()

        assertTrue(source.stopped)
        assertNull(controller.currentTrack)
        assertNull(stream.videoTrack?.let(VideoRenderRegistry::binding))
    }
}

private class ImageSourceStub : ImageSourceControlling {
    var started = false
    var stopped = false
    val prepared = RealtimeVideoFormat(704, 1_280, 24) to
        VideoFrame(
            format = VideoFormat(2, 2, VideoPixelFormat.RGBA),
            timestampUs = 0L,
            planes = listOf(
                VideoFramePlane(
                    data = ByteArray(16),
                    stride = 8,
                ),
            ),
        )

    override suspend fun prepare(
        imageData: ByteArray,
        videoFormat: RealtimeVideoFormat?,
    ): Pair<RealtimeVideoFormat, VideoFrame> = prepared

    override suspend fun prepare(
        bitmap: Bitmap,
        videoFormat: RealtimeVideoFormat?,
    ): Pair<RealtimeVideoFormat, VideoFrame> = prepared

    override suspend fun prepare(
        uri: Uri,
        videoFormat: RealtimeVideoFormat?,
    ): Pair<RealtimeVideoFormat, VideoFrame> = prepared

    override suspend fun prepare(
        decodedImage: DecodedImage,
        videoFormat: RealtimeVideoFormat?,
    ): Pair<RealtimeVideoFormat, VideoFrame> = prepared

    override fun start() {
        started = true
    }

    override fun stop() {
        stopped = true
    }
}
