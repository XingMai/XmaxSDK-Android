//
//  XmaxRealtimeVideoView.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import androidx.annotation.MainThread

/**
 * 实时生成画面容器。主线程设置轨道；本地预览持续挂载，远端首帧显示后自动淡入。
 * 将 [remoteTrack] 设为 null，或 SDK 停止远端渲染时，立即回到本地预览。
 * 此 View 不启动或停止媒体流。
 *
 * @param context Android 上下文；视图应使用具备当前界面主题的 Context。
 * @param attrs 从 XML 布局读取的视图属性；代码创建视图时可省略。
 * @param defStyleAttr 应用于视图的默认样式属性资源标识。
 */
@MainThread
public class XmaxRealtimeVideoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {
    /**
     * 本地和远端视频的显示容器。
     */
    private val localVideoView = XmaxVideoView(context).apply {
        isInteractionEnabled = false
    }
    private val remoteVideoView = XmaxVideoView(context).apply {
        isInteractionEnabled = false
    }

    /**
     * 当前显示来源与切换代次。
     */
    private var isRemoteDisplayed = false
    private var presentationVersion = 0L

    /**
     * 接入方提供的预览视图与本地、远端轨道。
     *
     * 由接入方持有的本地预览。设置后不再绑定 localTrack 的渲染窗口，
     * 会话重建不移除该视图；远端首帧就绪后仍使用相同的淡入逻辑。
     * 视图必须没有父容器，或已是当前容器的直接子视图。
     */
    public var localPreviewView: View? = null
        set(value) {
            if (field === value) return
            require(value !== this && (value?.parent == null || value.parent === this))
            field?.let(::removeView)
            field = value
            localVideoView.track = localTrack.takeIf { value == null }
            localVideoView.visibility = if (value == null) View.VISIBLE else View.GONE
            if (value != null && value.parent == null) {
                addView(value, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            }
            showLocalPreview()
        }

    /**
     * 本地输入轨道；远端画面显示期间仍保留本地预览。
     */
    public var localTrack: RealtimeVideoTrack? = null
        set(value) {
            if (field === value) return
            field = value
            localVideoView.track = value.takeIf { localPreviewView == null }
        }

    /**
     * 远端生成轨道；替换后等待新轨道首帧，清空后立即显示本地预览。
     */
    public var remoteTrack: RealtimeVideoTrack? = null
        set(value) {
            if (field === value) return
            field = value
            showLocalPreview()
            remoteVideoView.track = value
        }

    /**
     * 视频布局和触控轨迹显示配置。
     *
     * 同时应用于本地和远端画面。
     */
    public var videoContentMode: VideoContentMode = VideoContentMode.FILL
        set(value) {
            if (field == value) return
            field = value
            showLocalPreview()
            localVideoView.videoContentMode = value
            remoteVideoView.videoContentMode = value
        }

    /**
     * 仅在远端画面显示后允许轨迹交互，本地预览始终不接收轨迹。
     */
    public var isInteractionEnabled: Boolean = true
        set(value) {
            field = value
            remoteVideoView.isInteractionEnabled = value && isRemoteDisplayed
        }

    /**
     * 远端轨迹视觉效果；null 使用 SDK 内置效果。
     */
    public var trajectoryRenderer: TrajectoryEffectRendering?
        get() = remoteVideoView.trajectoryRenderer
        set(value) {
            remoteVideoView.trajectoryRenderer = value
        }

    init {
        setBackgroundColor(Color.BLACK)
        clipChildren = true
        /**
         * 等待结果时保持远端渲染器挂载在本地预览下方，以持续接收视频帧。
         */
        addView(remoteVideoView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(localVideoView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        remoteVideoView.frameInvalidationHandler = { invalidatedTrack ->
            if (remoteTrack === invalidatedTrack) {
                showLocalPreview()
            }
        }
        showLocalPreview()
    }

    /**
     * 在视图进入窗口后恢复显示所需的绑定。
     */
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        showLocalPreview()
    }

    /**
     * 在视图离开窗口后解除绑定并停止显示回调。
     */
    override fun onDetachedFromWindow() {
        showLocalPreview()
        super.onDetachedFromWindow()
    }

    /**
     * 切回本地预览画面并隐藏远端生成结果。
     */
    private fun showLocalPreview() {
        val version = ++presentationVersion
        isRemoteDisplayed = false
        remoteVideoView.animate().cancel()
        remoteVideoView.isInteractionEnabled = false
        (localPreviewView ?: localVideoView).bringToFront()
        remoteVideoView.alpha = 1f
        remoteVideoView.frameDisplayHandler = { displayedTrack ->
            if (presentationVersion == version && remoteVideoView.isFrameDisplayEnabled &&
                remoteTrack === displayedTrack && isAttachedToWindow && !isRemoteDisplayed
            ) {
                isRemoteDisplayed = true
                remoteVideoView.alpha = 0f
                remoteVideoView.bringToFront()
                remoteVideoView.isInteractionEnabled = isInteractionEnabled
                remoteVideoView.animate()
                    .alpha(1f)
                    .setDuration(REMOTE_FADE_DURATION_MS)
                    .setInterpolator(AccelerateDecelerateInterpolator())
                    .start()
            }
        }
    }

    /**
     * 提供 XmaxRealtimeVideoView 使用的构造入口、共享配置或常量。
     */
    private companion object {
        /**
         * 共享常量、默认配置与工厂入口。
         */
        const val REMOTE_FADE_DURATION_MS = 300L
    }
}
