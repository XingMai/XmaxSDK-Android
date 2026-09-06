package ai.xmax.sdk.foundation.rtc

import ai.xmax.sdk.RealtimeVideoFrame
import ai.xmax.sdk.VideoContentMode
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import android.view.TextureView
import android.view.View
import com.bytedance.realx.video.RendererCommon
import com.ss.bytertc.engine.data.VideoPixelFormat
import com.ss.bytertc.engine.ui.VideoFrameRender
import com.ss.bytertc.engine.video.IVideoFrame
import com.ss.bytertc.engine.video.IVideoSink

/** 持续接收后处理帧；渲染与帧回调共用输入，原生引用最多保留最新一帧供视图重新绑定。 */
internal class VolcRemoteVideoFrameSink(
    private val listener: RtcRemoteVideoSink,
) : IVideoSink {
    private val lock = Any()
    private var active = true
    private var failed = false
    private var latest: IVideoFrame? = null
    private var renderer: RemoteFrameRenderer? = null

    override fun onFrame(frame: IVideoFrame) {
        var failure: XmaxError? = null
        synchronized(lock) {
            if (!active || failed || frame.width() <= 0 || frame.height() <= 0) return
            try {
                require(frame.pixelFormat() == VideoPixelFormat.I420) { "Remote sink requires I420 frames" }
                frame.addRef()
                val previous = latest
                latest = frame
                previous?.releaseRef()
                listener.onFrame(VolcRemoteVideoFrame(frame))
                renderer?.render(frame)
                listener.onFirstFrame(frame.width(), frame.height())
            } catch (error: Exception) {
                failed = true
                failure = XmaxError(XmaxErrorCode.RTC_ERROR, "Remote video frame processing failed", cause = error)
            }
        }
        failure?.let(listener::onError)
    }

    fun bind(createRenderer: () -> RemoteFrameRenderer) = synchronized(lock) {
        check(active && !failed) { "Remote video sink is no longer active" }
        releaseRenderer()
        val next = createRenderer()
        renderer = next
        try {
            latest?.let(next::render)
        } catch (error: Throwable) {
            renderer = null
            try { next.release() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
            throw error
        }
    }

    fun unbind() = synchronized(lock) { releaseRenderer() }

    fun release() = synchronized(lock) {
        active = false
        try {
            releaseRenderer()
        } finally {
            val previous = latest
            latest = null
            previous?.releaseRef()
        }
    }

    private fun releaseRenderer() {
        val previous = renderer
        renderer = null
        previous?.release()
    }

    override fun getRenderElapse(): Int = 0
}

internal interface RemoteFrameRenderer {
    fun render(frame: IVideoFrame)
    fun release()
}

/** 使用 RTC 随包提供的 EGL 渲染器；所有权属于持续 sink，不再切回原生 VideoCanvas。 */
internal class VolcRemoteFrameRenderer(view: View, contentMode: VideoContentMode) : RemoteFrameRenderer {
    private val renderer = VideoFrameRender("XmaxRemoteVideo")

    init {
        require(view is TextureView) { "Remote video rendering requires a TextureView" }
        renderer.setRenderView(view, null)
        try {
            // 输入固定为 I420，不需要与解码器共享纹理上下文。
            renderer.init(null)
            renderer.setScalingType(when (contentMode) {
                VideoContentMode.FIT -> RendererCommon.ScalingType.SCALE_ASPECT_FIT
                VideoContentMode.FILL -> RendererCommon.ScalingType.SCALE_ASPECT_FILL
            })
            renderer.setMirror(false)
            renderer.onStart()
        } catch (error: Throwable) {
            try { renderer.release() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
            throw error
        }
    }

    override fun render(frame: IVideoFrame) = renderer.consumeVideoFrame(frame)
    override fun release() = renderer.release()
}

internal class VolcRemoteVideoFrame(private val frame: IVideoFrame) : RtcRemoteVideoFrame {
    override val width: Int get() = frame.width()
    override val height: Int get() = frame.height()

    override fun copy(): RealtimeVideoFrame {
        require(width > 0 && height > 0 && frame.pixelFormat() == VideoPixelFormat.I420 && frame.numberOfPlanes() == 3)
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        return RealtimeVideoFrame(
            width = width,
            height = height,
            presentationTimeUs = frame.timestampUs(),
            durationUs = null,
            rotationDegrees = frame.rotation().value(),
            y = copyPlane(0, width, height),
            u = copyPlane(1, chromaWidth, chromaHeight),
            v = copyPlane(2, chromaWidth, chromaHeight),
        )
    }

    private fun copyPlane(index: Int, columns: Int, rows: Int): ByteArray {
        val source = requireNotNull(frame.planeData(index)).duplicate()
        val stride = frame.planeStride(index)
        require(stride >= columns && rows.toLong() * columns <= Int.MAX_VALUE)
        require((rows - 1L) * stride + columns <= source.remaining()) { "Truncated remote I420 plane $index" }
        val start = source.position()
        return ByteArray(columns * rows).also { bytes ->
            repeat(rows) { row ->
                source.position(start + row * stride)
                source.get(bytes, row * columns, columns)
            }
        }
    }
}
