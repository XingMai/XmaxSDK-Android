package ai.xmax.sdk.foundation.rtc

import com.ss.bytertc.engine.video.IVideoFrame
import com.ss.bytertc.engine.video.IVideoSink

/** Each registration captures its own listener. Native frames never escape this callback. */
internal class VolcRemoteVideoFrameSink(
    private val listener: (Int, Int) -> Unit,
) : IVideoSink {
    override fun onFrame(frame: IVideoFrame) {
        val width = frame.width()
        val height = frame.height()
        if (width > 0 && height > 0) listener(width, height)
    }

    override fun getRenderElapse(): Int = 0
}
