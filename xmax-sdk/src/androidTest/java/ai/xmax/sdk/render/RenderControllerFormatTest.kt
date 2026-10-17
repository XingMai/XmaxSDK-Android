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
import ai.xmax.sdk.foundation.rtc.RemoteStream
import ai.xmax.sdk.foundation.rtc.RtcManaging
import ai.xmax.sdk.foundation.rtc.RtcRemoteVideoFrame
import ai.xmax.sdk.foundation.rtc.RtcRemoteVideoSink
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
public class RenderControllerFormatTest {
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
        controller.registerRemoteTrack(track) {}
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
