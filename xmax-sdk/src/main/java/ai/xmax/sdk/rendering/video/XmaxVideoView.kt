package ai.xmax.sdk

import ai.xmax.sdk.rendering.video.VideoRenderBinding
import ai.xmax.sdk.rendering.video.VideoRenderRegistry
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
    private var attachedTrack: RealtimeVideoTrack? = null
    private var attachedBinding: VideoRenderBinding? = null

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
            field = value
            attachCurrentTrackIfNeeded()
        }

    init {
        setBackgroundColor(Color.BLACK)
        clipChildren = true
        addView(
            renderView,
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
        val binding = VideoRenderRegistry.binding(currentTrack) ?: return
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

    private fun detachCurrentTrack() {
        val binding = attachedBinding ?: return
        runCatching { binding.detach(renderView) }
            .onFailure { Log.e(TAG, "Failed to detach video render view", it) }
        attachedTrack = null
        attachedBinding = null
    }

    private companion object {
        const val TAG = "XmaxVideoView"
    }
}
