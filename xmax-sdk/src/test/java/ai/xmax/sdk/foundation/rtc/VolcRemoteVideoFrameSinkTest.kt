package ai.xmax.sdk.foundation.rtc

import com.ss.bytertc.engine.data.VideoPixelFormat
import com.ss.bytertc.engine.data.VideoRotation
import com.ss.bytertc.engine.video.IVideoFrame
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ReadOnlyBufferException
import org.junit.Assert.*
import org.junit.Test

class VolcRemoteVideoFrameSinkTest {
    @Test
    fun `sink retains only the latest valid frame and rejects callbacks after release`() {
        val received = mutableListOf<Pair<Int, Int>>()
        val sink = VolcRemoteVideoFrameSink(RtcRemoteVideoSink { w, h -> received += w to h })
        val first = NativeFrame(3, 3)
        val second = NativeFrame(4, 2)
        sink.onFrame(NativeFrame(0, 3).frame)
        sink.onFrame(first.frame)
        assertEquals(1, first.references)
        sink.onFrame(second.frame)
        assertEquals(0, first.references)
        assertEquals(1, second.references)
        sink.release()
        sink.release()
        assertEquals(0, second.references)
        sink.onFrame(first.frame)
        assertEquals(0, first.references)
        assertEquals(listOf(3 to 3, 4 to 2), received)
    }

    @Test
    fun `render binding replays latest frame and continuous output survives detach and reattach`() {
        val delivered = mutableListOf<Int>()
        val sink = VolcRemoteVideoFrameSink(object : RtcRemoteVideoSink {
            override fun onFirstFrame(width: Int, height: Int) = Unit
            override fun onFrame(frame: RtcRemoteVideoFrame) { delivered += frame.width }
        })
        val first = NativeFrame(3, 3)
        val second = NativeFrame(4, 2)
        val third = NativeFrame(5, 3)
        val renderer = Renderer()
        sink.onFrame(first.frame)
        sink.bind { renderer }
        assertSame(first.frame, renderer.frames.single())
        sink.onFrame(second.frame)
        assertSame(second.frame, renderer.frames.last())
        sink.unbind()
        assertEquals(1, renderer.releases)
        sink.onFrame(third.frame)
        assertEquals(2, renderer.frames.size)
        val next = Renderer()
        sink.bind { next }
        assertSame(third.frame, next.frames.single())
        assertEquals(listOf(3, 4, 5), delivered)
        sink.release()
        assertEquals(1, next.releases)
        assertEquals(0, third.references)
        assertTrue(runCatching { sink.bind { Renderer() } }.isFailure)
    }

    @Test
    fun `renderer release failure still releases native frame and deactivates sink`() {
        val sink = VolcRemoteVideoFrameSink(RtcRemoteVideoSink { _, _ -> })
        val frame = NativeFrame(3, 3)
        sink.onFrame(frame.frame)
        sink.bind {
            object : RemoteFrameRenderer {
                override fun render(frame: IVideoFrame) = Unit
                override fun release() { throw IllegalStateException("EGL cleanup failed") }
            }
        }
        assertTrue(runCatching { sink.release() }.isFailure)
        assertEquals(0, frame.references)
        sink.onFrame(frame.frame)
        assertEquals(0, frame.references)
        sink.release()
    }

    private class Renderer : RemoteFrameRenderer {
        val frames = mutableListOf<IVideoFrame>()
        var releases = 0
        override fun render(frame: IVideoFrame) { frames += frame }
        override fun release() { releases++ }
    }

    @Test
    fun `copy removes stride padding preserves timestamp rotation and owns readonly pixels`() {
        val native = NativeFrame(3, 3)
        val copy = VolcRemoteVideoFrame(native.frame).copy()
        assertEquals(42_123L, copy.presentationTimeUs)
        assertNull(copy.durationUs)
        assertEquals(90, copy.rotationDegrees)
        assertEquals(3, copy.yStride)
        assertEquals(2, copy.uStride)
        assertArrayEquals(byteArrayOf(0, 1, 2, 5, 6, 7, 10, 11, 12), copy.yData.bytes())
        assertArrayEquals(byteArrayOf(20, 21, 24, 25), copy.uData.bytes())
        native.planes.forEach { plane -> repeat(plane.limit()) { plane.put(it, 99) } }
        assertEquals(0, copy.yData.get(0).toInt())
        copy.yData.position(3)
        assertEquals(0, copy.yData.position())
        assertTrue(runCatching { copy.yData.put(0, 1) }.exceptionOrNull() is ReadOnlyBufferException)
        assertEquals(0, native.planes[0].position())
    }

    @Test
    fun `truncated I420 buffers report a single processing failure and release retained frame`() {
        val native = NativeFrame(3, 3)
        native.planes[0].limit(2)
        var errors = 0
        var firstFrames = 0
        val sink = VolcRemoteVideoFrameSink(object : RtcRemoteVideoSink {
            override fun onFirstFrame(width: Int, height: Int) { firstFrames++ }
            override fun onFrame(frame: RtcRemoteVideoFrame) { frame.copy() }
            override fun onError(error: ai.xmax.sdk.XmaxError) { errors++ }
        })
        sink.onFrame(native.frame)
        sink.onFrame(native.frame)
        assertEquals(1, errors)
        assertEquals(0, firstFrames)
        sink.release()
        assertEquals(0, native.references)
    }

    private fun ByteBuffer.bytes() = ByteArray(remaining()).also { get(it) }

    private class NativeFrame(val width: Int, val height: Int) {
        var references = 0
        val strides = intArrayOf(width + 2, (width + 1) / 2 + 2, (width + 1) / 2 + 2)
        val planes = Array(3) { index ->
            ByteBuffer.allocate(strides[index] * if (index == 0) height else (height + 1) / 2).apply {
                repeat(capacity()) { put(it, (index * 20 + it).toByte()) }
            }
        }
        val frame: IVideoFrame = Proxy.newProxyInstance(
            IVideoFrame::class.java.classLoader, arrayOf(IVideoFrame::class.java),
        ) { _, method, args ->
            when (method.name) {
                "width" -> width
                "height" -> height
                "timestampUs" -> 42_123L
                "rotation" -> VideoRotation.VIDEO_ROTATION_90
                "pixelFormat" -> VideoPixelFormat.I420
                "numberOfPlanes" -> 3
                "planeData" -> planes[args!![0] as Int]
                "planeStride" -> strides[args!![0] as Int]
                "addRef" -> { references++; null }
                "releaseRef" -> { references--; references.toLong() }
                else -> throw AssertionError(method.name)
            }
        } as IVideoFrame
    }
}
