package ai.xmax.sdk.rendering.video

import ai.xmax.sdk.VideoContentMode
import android.view.View
import java.lang.ref.WeakReference

/** 将实时视频轨道绑定到具体 Android 渲染视图。 */
internal class VideoRenderBinding(
    val libraryName: String,
    private val attachHandler: (View, VideoContentMode) -> Unit,
    private val detachHandler: (View) -> Unit,
) {
    private var attachedView: WeakReference<View>? = null

    @Synchronized
    fun attach(view: View, contentMode: VideoContentMode) {
        val previousView = attachedView?.get()
        if (previousView != null && previousView !== view) {
            detachHandler(previousView)
            attachedView = null
        }
        attachHandler(view, contentMode)
        attachedView = WeakReference(view)
    }

    @Synchronized
    fun detach(view: View) {
        if (attachedView?.get() === view) detach()
    }

    @Synchronized
    fun detach() {
        attachedView?.get()?.let(detachHandler)
        attachedView = null
    }
}
