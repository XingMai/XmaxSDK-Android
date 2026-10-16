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
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
public class InteractionControllerTest {
    @Test
    public fun containCoordinatesFollowDisplayedResolutionWithoutCanvasPadding() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val received = mutableListOf<List<RealtimePoint>>()
        val controller = InteractionController(listener = { _, points -> received += points }, scope = scope)
        try {
            controller.startInteraction("task", RealtimeVideoFormat(1280, 720, 30), useDisplayedSize = true)
            val frame = InteractionFrame(
                points = listOf(PointF(640f, 500f)),
                viewportSize = SizeF(1280f, 1000f),
                contentMode = VideoContentMode.FIT,
                displayedVideoFormat = RealtimeVideoFormat(1024, 576, 24),
            )
            controller.submitInteraction(frame)
            assertEquals(listOf(RealtimePoint(512.0, 288.0)), received.last())

            controller.submitInteraction(frame.copy(displayedVideoFormat = RealtimeVideoFormat(640, 360, 20)))
            assertEquals(listOf(RealtimePoint(320.0, 180.0)), received.last())

            controller.submitInteraction(frame.copy(points = listOf(PointF(640f, 10f))))
            controller.submitInteraction(frame.copy(displayedVideoFormat = null))
            assertEquals(2, received.size)

            controller.startInteraction("legacy", RealtimeVideoFormat(1280, 720, 30), useDisplayedSize = false)
            controller.submitInteraction(frame)
            assertEquals(listOf(RealtimePoint(640.0, 360.0)), received.last())
        } finally {
            controller.stopInteraction()
            scope.cancel()
        }
    }
}
