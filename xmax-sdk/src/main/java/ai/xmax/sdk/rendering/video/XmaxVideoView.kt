package ai.xmax.sdk

import ai.xmax.sdk.rendering.video.VideoRenderBinding
import ai.xmax.sdk.rendering.video.VideoRenderRegistry
import ai.xmax.sdk.rendering.trajectory.TrajectoryBinding
import ai.xmax.sdk.rendering.trajectory.TrajectoryOverlayView
import ai.xmax.sdk.rendering.trajectory.TrajectoryRegistry
import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.util.Log
import android.view.TextureView
import android.widget.FrameLayout

/** 显示本地或远端实时视频轨道的 Android 容器。 */
public class XmaxVideoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {
    private val renderView = TextureView(context)
    private val trajectoryOverlayView = TrajectoryOverlayView(context)
    private var attachedTrack: RealtimeVideoTrack? = null
    private var attachedBinding: VideoRenderBinding? = null
    private var attachedTrajectoryTrack: RealtimeVideoTrack? = null
    private var attachedTrajectoryBinding: TrajectoryBinding? = null

    /** 当前显示的视频轨道。 */
    public var track: RealtimeVideoTrack? = null
        set(value) {
            if (field === value) return
            detachCurrentTrack()
            field = value
            attachCurrentTrackIfNeeded()
        }

    /** 视频内容在容器中的显示模式。 */
    public var videoContentMode: VideoContentMode = VideoContentMode.FILL
        set(value) {
            if (field == value) return
            detachCurrentTrack()
            field = value
            attachCurrentTrackIfNeeded()
        }

    /** 是否允许在远端视频上绘制并发送轨迹交互。 */
    public var isInteractionEnabled: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            trajectoryOverlayView.setRequestedInteractionEnabled(value)
        }

    /** 自定义轨迹视觉效果；设置为 null 时恢复 SDK 内置效果。 */
    public var trajectoryRenderer: TrajectoryEffectRendering? = null
        set(value) {
            field = value
            trajectoryOverlayView.setRenderer(value ?: DefaultTrajectoryEffectRenderer(context))
        }

    init {
        setBackgroundColor(Color.BLACK)
        clipChildren = true
        addView(
            renderView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        addView(
            trajectoryOverlayView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attachCurrentTrackIfNeeded()
    }

    override fun onDetachedFromWindow() {
        detachCurrentTrack()
        super.onDetachedFromWindow()
    }

    private fun attachCurrentTrackIfNeeded() {
        if (!isAttachedToWindow) return
        val currentTrack = track ?: return
        VideoRenderRegistry.binding(currentTrack)?.let { binding ->
            runCatching {
                binding.attach(renderView, videoContentMode)
                attachedTrack = currentTrack
                attachedBinding = binding
            }.onFailure { error ->
                attachedTrack = null
                attachedBinding = null
                Log.e(TAG, "Failed to attach video render view", error)
            }
        }
        TrajectoryRegistry.binding(currentTrack)?.let { binding ->
            binding.attach(trajectoryOverlayView, videoContentMode)
            attachedTrajectoryTrack = currentTrack
            attachedTrajectoryBinding = binding
        }
        trajectoryOverlayView.bringToFront()
    }

    private fun detachCurrentTrack() {
        attachedBinding?.let { binding ->
            runCatching { binding.detach(renderView) }
                .onFailure { Log.e(TAG, "Failed to detach video render view", it) }
        }
        attachedTrajectoryBinding?.detach(trajectoryOverlayView)
        attachedTrack = null
        attachedBinding = null
        attachedTrajectoryTrack = null
        attachedTrajectoryBinding = null
    }

    private companion object {
        const val TAG = "XmaxVideoView"
    }
}
