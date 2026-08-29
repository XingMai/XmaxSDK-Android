package ai.xmax.sdk.rendering.trajectory

import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.VideoContentMode
import ai.xmax.sdk.media.interaction.InteractionFrame
import android.os.Handler
import android.os.Looper

/** 将渲染层触摸输入转交给媒体交互层。 */
internal class TrajectoryBinding(
    private val interactionListener: (InteractionFrame) -> Unit,
    videoFormat: RealtimeVideoFormat,
) {
    private val stateLock = Any()
    private var overlayView: TrajectoryOverlayView? = null
    private var contentMode = VideoContentMode.FILL
    private var videoFormat = videoFormat

    fun attach(view: TrajectoryOverlayView, contentMode: VideoContentMode) {
        synchronized(stateLock) {
            if (overlayView !== view) overlayView?.clearBinding(this)
            overlayView = view
            this.contentMode = contentMode
        }
        view.setBinding(this)
        view.setContentMode(contentMode)
        view.setVideoFormat(videoFormat)
    }

    fun detach(view: TrajectoryOverlayView) {
        val shouldDetach = synchronized(stateLock) {
            if (overlayView !== view) false else {
                overlayView = null
                true
            }
        }
        if (shouldDetach) view.clearBinding(this)
    }

    fun invalidate() {
        synchronized(stateLock) {
            overlayView.also { overlayView = null }
        }?.runOnMain { clearBinding(this@TrajectoryBinding) }
    }

    fun update(contentMode: VideoContentMode) {
        synchronized(stateLock) {
            this.contentMode = contentMode
            overlayView
        }?.runOnMain { setContentMode(contentMode) }
    }

    fun update(videoFormat: RealtimeVideoFormat) {
        synchronized(stateLock) {
            this.videoFormat = videoFormat
            overlayView
        }?.runOnMain { setVideoFormat(videoFormat) }
    }

    fun submit(frame: InteractionFrame) {
        interactionListener(frame)
    }

    private inline fun TrajectoryOverlayView.runOnMain(crossinline action: TrajectoryOverlayView.() -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            MAIN_HANDLER.post { action() }
        }
    }

    private companion object {
        val MAIN_HANDLER = Handler(Looper.getMainLooper())
    }
}
