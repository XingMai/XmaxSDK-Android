package ai.xmax.sdk.foundation.rtc

import io.agora.base.VideoFrame
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class AgoraRemoteVideoFrameTest {
    @Test fun `copy respects I420 strides timestamp rotation and releases temporary conversion`() {
        var releases = 0
        lateinit var buffer: VideoFrame.I420Buffer
        buffer = Proxy.newProxyInstance(VideoFrame.I420Buffer::class.java.classLoader,
            arrayOf(VideoFrame.I420Buffer::class.java)) { _, method, _ ->
            when (method.name) {
                "getWidth" -> 4
                "getHeight" -> 2
                "getStrideY" -> 5
                "getStrideU", "getStrideV" -> 3
                "getDataY" -> ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4, 99, 5, 6, 7, 8))
                "getDataU" -> ByteBuffer.wrap(byteArrayOf(9, 10))
                "getDataV" -> ByteBuffer.wrap(byteArrayOf(11, 12))
                "toI420" -> buffer
                "release" -> { releases++; null }
                "retain" -> null
                else -> error("Unexpected call: ${method.name}")
            }
        } as VideoFrame.I420Buffer
        val copy = AgoraRemoteVideoFrame(VideoFrame(buffer, 90, 12_345_000)).copy()
        assertEquals(4, copy.width)
        assertEquals(2, copy.height)
        assertEquals(90, copy.rotationDegrees)
        assertEquals(12_345L, copy.presentationTimeUs)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), copy.yData.bytes())
        assertArrayEquals(byteArrayOf(9, 10), copy.uData.bytes())
        assertArrayEquals(byteArrayOf(11, 12), copy.vData.bytes())
        assertEquals(1, releases)
    }

    private fun ByteBuffer.bytes() = ByteArray(remaining()).also { get(it) }
}
