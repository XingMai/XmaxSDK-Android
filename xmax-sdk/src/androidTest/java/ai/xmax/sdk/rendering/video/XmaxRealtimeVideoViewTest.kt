package ai.xmax.sdk.rendering.video

import ai.xmax.sdk.DefaultTrajectoryEffectRenderer
import ai.xmax.sdk.RealtimeVideoTrack
import ai.xmax.sdk.VideoContentMode
import ai.xmax.sdk.XmaxRealtimeVideoView
import ai.xmax.sdk.XmaxVideoView
import ai.xmax.sdk.foundation.rtc.RemoteStream
import ai.xmax.sdk.foundation.rtc.RtcManaging
import ai.xmax.sdk.rendering.RenderController
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.Surface
import android.view.TextureView
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

public class RealtimeVideoTestActivity : Activity()

/** Uses real hardware TextureViews and buffers; no RTC connection or media permissions needed. */
@RunWith(AndroidJUnit4::class)
public class XmaxRealtimeVideoViewTest {
    @Test
    public fun sdkStopAndResetCoverRemoteBeforeUnbindWithoutWaitingForApplicationTrackUpdate() = withView { h ->
        val remote = TextureSource(RealtimeVideoTrack("remote"))
        val stream = RemoteStream("room", "bot")
        var receiver: ((Int, Int) -> Unit)? = null
        val teardownStates = mutableListOf<Pair<Boolean, Boolean>>()
        val rtc = Proxy.newProxyInstance(
            RtcManaging::class.java.classLoader,
            arrayOf(RtcManaging::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getRenderLibraryName" -> "test"
                "setRemoteVideoFrameListener" -> {
                    @Suppress("UNCHECKED_CAST")
                    receiver = args!![1] as ((Int, Int) -> Unit)?
                    null
                }
                "bindRemoteVideo" -> {
                    remote.texture = args!![1] as FrameReportingTextureView
                    remote.texture.surfaceTextureListener = remote
                    null
                }
                "unbindRemoteVideo" -> {
                    teardownStates += (Looper.myLooper() == Looper.getMainLooper()) to
                        (h.frontView === h.localView && !h.remoteView.isFrameDisplayEnabled &&
                            !h.remoteView.isInteractionEnabled)
                    // Simulate the native renderer clearing its surface during teardown.
                    remote.draw(Color.BLACK)
                    null
                }
                else -> throw AssertionError("Unexpected RTC operation: " + method.name)
            }
        } as RtcManaging
        val controller = RenderController(rtc)
        h.ui {
            h.tracks += remote.track
            controller.registerRemoteTrack(remote.track) {}
            h.view.remoteTrack = remote.track
        }
        var oldSurface: FrameReportingTextureView? = null
        var oldSurfaceCallback: ((FrameReportingTextureView) -> Unit)? = null
        var oldDisplayCallback: ((RealtimeVideoTrack) -> Unit)? = null
        try {
            repeat(2) { round ->
                h.ui {
                    controller.setRemoteStream(stream)
                    receiver!!(704, 1280)
                    if (round > 0) {
                        assertNotSame(oldSurface, remote.texture)
                        oldSurfaceCallback!!(oldSurface!!)
                        oldDisplayCallback!!(remote.track)
                    }
                    assertSame(h.localView, h.frontView)
                }
                h.await { remote.texture.isAvailable }
                h.assertColor(Color.GREEN)
                oldSurface = remote.texture
                oldSurfaceCallback = remote.texture.onFrameDisplayed!!
                oldDisplayCallback = h.remoteView.frameDisplayHandler!!
                remote.draw(Color.RED)
                h.await { h.frontView === h.remoteView }
                if (round == 0) {
                    // Stop during the fade, leaving the same public track assigned.
                    h.ui { controller.setRemoteStream(null) }
                } else {
                    h.await { h.remoteView.alpha == 1f }
                    h.assertColor(Color.RED)
                    // The core disconnect path starts on a worker, not on the UI thread.
                    runBlocking(Dispatchers.Default) { controller.resetRemoteTrack(remote.track) }
                }
                h.ui {
                    assertSame(remote.track, h.view.remoteTrack)
                    oldSurfaceCallback(oldSurface)
                    oldDisplayCallback(remote.track)
                    assertSame(h.localView, h.frontView)
                    assertFalse(h.remoteView.isInteractionEnabled)
                }
                SystemClock.sleep(350)
                h.assertColor(Color.GREEN)
            }
            assertEquals(listOf(true to true, true to true), teardownStates)
            assertEquals(1, h.localAttachCount)
        } finally {
            runBlocking { controller.resetRemoteTrack(remote.track) }
        }
    }

    @Test
    public fun frameReadinessReleasesSinkBeforeBindingCanvasOnEveryGeneration() = withView { h ->
        val remote = TextureSource(RealtimeVideoTrack("remote"))
        val stream = RemoteStream("room", "bot")
        val calls = mutableListOf<String>()
        var receiver: ((Int, Int) -> Unit)? = null
        val rtc = Proxy.newProxyInstance(
            RtcManaging::class.java.classLoader,
            arrayOf(RtcManaging::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getRenderLibraryName" -> "test"
                "setRemoteVideoFrameListener" -> {
                    @Suppress("UNCHECKED_CAST")
                    val next = args!![1] as ((Int, Int) -> Unit)?
                    receiver = next
                    calls += if (next == null) "release-sink" else "receive-frames"
                    null
                }
                "bindRemoteVideo" -> {
                    assertEquals(null, receiver)
                    calls += "bind-canvas"
                    remote.texture = args!![1] as FrameReportingTextureView
                    remote.texture.surfaceTextureListener = remote
                    null
                }
                "unbindRemoteVideo" -> { calls += "unbind-canvas"; null }
                else -> throw AssertionError("Unexpected RTC operation: " + method.name)
            }
        } as RtcManaging
        val controller = RenderController(rtc)
        h.ui {
            h.tracks += remote.track
            controller.registerRemoteTrack(remote.track) {}
        }
        try {
            repeat(2) {
                h.ui {
                    calls.clear()
                    controller.setRemoteStream(stream)
                    h.view.remoteTrack = remote.track
                    assertEquals(listOf("receive-frames"), calls)
                    receiver!!(704, 1280)
                    assertEquals(listOf("receive-frames", "release-sink", "bind-canvas"), calls)
                    assertSame(h.localView, h.frontView)
                }
                runBlocking { controller.waitUntilRemoteFrameReady() }
                h.await { remote.texture.isAvailable }
                h.assertColor(Color.GREEN)
                remote.draw(Color.RED)
                h.await { h.frontView === h.remoteView && h.remoteView.alpha == 1f }
                h.assertColor(Color.RED)
                h.ui {
                    h.view.remoteTrack = null
                    controller.setRemoteStream(null)
                }
            }
        } finally {
            runBlocking { controller.resetRemoteTrack(remote.track) }
        }
    }

    @Test
    public fun localPreviewCoversEmptySurfaceUntilRemoteFrameAndReturnsImmediatelyOnClear() = withView { h ->
        val remote = h.textureTrack()
        h.ui { h.view.remoteTrack = remote.track }
        h.await { remote.texture.isAvailable }
        h.assertColor(Color.GREEN)
        h.ui {
            assertSame(h.localView, h.frontView)
            assertFalse(h.remoteView.isInteractionEnabled)
            // A layout update without a buffer must not count as a rendered frame.
            remote.texture.layout(0, 0, 180, 180)
        }
        h.assertColor(Color.GREEN)
        remote.draw(Color.RED)
        h.await { h.frontView === h.remoteView && h.remoteView.alpha == 1f }
        h.assertColor(Color.RED)
        h.ui {
            assertTrue(remote.availableCount > 0)
            assertTrue(remote.updateCount > 0)
            assertEquals(1, h.localAttachCount)
            assertFalse(h.localView.isInteractionEnabled)
            assertTrue(h.remoteView.isInteractionEnabled)
            h.view.remoteTrack = null
            assertSame(h.localView, h.frontView)
            assertFalse(h.remoteView.isInteractionEnabled)
            assertEquals(1, remote.detachCount)
            assertEquals(1, remote.destroyedCount)
        }
        h.assertColor(Color.GREEN)
    }

    @Test
    public fun switchingTracksAndClearingDuringFadeRejectsOldSurfaceAndTrackCallbacks() = withView { h ->
        // Identical IDs intentionally verify track identity rather than string-ID comparison.
        val first = h.textureTrack("remote")
        val second = h.textureTrack("remote")
        h.ui { h.view.remoteTrack = first.track }
        h.await { first.texture.isAvailable }
        val oldSurfaceCallback = first.texture.onFrameDisplayed!!
        val oldTrackCallback = h.remoteView.frameDisplayHandler!!
        first.draw(Color.RED)
        h.await { h.frontView === h.remoteView }
        h.ui {
            h.view.remoteTrack = second.track
            assertNotSame(first.texture, second.texture)
            oldSurfaceCallback(first.texture)
            oldTrackCallback(first.track)
            assertSame(h.localView, h.frontView)
        }
        h.await { second.texture.isAvailable }
        h.assertColor(Color.GREEN)
        second.draw(Color.BLUE)
        h.await { h.frontView === h.remoteView }
        h.ui {
            h.view.remoteTrack = null
            oldTrackCallback(second.track)
            assertSame(h.localView, h.frontView)
        }
        // Let the cancelled animator's original duration elapse before inspecting the result.
        SystemClock.sleep(350)
        h.assertColor(Color.GREEN)
        h.ui {
            assertSame(h.localView, h.frontView)
            assertEquals(1, h.localAttachCount)
            assertEquals(1, first.detachCount)
            assertEquals(1, second.detachCount)
        }
    }

    @Test
    public fun detachReattachAndContentModeChangeRequireFreshRemoteBuffers() = withView { h ->
        val remote = h.textureTrack()
        h.ui { h.view.remoteTrack = remote.track }
        h.await { remote.texture.isAvailable }
        remote.draw(Color.RED)
        h.await { h.frontView === h.remoteView && h.remoteView.alpha == 1f }
        val oldTexture = remote.texture
        val oldCallback = oldTexture.onFrameDisplayed!!
        h.ui {
            h.root.removeView(h.view)
            assertEquals(1, remote.detachCount)
            h.root.addView(h.view)
            oldCallback(oldTexture)
            assertSame(h.localView, h.frontView)
            assertNotSame(oldTexture, remote.texture)
        }
        h.await { remote.texture.isAvailable }
        h.assertColor(Color.GREEN)
        remote.draw(Color.BLUE)
        h.await { h.frontView === h.remoteView && h.remoteView.alpha == 1f }
        h.assertColor(Color.BLUE)
        h.ui {
            h.view.videoContentMode = VideoContentMode.FIT
            h.view.isInteractionEnabled = false
            val renderer = DefaultTrajectoryEffectRenderer(h.view.context)
            h.view.trajectoryRenderer = renderer
            assertSame(renderer, h.remoteView.trajectoryRenderer)
            assertEquals(null, h.localView.trajectoryRenderer)
            assertEquals(VideoContentMode.FIT, h.remoteView.videoContentMode)
            assertEquals(VideoContentMode.FIT, h.localView.videoContentMode)
            assertSame(h.localView, h.frontView)
        }
        h.await { remote.texture.isAvailable }
        remote.draw(Color.RED)
        h.await { h.frontView === h.remoteView && h.remoteView.alpha == 1f }
        h.ui { assertFalse(h.remoteView.isInteractionEnabled) }
        h.assertColor(Color.RED)
    }

    @Test
    public fun bitmapPresentationAlsoRevealsRemoteAndRepeatedAssignmentDoesNotRebind() = withView { h ->
        val track = RealtimeVideoTrack("bitmap")
        var attachCount = 0
        h.register(track, VideoRenderBinding(
            libraryName = "test",
            attachHandler = { view, mode ->
                attachCount++
                view.displayDecodedVideoBitmap(bitmap(Color.BLUE), mode)
            },
            detachHandler = XmaxVideoView::clearDecodedVideoPreview,
        ))
        h.ui {
            h.view.remoteTrack = track
            assertSame(h.localView, h.frontView)
        }
        h.await { h.frontView === h.remoteView && h.remoteView.alpha == 1f }
        h.assertColor(Color.BLUE)
        h.ui {
            repeat(5) { h.view.remoteTrack = track }
            assertEquals(1, attachCount)
            assertSame(h.remoteView, h.frontView)
        }
    }

    private fun withView(test: (Harness) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch<RealtimeVideoTestActivity>(
            Intent(context, RealtimeVideoTestActivity::class.java),
        ).use { scenario ->
            val harness = Harness(scenario)
            try {
                harness.setUp()
                test(harness)
            } finally {
                harness.ui {
                    harness.view.remoteTrack = null
                    harness.view.localTrack = null
                }
                harness.tracks.forEach(VideoRenderRegistry::unregister)
            }
        }
    }

    private class Harness(val scenario: ActivityScenario<RealtimeVideoTestActivity>) {
        lateinit var root: FrameLayout
        lateinit var view: XmaxRealtimeVideoView
        lateinit var localView: XmaxVideoView
        lateinit var remoteView: XmaxVideoView
        val tracks = mutableListOf<RealtimeVideoTrack>()
        var localAttachCount = 0
        val frontView get() = view.getChildAt(view.childCount - 1)

        fun setUp() = ui { activity ->
            root = FrameLayout(activity)
            view = XmaxRealtimeVideoView(activity)
            root.addView(view, FrameLayout.LayoutParams(200, 200))
            activity.setContentView(root)
            localView = view.getChildAt(1) as XmaxVideoView
            remoteView = view.getChildAt(0) as XmaxVideoView
            val localTrack = RealtimeVideoTrack("local")
            register(localTrack, VideoRenderBinding(
                libraryName = "test",
                attachHandler = { target, mode ->
                    localAttachCount++
                    target.displayDecodedVideoBitmap(bitmap(Color.GREEN), mode)
                },
                detachHandler = XmaxVideoView::clearDecodedVideoPreview,
            ))
            view.localTrack = localTrack
        }

        fun register(track: RealtimeVideoTrack, binding: VideoRenderBinding) {
            tracks += track
            VideoRenderRegistry.register(track, binding)
        }

        fun textureTrack(id: String = "remote"): TextureSource = TextureSource(RealtimeVideoTrack(id)).also { source ->
            register(source.track, VideoRenderBinding(
                libraryName = "test",
                attachHandler = { target, _ ->
                    target.prepareRtcVideoRendering()
                    source.texture = target.rtcRenderView as FrameReportingTextureView
                    source.texture.surfaceTextureListener = source
                    assertSame(source, source.texture.surfaceTextureListener)
                },
                detachHandler = { source.detachCount++ },
            ))
        }

        fun ui(action: (RealtimeVideoTestActivity) -> Unit) {
            scenario.onActivity(action)
        }

        fun await(condition: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 5_000
            do {
                var satisfied = false
                ui { satisfied = condition() }
                if (satisfied) return
                SystemClock.sleep(16)
            } while (SystemClock.uptimeMillis() < deadline)
            throw AssertionError("Timed out waiting for the expected rendering state")
        }

        fun assertColor(expected: Int) {
            await { view.isLaidOut }
            val pixels = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            val latch = CountDownLatch(1)
            var result = -1
            ui { activity ->
                val position = IntArray(2)
                view.getLocationInWindow(position)
                val x = position[0] + view.width / 2
                val y = position[1] + view.height / 2
                PixelCopy.request(activity.window, Rect(x, y, x + 1, y + 1), pixels, {
                    result = it
                    latch.countDown()
                }, Handler(Looper.getMainLooper()))
            }
            assertTrue("PixelCopy did not complete", latch.await(5, TimeUnit.SECONDS))
            assertEquals(PixelCopy.SUCCESS, result)
            assertEquals(expected, pixels.getPixel(0, 0))
            pixels.recycle()
        }
    }

    private class TextureSource(val track: RealtimeVideoTrack) : TextureView.SurfaceTextureListener {
        lateinit var texture: FrameReportingTextureView
        var availableCount = 0
        var updateCount = 0
        var destroyedCount = 0
        var detachCount = 0

        fun draw(color: Int) {
            Surface(checkNotNull(texture.surfaceTexture)).let { surface ->
                try {
                    val canvas = surface.lockCanvas(null)
                    canvas.drawColor(color)
                    surface.unlockCanvasAndPost(canvas)
                } finally {
                    surface.release()
                }
            }
        }

        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            availableCount++
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            destroyedCount++
            return true
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
            updateCount++
        }
    }

    private companion object {
        fun bitmap(color: Int): Bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply {
            eraseColor(color)
        }
    }
}
