package ai.xmax.sdk.rendering.video

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.TextureView

/** Observes presentation without replacing the RTC renderer's surface lifecycle callbacks. */
internal class FrameReportingTextureView(context: Context) : TextureView(context) {
    var onFrameDisplayed: ((FrameReportingTextureView) -> Unit)? = null

    private var rendererListener: SurfaceTextureListener? = null
    private var frameCheckPending = false
    private var hasDisplayedFrame = false
    private val frameCheck = Runnable {
        frameCheckPending = false
        val surface = surfaceTexture
        // Size/visibility changes also produce texture updates. A timestamp distinguishes
        // an acquired video buffer from an empty surface. Check after the drawing traversal.
        if (!hasDisplayedFrame && isAttachedToWindow && surface != null &&
            !surface.isReleased && surface.timestamp != 0L
        ) {
            hasDisplayedFrame = true
            onFrameDisplayed?.invoke(this)
        }
    }

    init {
        super.setSurfaceTextureListener(object : SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                rendererListener?.onSurfaceTextureAvailable(surface, width, height)
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                rendererListener?.onSurfaceTextureSizeChanged(surface, width, height)
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                removeCallbacks(frameCheck)
                frameCheckPending = false
                hasDisplayedFrame = false
                return rendererListener?.onSurfaceTextureDestroyed(surface) ?: true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                rendererListener?.onSurfaceTextureUpdated(surface)
                if (surface === surfaceTexture && !hasDisplayedFrame && !frameCheckPending) {
                    frameCheckPending = true
                    postOnAnimation(frameCheck)
                }
            }
        })
    }

    override fun setSurfaceTextureListener(listener: SurfaceTextureListener?) {
        rendererListener = listener
    }

    // RTC implementations may save and wrap their previous listener. Returning our
    // forwarding listener here would create a callback cycle when they do so.
    override fun getSurfaceTextureListener(): SurfaceTextureListener? = rendererListener

    override fun onDetachedFromWindow() {
        removeCallbacks(frameCheck)
        frameCheckPending = false
        super.onDetachedFromWindow()
    }
}
