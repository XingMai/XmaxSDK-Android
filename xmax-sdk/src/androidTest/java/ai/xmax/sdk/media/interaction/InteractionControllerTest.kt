//
//  InteractionControllerTest.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.media.interaction

import ai.xmax.sdk.RealtimePoint
import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.VideoContentMode
import android.graphics.PointF
import android.util.SizeF
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
public class InteractionControllerTest {
    @Test
    public fun coordinatesUseFixedMediaSizeAndIgnoreFitLetterboxing() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val received = mutableListOf<List<RealtimePoint>>()
        val controller = InteractionController(listener = { _, points -> received += points }, scope = scope)
        try {
            controller.startInteraction("task", RealtimeVideoFormat(1280, 720, 30))
            val frame = InteractionFrame(
                points = listOf(PointF(640f, 500f)),
                viewportSize = SizeF(1280f, 1000f),
                contentMode = VideoContentMode.FIT,
            )
            controller.submitInteraction(frame)
            assertEquals(listOf(RealtimePoint(0.5, 0.5)), received.last())

            controller.submitInteraction(frame.copy(points = listOf(PointF(640f, 10f))))
            assertEquals(1, received.size)
            controller.submitInteraction(frame.copy(points = listOf(PointF(320f, 320f))))
            assertEquals(2, received.size)
            assertEquals(listOf(RealtimePoint(0.25, 0.25)), received.last())
        } finally {
            controller.stopInteraction()
            scope.cancel()
        }
    }

    @Test
    public fun mappingUsesResolvedMediaAspectForFitLetterboxingAndFillCropping() {
        val viewport = SizeF(400f, 400f)
        val media = SizeF(200f, 100f)
        assertNull(InteractionCoordinateMapper.map(PointF(100f, 99f), viewport, media, VideoContentMode.FIT))
        assertEquals(RealtimePoint(0.25, 0.25), InteractionCoordinateMapper.map(PointF(100f, 150f), viewport, media, VideoContentMode.FIT))
        assertEquals(RealtimePoint(0.25, 0.25), InteractionCoordinateMapper.map(PointF(0f, 100f), viewport, media, VideoContentMode.FILL))
    }

    @Test
    public fun mappingClampsEdgesAndPreservesFractionalPrecision() {
        val size = SizeF(300f, 400f)
        val media = SizeF(900f, 1200f)
        assertEquals(RealtimePoint(0.0, 0.0), InteractionCoordinateMapper.map(PointF(-10f, -10f), size, media, VideoContentMode.FILL))
        assertEquals(RealtimePoint(1.0, 1.0), InteractionCoordinateMapper.map(PointF(300f, 400f), size, media, VideoContentMode.FILL))
        val point = checkNotNull(InteractionCoordinateMapper.map(PointF(0.431231f * 300, 0.56931f * 400), size, media, VideoContentMode.FIT))
        assertEquals(0.431231, point.x, 0.000001)
        assertEquals(0.56931, point.y, 0.000001)
    }

    @Test
    public fun invalidGeometryDoesNotProduceCoordinates() {
        val valid = SizeF(720f, 1280f)
        for (invalid in listOf(SizeF(0f, 0f), SizeF(-1f, 1280f))) {
            assertNull(InteractionCoordinateMapper.map(PointF(100f, 100f), invalid, valid, VideoContentMode.FIT))
            assertNull(InteractionCoordinateMapper.map(PointF(100f, 100f), valid, invalid, VideoContentMode.FIT))
        }
        assertNull(InteractionCoordinateMapper.map(PointF(Float.NaN, 100f), valid, valid, VideoContentMode.FIT))
    }
}
