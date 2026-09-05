package ai.xmax.sdk.foundation.rtc

import com.ss.bytertc.engine.video.IVideoFrame
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Test

class VolcRemoteVideoFrameSinkTest {
    @Test
    fun `sink accepts successive valid processed frames without retaining native buffers`() {
        val frames = mutableListOf<Pair<Int, Int>>()
        val sink = VolcRemoteVideoFrameSink { width, height -> frames += width to height }

        sink.onFrame(frame(0, 1280))
        sink.onFrame(frame(704, -1))
        sink.onFrame(frame(704, 1280))
        sink.onFrame(frame(1280, 704))

        assertEquals(listOf(704 to 1280, 1280 to 704), frames)
    }

    private fun frame(width: Int, height: Int): IVideoFrame = Proxy.newProxyInstance(
        IVideoFrame::class.java.classLoader,
        arrayOf(IVideoFrame::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "width" -> width
            "height" -> height
            else -> throw AssertionError("Readiness must not access or retain native buffers: " + method.name)
        }
    } as IVideoFrame
}
