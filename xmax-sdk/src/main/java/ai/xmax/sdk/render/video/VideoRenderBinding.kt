//
//  VideoRenderBinding.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.render.video

import ai.xmax.sdk.VideoContentMode
import ai.xmax.sdk.VideoFrame
import ai.xmax.sdk.XmaxVideoView
import java.lang.ref.WeakReference

/**
 * 将实时视频轨道绑定到具体 Android 渲染视图。
 *
 * @param attachHandler 将轨道绑定到视图并应用显示模式的函数。
 * @param detachHandler 解除轨道视图绑定的函数。
 */
internal class VideoRenderBinding(
    private val attachHandler: (XmaxVideoView, VideoContentMode) -> Unit,
    private val detachHandler: (XmaxVideoView) -> Unit,
) {
    /**
     * 轨道与显示组件的绑定记录。
     */
    private var attachedView: WeakReference<XmaxVideoView>? = null

    /**
     * 创建 VideoRenderBinding，并配置其依赖和运行参数。
     *
     * @param imageFrame 用于本地预览的图片帧。
     */
    constructor(imageFrame: VideoFrame) : this(
        attachHandler = { view, contentMode ->
            view.displayImageFrame(imageFrame, contentMode)
        },
        detachHandler = XmaxVideoView::clearImageFrame,
    )

    /**
     * 将视频轨道绑定到指定预览视图。
     *
     * @param view 需要绑定或解除绑定的显示视图。
     * @param contentMode 视频等比适配或等比裁切的显示方式。
     */
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

    /**
     * 解除视频轨道与指定视图的绑定。
     *
     * @param view 需要绑定或解除绑定的显示视图。
     */
    @Synchronized
    fun detach(view: XmaxVideoView) {
        if (attachedView?.get() === view) detach()
    }

    /**
     * 解除视频轨道与指定视图的绑定。
     */
    @Synchronized
    fun detach() {
        attachedView?.get()?.let(detachHandler)
        attachedView = null
    }
}
