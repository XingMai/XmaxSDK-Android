package ai.xmax.sdk

import ai.xmax.sdk.rendering.video.VideoRenderBinding
import ai.xmax.sdk.rendering.video.VideoRenderRegistry
import ai.xmax.sdk.rendering.trajectory.TrajectoryBinding
import ai.xmax.sdk.rendering.trajectory.TrajectoryOverlayView
import ai.xmax.sdk.rendering.trajectory.TrajectoryRegistry
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.view.TextureView
import android.widget.FrameLayout

/** 显示本地或远端实时视频轨道的 Android 容器。 */
public class XmaxVideoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {
    private val renderView = TextureView(context)
    private val imageView = ImageView(context)
    private val trajectoryOverlayView = TrajectoryOverlayView(context)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var displayedBitmap: Bitmap? = null
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
            imageView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        addView(
            trajectoryOverlayView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        imageView.visibility = View.GONE
    }

    internal val rtcRenderView: View
        get() = renderView

    internal fun prepareRtcVideoRendering() {
        clearImageFrame()
        renderView.visibility = View.VISIBLE
    }

    internal fun displayImageFrame(frame: VideoFrame, contentMode: VideoContentMode) {
        val bitmap = makeBitmap(frame)
        displayedBitmap?.recycle()
        displayedBitmap = bitmap
        imageView.scaleType = when (contentMode) {
            VideoContentMode.FIT -> ImageView.ScaleType.FIT_CENTER
            VideoContentMode.FILL -> ImageView.ScaleType.CENTER_CROP
        }
        imageView.setImageBitmap(bitmap)
        imageView.visibility = View.VISIBLE
        renderView.visibility = View.GONE
    }

    internal fun clearImageFrame() {
        val bitmapToClear = displayedBitmap
        val action: () -> Unit = action@{
            if (displayedBitmap !== bitmapToClear) return@action
            imageView.setImageDrawable(null)
            imageView.visibility = View.GONE
            displayedBitmap?.recycle()
            displayedBitmap = null
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.invoke()
        } else {
            mainHandler.post(action)
        }
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
                binding.attach(this, videoContentMode)
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
            runCatching { binding.detach(this) }
                .onFailure { Log.e(TAG, "Failed to detach video render view", it) }
        }
        attachedTrajectoryBinding?.detach(trajectoryOverlayView)
        attachedTrack = null
        attachedBinding = null
        attachedTrajectoryTrack = null
        attachedTrajectoryBinding = null
    }

    private fun makeBitmap(frame: VideoFrame): Bitmap {
        if (frame.format.pixelFormat != VideoPixelFormat.RGBA || frame.planes.size != 1) {
            throw IllegalArgumentException("Image preview requires a single RGBA video plane")
        }
        val width = frame.format.width
        val height = frame.format.height
        val plane = frame.planes.single()
        val bytes = plane.selectedBytes()
        require(bytes.size >= width * height * RGBA_BYTES_PER_PIXEL) {
            "Image preview RGBA plane is smaller than its declared format"
        }
        val pixels = IntArray(width * height)
        pixels.indices.forEach { index ->
            val offset = index * RGBA_BYTES_PER_PIXEL
            val red = bytes[offset].toInt() and 0xFF
            val green = bytes[offset + 1].toInt() and 0xFF
            val blue = bytes[offset + 2].toInt() and 0xFF
            val alpha = bytes[offset + 3].toInt() and 0xFF
            pixels[index] = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private companion object {
        const val TAG = "XmaxVideoView"
        const val RGBA_BYTES_PER_PIXEL = 4
    }
}
