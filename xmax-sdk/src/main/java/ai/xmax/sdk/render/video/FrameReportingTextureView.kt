//
//  FrameReportingTextureView.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.render.video

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.TextureView

/**
 * Observes presentation without replacing the RTC renderer's surface lifecycle callbacks.
 *
 * @param context Android 上下文；视图应使用具备当前界面主题的 Context。
 */
internal class FrameReportingTextureView(context: Context) : TextureView(context) {
    /**
     * 画面显示通知及外部纹理监听器。
     */
    var onFrameDisplayed: ((FrameReportingTextureView) -> Unit)? = null

    private var rendererListener: SurfaceTextureListener? = null

    /**
     * 首帧显示判定与绘制检查任务。
     */
    private var frameCheckPending = false
    private var hasDisplayedFrame = false
    private val frameCheck = Runnable {
        frameCheckPending = false
        val surface = surfaceTexture
        /**
         * 在绘制完成后确认纹理已取得有效视频帧，再发送首帧显示通知。
         */
        if (!hasDisplayedFrame && isAttachedToWindow && surface != null &&
            !surface.isReleased && surface.timestamp != 0L
        ) {
            hasDisplayedFrame = true
            onFrameDisplayed?.invoke(this)
        }
    }

    init {
        super.setSurfaceTextureListener(object : SurfaceTextureListener {
            /**
             * 在预览纹理可用后建立视频输出绑定。
             *
             * @param surface 当前预览纹理。
             * @param width 画面宽度，单位为像素。
             * @param height 画面高度，单位为像素。
             */
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                rendererListener?.onSurfaceTextureAvailable(surface, width, height)
            }

            /**
             * 在预览尺寸变化后更新视频布局。
             *
             * @param surface 当前预览纹理。
             * @param width 画面宽度，单位为像素。
             * @param height 画面高度，单位为像素。
             */
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                rendererListener?.onSurfaceTextureSizeChanged(surface, width, height)
            }

            /**
             * 解除已销毁纹理的媒体绑定并决定纹理资源的释放归属。
             *
             * @param surface 当前预览纹理。
             * @return 是否由 TextureView 释放纹理的判定结果。
             */
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                removeCallbacks(frameCheck)
                frameCheckPending = false
                hasDisplayedFrame = false
                return rendererListener?.onSurfaceTextureDestroyed(surface) ?: true
            }

            /**
             * 接收纹理内容更新通知。
             *
             * @param surface 当前预览纹理。
             */
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                rendererListener?.onSurfaceTextureUpdated(surface)
                if (surface === surfaceTexture && !hasDisplayedFrame && !frameCheckPending) {
                    frameCheckPending = true
                    postOnAnimation(frameCheck)
                }
            }
        })
    }

    /**
     * 设置接收预览纹理生命周期通知的监听器。
     *
     * @param listener 接收对应事件的监听器；null 表示注销。
     */
    override fun setSurfaceTextureListener(listener: SurfaceTextureListener?) {
        rendererListener = listener
    }

    /**
     * 取得接入方设置的预览纹理监听器。
     *
     * @return 接入方设置的纹理监听器；未设置时为 null。
     *
     * 只返回外部注册的监听器，避免 RTC 包装监听器时形成循环回调。
     */
    override fun getSurfaceTextureListener(): SurfaceTextureListener? = rendererListener

    /**
     * 在视图离开窗口后解除绑定并停止显示回调。
     */
    override fun onDetachedFromWindow() {
        removeCallbacks(frameCheck)
        frameCheckPending = false
        super.onDetachedFromWindow()
    }
}
