package ai.xmax.sdk

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import androidx.annotation.MainThread

/**
 * 实时生成画面容器。主线程设置轨道；本地预览持续挂载，远端首帧显示后自动淡入。
 * 将 [remoteTrack] 设为 null，或 SDK 停止远端渲染时，立即回到本地预览。
 * 此 View 不启动或停止媒体流。
 */
@MainThread
public class XmaxRealtimeVideoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {
    private val localVideoView = XmaxVideoView(context).apply {
        isInteractionEnabled = false
    }
    private val remoteVideoView = XmaxVideoView(context).apply {
        isInteractionEnabled = false
    }
    private var isRemoteDisplayed = false
    private var presentationVersion = 0L

    /** 本地输入轨道；远端画面显示期间仍保留本地预览。 */
    public var localTrack: RealtimeVideoTrack? = null
        set(value) {
            if (field === value) return
            field = value
            localVideoView.track = value
        }

    /** 远端生成轨道；替换后等待新轨道首帧，清空后立即显示本地预览。 */
    public var remoteTrack: RealtimeVideoTrack? = null
        set(value) {
            if (field === value) return
            field = value
            showLocalPreview()
            remoteVideoView.track = value
        }

    /** 同时应用于本地和远端画面。 */
    public var videoContentMode: VideoContentMode = VideoContentMode.FILL
        set(value) {
            if (field == value) return
            field = value
            showLocalPreview()
            localVideoView.videoContentMode = value
            remoteVideoView.videoContentMode = value
        }

    /** 仅在远端画面显示后允许轨迹交互，本地预览始终不接收轨迹。 */
    public var isInteractionEnabled: Boolean = true
        set(value) {
            field = value
            remoteVideoView.isInteractionEnabled = value && isRemoteDisplayed
        }

    /** 远端轨迹视觉效果；null 使用 SDK 内置效果。 */
    public var trajectoryRenderer: TrajectoryEffectRendering?
        get() = remoteVideoView.trajectoryRenderer
        set(value) {
            remoteVideoView.trajectoryRenderer = value
        }

    init {
        setBackgroundColor(Color.BLACK)
        clipChildren = true
        // Keep the remote renderer visible behind the local preview while waiting.
        // GONE/INVISIBLE (or alpha zero) can prevent TextureView from acquiring frames.
        addView(remoteVideoView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(localVideoView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        remoteVideoView.frameInvalidationHandler = { invalidatedTrack ->
            if (remoteTrack === invalidatedTrack) {
                showLocalPreview()
            }
        }
        showLocalPreview()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        showLocalPreview()
    }

    override fun onDetachedFromWindow() {
        showLocalPreview()
        super.onDetachedFromWindow()
    }

    private fun showLocalPreview() {
        val version = ++presentationVersion
        isRemoteDisplayed = false
        remoteVideoView.animate().cancel()
        remoteVideoView.isInteractionEnabled = false
        localVideoView.bringToFront()
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

    private companion object {
        const val REMOTE_FADE_DURATION_MS = 300L
    }
}
