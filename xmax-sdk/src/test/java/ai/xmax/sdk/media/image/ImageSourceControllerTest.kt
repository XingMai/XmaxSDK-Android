//
//  ImageSourceControllerTest.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.media.image

import ai.xmax.sdk.RealtimeModel
import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.VideoFormat
import ai.xmax.sdk.VideoFrame
import ai.xmax.sdk.VideoFramePlane
import ai.xmax.sdk.VideoPixelFormat
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import ai.xmax.sdk.foundation.media.image.DecodedImage
import ai.xmax.sdk.foundation.media.image.ImageManaging
import ai.xmax.sdk.service.media.MediaService
import android.graphics.Bitmap
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ImageSourceControllerTest {
    @Test
    fun `image input preserves portrait landscape square and odd dimensions outside model buckets`() = runTest {
        val cases = listOf(IntSize(1920, 1080), IntSize(1080, 1920), IntSize(640, 480), IntSize(1024, 1024), IntSize(1025, 823))
        for (size in cases) {
            val decoded = DecodedImageStub(size)
            val controller = ImageSourceController(
                imageManager = ImageManagerStub(decoded),
                mediaService = MediaService(),
                frameListener = {},
                errorListener = { throw it },
                uriDataLoader = { byteArrayOf(1) },
                outputScope = backgroundScope,
            )
            val expected = RealtimeVideoFormat(size.width / 4 * 4, size.height / 4 * 4, 30)
            val (format) = controller.prepare(byteArrayOf(1))
            assertEquals(expected, format)
            assertEquals(expected, decoded.requestedFormat)
            controller.stop()
        }
    }

    @Test
    fun `large images use resolved media dimensions and invalid dimensions fail`() = runTest {
        val decoded = DecodedImageStub(IntSize(3840, 2160))
        val controller = ImageSourceController(
            imageManager = ImageManagerStub(decoded),
            mediaService = MediaService(),
            frameListener = {},
            errorListener = { throw it },
            uriDataLoader = { byteArrayOf(1) },
            outputScope = backgroundScope,
        )
        val expected = RealtimeVideoFormat(1920, 1080, 30)
        assertEquals(expected, controller.prepare(decoded).first)
        assertEquals(expected, decoded.requestedFormat)
        controller.stop()

        for (size in listOf(IntSize(0, 1080), IntSize(1, 1), IntSize(2, 2), IntSize(3, 3))) {
            val error = runCatching { controller.prepare(DecodedImageStub(size)) }.exceptionOrNull()
            assertTrue(error is XmaxError)
            assertEquals(XmaxErrorCode.INVALID_CONFIGURATION, (error as XmaxError).code)
        }
    }

    @Test
    fun `default image frame rate follows model`() = runTest {
        val cases = listOf(
            RealtimeModel.X2_1_1_PREVIEW_CN to IntSize(1024, 1_920),
            RealtimeModel.X2_1_1_PREVIEW_US to IntSize(1_920, 1_024),
        )

        for ((model, size) in cases) {
            val controller = ImageSourceController(
                imageManager = ImageManagerStub(DecodedImageStub(size)),
                mediaService = MediaService(model),
                frameListener = {},
                errorListener = { throw it },
                uriDataLoader = { byteArrayOf(1) },
                outputScope = backgroundScope,
            )

            val (format) = controller.prepare(byteArrayOf(1))
            assertEquals(model.defaultFrameRate, format.fps)
            assertEquals(size, IntSize(format.width, format.height))
        }
    }

    @Test
    fun `prepared image emits reusable frames until stopped`() = runTest {
        val frames = mutableListOf<VideoFrame>()
        val decoded = DecodedImageStub()
        val controller = ImageSourceController(
            imageManager = ImageManagerStub(decoded),
            mediaService = MediaService(),
            frameListener = frames::add,
            errorListener = { throw it },
            uriDataLoader = { byteArrayOf(1) },
            timestampUsProvider = { frames.size.toLong() },
            outputScope = backgroundScope,
        )

        val expectedFormat = RealtimeVideoFormat(1024, 1920, 30)
        val prepared = controller.prepare(imageData = byteArrayOf(1, 2, 3))
        controller.start()
        advanceTimeBy(100L)
        runCurrent()

        assertEquals(expectedFormat, prepared.first)
        assertTrue(frames.size >= 3)
        assertTrue(frames.all { it.planes.first() === prepared.second.planes.first() })

        controller.stop()
        val stoppedCount = frames.size
        advanceTimeBy(100L)
        runCurrent()
        assertEquals(stoppedCount, frames.size)
    }
}

private class ImageManagerStub(
    private val decodedImage: DecodedImage,
) : ImageManaging {
    override fun decode(data: ByteArray): DecodedImage = decodedImage
    override fun decode(bitmap: Bitmap): DecodedImage = decodedImage
}

private class DecodedImageStub(
    override val size: IntSize = IntSize(1024, 1_920),
) : DecodedImage {
    var requestedFormat: RealtimeVideoFormat? = null

    override fun makeVideoFrame(videoFormat: RealtimeVideoFormat): VideoFrame {
        requestedFormat = videoFormat
        return VideoFrame(
            format = VideoFormat(2, 2, VideoPixelFormat.RGBA),
            timestampUs = 0L,
            planes = listOf(VideoFramePlane(ByteArray(16), stride = 8)),
        )
    }
}
