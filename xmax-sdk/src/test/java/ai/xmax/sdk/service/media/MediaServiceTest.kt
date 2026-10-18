//
//  MediaServiceTest.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.service.media

import ai.xmax.sdk.RealtimeModel
import ai.xmax.sdk.RealtimeMediaSource
import androidx.compose.ui.geometry.Size
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.*
import org.junit.Test

class MediaServiceTest {
    @Test
    fun `generation canvas selects nearest aspect without changing media input`() {
        RealtimeModel.entries.forEach { model ->
            val service = MediaService(model)
            for (size in listOf(IntSize(1920, 1080), IntSize(1280, 720), IntSize(640, 480), IntSize(3840, 2160))) {
                assertEquals(IntSize(1920, 1024), service.resolveGenerationSize(size))
                assertEquals(IntSize(1024, 1920), service.resolveGenerationSize(IntSize(size.height, size.width)))
            }
            model.resolutionBuckets.forEach { assertEquals(it, service.resolveGenerationSize(it)) }
            assertTrue(service.resolveGenerationSize(IntSize(640, 640)) in model.resolutionBuckets)
            assertThrows(XmaxError::class.java) { service.resolveGenerationSize(IntSize(0, 1080)) }
        }
    }

    @Test fun `both Agora models preserve only the declared buckets`() {
        RealtimeModel.entries.forEach { model ->
            val service = MediaService(model)
            model.resolutionBuckets.forEach { assertEquals(it, service.resolveMediaSize(it, RealtimeMediaSource.CAMERA)) }
            val format = model.defaultCameraVideoFormat
            assertEquals(IntSize(format.width, format.height), service.resolveMediaSize(IntSize(format.width, format.height), RealtimeMediaSource.CAMERA))
            listOf(IntSize(832, 1472), IntSize(1920, 1080), IntSize(512, 960),
                IntSize(2048, 3840), IntSize(1120, 1120), IntSize(0, 1920)).forEach { size ->
                val error = assertThrows(XmaxError::class.java) { service.resolveMediaSize(size, RealtimeMediaSource.CAMERA) }
                assertEquals(XmaxErrorCode.INVALID_CONFIGURATION, error.code)
            }
        }
    }

    @Test
    fun `media respects model area without applying minimum area or bucket constraints`() {
        val cases = listOf(
            IntSize(3840, 2160) to IntSize(1920, 1080),
            IntSize(2160, 3840) to IntSize(1080, 1920),
            IntSize(8000, 6000) to IntSize(1664, 1248),
            IntSize(6000, 8000) to IntSize(1248, 1664),
            IntSize(1280, 1707) to IntSize(1252, 1672),
            IntSize(1707, 1280) to IntSize(1672, 1252),
            IntSize(320, 180) to IntSize(320, 180),
            IntSize(804, 600) to IntSize(804, 600),
            IntSize(1001, 1000) to IntSize(1000, 1000),
            IntSize(802, 600) to IntSize(800, 600),
        )
        for (model in RealtimeModel.entries) {
            val service = MediaService(model)
            for (source in listOf(RealtimeMediaSource.IMAGE, RealtimeMediaSource.VIDEO)) {
                for ((input, expected) in cases) {
                    val actual = service.resolveMediaSize(input, source)
                    assertEquals(expected, actual)
                    assertTrue(actual.width.toLong() * actual.height <= model.maximumInputPixels)
                    assertEquals(0, actual.width % 4)
                    assertEquals(0, actual.height % 4)
                    assertTrue(actual.width <= input.width && actual.height <= input.height)
                    assertEquals(actual, service.resolveTargetSize(actual, Size(actual.width.toFloat(), actual.height.toFloat())))
                }
            }
        }
    }

    @Test
    fun `target preserves exact aspect within bounds without upscaling`() {
        val service = MediaService()
        assertEquals(IntSize(720, 1280), service.resolveTargetSize(IntSize(720, 1280), Size(800f, 1600f)))
        assertEquals(IntSize(432, 768), service.resolveTargetSize(IntSize(720, 1280), Size(600f, 800f)))
        assertEquals(IntSize(800, 1500), service.resolveTargetSize(IntSize(1024, 1920), Size(819.2f, 1536f)))
        assertEquals(IntSize(640, 1200), service.resolveTargetSize(IntSize(1024, 1920), Size(665.6f, 1248f)))
        assertEquals(IntSize(800, 600), service.resolveTargetSize(IntSize(802, 600), Size(802f, 600f)))
    }

    @Test
    fun `invalid or too small dimensions fail before media allocation`() {
        val service = MediaService()
        for (edge in listOf(-1, 0, 1, 2, 3)) {
            for (size in listOf(IntSize(edge, 600), IntSize(600, edge))) {
                for (source in RealtimeMediaSource.entries) {
                    assertThrows(XmaxError::class.java) { service.resolveMediaSize(size, source) }
                }
                assertThrows(XmaxError::class.java) { service.resolveTargetSize(size, Size(720f, 1280f)) }
            }
        }
        for (edge in listOf(Float.NaN, Float.POSITIVE_INFINITY, 0f, -1f, 3f)) {
            for (bounds in listOf(Size(edge, 1280f), Size(720f, edge))) {
                assertThrows(XmaxError::class.java) { service.resolveTargetSize(IntSize(720, 1280), bounds) }
            }
        }
        assertThrows(XmaxError::class.java) { service.resolveMediaSize(IntSize(Int.MAX_VALUE, 4), RealtimeMediaSource.VIDEO) }
    }
}
