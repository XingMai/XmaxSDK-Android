//
//  RenderControllerFormatTest.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.render

import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.RealtimeVideoFrame
import ai.xmax.sdk.RealtimeVideoTrack
import ai.xmax.sdk.TrajectoryEffectRendering
import ai.xmax.sdk.TrajectoryID
import ai.xmax.sdk.TrajectoryPoint
import ai.xmax.sdk.VideoContentMode
import ai.xmax.sdk.foundation.rtc.RemoteStream
import ai.xmax.sdk.foundation.rtc.RtcManaging
import ai.xmax.sdk.foundation.rtc.RtcRemoteVideoFrame
import ai.xmax.sdk.foundation.rtc.RtcRemoteVideoSink
import ai.xmax.sdk.render.trajectory.TrajectoryOverlayView
import ai.xmax.sdk.render.trajectory.TrajectoryRegistry
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.compose.ui.unit.IntSize
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
public class RenderControllerFormatTest {
    @Test
    public fun downlinkFramesDoNotResetTrajectoryOrChangeItsMediaBasis() = runBlocking {
        var sink: RtcRemoteVideoSink? = null
        val rtc = Proxy.newProxyInstance(
            RtcManaging::class.java.classLoader,
            arrayOf(RtcManaging::class.java),
        ) { _, method, arguments ->
            if (method.name == "setRemoteVideoFrameListener") sink = arguments[1] as RtcRemoteVideoSink?
            null
        } as RtcManaging
        val controller = RenderController(rtc)
        val track = RealtimeVideoTrack("remote", RealtimeVideoFormat(800, 600, 24))
        controller.registerRemoteTrack(track, IntSize(1280, 720)) {}
        try {
            controller.setRemoteStream(RemoteStream("room", "bot"))
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.runOnMainSync {
                val context = instrumentation.targetContext
                val renderedPoints = mutableListOf<TrajectoryPoint>()
                var resetCount = 0
                val overlay = TrajectoryOverlayView(context)
                overlay.setRenderer(object : TrajectoryEffectRendering {
                    override val view = View(context)
                    override fun renderBegan(points: List<TrajectoryPoint>) { renderedPoints += points }
                    override fun renderMoved(points: List<TrajectoryPoint>) = Unit
                    override fun renderEnded(identifiers: List<TrajectoryID>) = Unit
                    override fun reset() { resetCount++ }
                })
                overlay.layout(0, 0, 1280, 1000)
                val binding = checkNotNull(TrajectoryRegistry.binding(track))
                binding.attach(overlay, VideoContentMode.FIT)
                val now = SystemClock.uptimeMillis()
                val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 320f, 320f, 0)
                val up = MotionEvent.obtain(now, now + 1, MotionEvent.ACTION_UP, 320f, 320f, 0)
                try {
                    assertTrue(overlay.onTouchEvent(down))
                    val resetBeforeFrames = resetCount
                    requireNotNull(sink).onFirstFrame(800, 600)
                    requireNotNull(sink).onFrame(frame(300, 400, rotated = true))
                    assertEquals(resetBeforeFrames, resetCount)
                    overlay.onTouchEvent(up)
                    assertTrue(overlay.onTouchEvent(down))
                    assertEquals(2, renderedPoints.size)
                    for (point in renderedPoints) {
                        assertEquals(0.25f, point.normalizedLocation.x, 0.000001f)
                        assertEquals(0.25f, point.normalizedLocation.y, 0.000001f)
                    }
                } finally {
                    overlay.onTouchEvent(up)
                    binding.detach(overlay)
                    down.recycle()
                    up.recycle()
                }
            }
        } finally {
            controller.resetRemoteTrack(track)
        }
    }

    @Test
    public fun displayedDimensionsFollowCurrentFramesAndIgnoreReplacedStreams() = runBlocking {
        var sink: RtcRemoteVideoSink? = null
        val rtc = Proxy.newProxyInstance(
            RtcManaging::class.java.classLoader,
            arrayOf(RtcManaging::class.java),
        ) { _, method, arguments ->
            if (method.name == "setRemoteVideoFrameListener") sink = arguments[1] as RtcRemoteVideoSink?
            null
        } as RtcManaging
        val controller = RenderController(rtc)
        val track = RealtimeVideoTrack("remote", RealtimeVideoFormat(1280, 720, 30))
        controller.registerRemoteTrack(track, IntSize(1280, 720)) {}
        try {
            controller.setRemoteStream(RemoteStream("room", "first"))
            val first = requireNotNull(sink)
            first.onFirstFrame(1024, 576)
            controller.waitUntilRemoteFrameReady()
            assertEquals(RealtimeVideoFormat(1024, 576, 30), track.videoFormat)

            first.onFrame(frame(360, 640, rotated = true))
            assertEquals(RealtimeVideoFormat(640, 360, 30), track.videoFormat)

            controller.setRemoteStream(RemoteStream("room", "second"))
            first.onFrame(frame(1920, 1080))
            first.onFirstFrame(1920, 1080)
            assertEquals(RealtimeVideoFormat(640, 360, 30), track.videoFormat)

            requireNotNull(sink).onFirstFrame(1280, 720)
            controller.waitUntilRemoteFrameReady()
            assertEquals(RealtimeVideoFormat(1280, 720, 30), track.videoFormat)
        } finally {
            controller.resetRemoteTrack(track)
        }
    }

    private fun frame(width: Int, height: Int, rotated: Boolean = false): RtcRemoteVideoFrame = object : RtcRemoteVideoFrame {
        override val width = width
        override val height = height
        override val displayWidth = if (rotated) height else width
        override val displayHeight = if (rotated) width else height
        override fun copy(): RealtimeVideoFrame = error("Frames without a consumer must not be copied")
    }
}
