package ai.xmax.sdk.rendering.video

import ai.xmax.sdk.VideoContentMode
import ai.xmax.sdk.VideoFrame
import ai.xmax.sdk.XmaxVideoView
import java.lang.ref.WeakReference

/** 将实时视频轨道绑定到具体 Android 渲染视图。 */
internal class VideoRenderBinding(
    val libraryName: String,
    private val attachHandler: (XmaxVideoView, VideoContentMode) -> Unit,
    private val detachHandler: (XmaxVideoView) -> Unit,
) {
    private var attachedView: WeakReference<XmaxVideoView>? = null

    constructor(imageFrame: VideoFrame) : this(
        libraryName = "XmaxSDK",
        attachHandler = { view, contentMode ->
            view.displayImageFrame(imageFrame, contentMode)
        },
        detachHandler = XmaxVideoView::clearImageFrame,
    )

    @Synchronized
    fun attach(view: XmaxVideoView, contentMode: VideoContentMode) {
        val previousView = attachedView?.get()
        if (previousView != null && previousView !== view) {
            detachHandler(previousView)
            attachedView = null
        }
        attachHandler(view, contentMode)
        attachedView = WeakReference(view)
    }

    @Synchronized
    fun detach(view: XmaxVideoView) {
        if (attachedView?.get() === view) detach()
    }

    @Synchronized
    fun detach() {
        attachedView?.get()?.let(detachHandler)
        attachedView = null
    }
}
