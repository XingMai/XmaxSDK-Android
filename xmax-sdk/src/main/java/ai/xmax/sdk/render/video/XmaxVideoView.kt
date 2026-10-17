//
//  XmaxVideoView.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

import ai.xmax.sdk.render.trajectory.TrajectoryBinding
import ai.xmax.sdk.render.trajectory.TrajectoryOverlayView
import ai.xmax.sdk.render.trajectory.TrajectoryRegistry
import ai.xmax.sdk.render.video.FrameReportingTextureView
import ai.xmax.sdk.render.video.VideoRenderBinding
import ai.xmax.sdk.render.video.VideoRenderRegistry
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * 显示本地或远端实时视频轨道的 Android 容器。
 *
 * @param context Android 上下文；视图应使用具备当前界面主题的 Context。
 * @param attrs 从 XML 布局读取的视图属性；代码创建视图时可省略。
 * @param defStyleAttr 应用于视图的默认样式属性资源标识。
 */
public class XmaxVideoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {
    /**
     * RTC、图片与轨迹显示视图。
     */
    private var renderView: FrameReportingTextureView? = null
    private val imageView = ImageView(context)
    private val trajectoryOverlayView = TrajectoryOverlayView(context)

    /**
     * 主线程显示回调调度。
     */
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 当前图片及轨道、轨迹绑定。
     */
    private var displayedBitmap: Bitmap? = null
    private var attachedBinding: VideoRenderBinding? = null
    private var attachedTrajectoryBinding: TrajectoryBinding? = null

    /**
     * 画面显示代次与首帧通知状态。
     */
    private var renderGeneration = 0L
    private var hasDisplayedFrame = false
    private var pendingFrameDisplay: Runnable? = null

    /**
     * Internal presentation signal; this is separate from RTC decode readiness.
     */
    internal var frameDisplayHandler: ((RealtimeVideoTrack) -> Unit)? = null
    internal var frameInvalidationHandler: ((RealtimeVideoTrack) -> Unit)? = null
    internal var isFrameDisplayEnabled = false
        private set

    /**
     * 接入方配置的视频轨道、显示模式及交互效果。
     *
     * 当前显示的视频轨道。
     */
    public var track: RealtimeVideoTrack? = null
        set(value) {
            if (field === value) return
            detachCurrentTrack()
            field = value
            attachCurrentTrackIfNeeded()
        }

    /**
     * 视频内容在容器中的显示模式。
     */
    public var videoContentMode: VideoContentMode = VideoContentMode.FILL
        set(value) {
            if (field == value) return
            detachCurrentTrack()
            field = value
            attachCurrentTrackIfNeeded()
        }

    /**
     * 是否允许在远端视频上绘制并发送轨迹交互。
     */
    public var isInteractionEnabled: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            trajectoryOverlayView.setRequestedInteractionEnabled(value)
        }

    /**
     * 自定义轨迹视觉效果；设置为 null 时恢复 SDK 内置效果。
     */
    public var trajectoryRenderer: TrajectoryEffectRendering? = null
        set(value) {
            field = value
            trajectoryOverlayView.setRenderer(value ?: DefaultTrajectoryEffectRenderer(context))
        }

    init {
        setBackgroundColor(Color.BLACK)
        clipChildren = true
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

    /**
     * 提供给 RTC 的渲染视图。
     */
    internal val rtcRenderView: View
        get() = checkNotNull(renderView) { "RTC rendering has not been prepared" }

    /**
     * 准备网络视频预览使用的纹理视图。
     *
     * @return 用于网络视频预览的纹理视图。
     */
    internal fun prepareNetworkVideoRendering(): android.view.TextureView {
        prepareRtcVideoRendering()
        return checkNotNull(renderView)
    }

    /**
     * 准备 RTC 视频渲染所需的视图。
     */
    internal fun prepareRtcVideoRendering() {
        invalidateVideoPresentation()
        clearImageFrame()
        removeRtcRenderView()
        /**
         * 每次轨道绑定使用独立显示表面，首帧通知仅对应当前轨道。
         */
        val generation = renderGeneration
        renderView = FrameReportingTextureView(context).also { textureView ->
            textureView.onFrameDisplayed = { source ->
                if (renderView === source && renderGeneration == generation) {
                    scheduleFrameDisplayed()
                }
            }
            addView(
                textureView,
                0,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
            )
        }
        isFrameDisplayEnabled = true
    }

    /**
     * 通知容器切回本地预览，避免远端渲染资源释放时出现空白画面。
     */
    internal fun invalidateVideoPresentation() {
        renderGeneration += 1L
        isFrameDisplayEnabled = false
        hasDisplayedFrame = false
        pendingFrameDisplay?.let(mainHandler::removeCallbacks)
        pendingFrameDisplay = null
        renderView?.onFrameDisplayed = null
        track?.let { frameInvalidationHandler?.invoke(it) }
    }

    /**
     * 显示指定图片帧并应用显示模式。
     *
     * @param frame 待处理、显示或提交的视频帧。
     * @param contentMode 视频等比适配或等比裁切的显示方式。
     */
    internal fun displayImageFrame(frame: VideoFrame, contentMode: VideoContentMode) {
        displayBitmap(makeBitmap(frame), contentMode)
    }

    /**
     * 准备本地解码视频的图片预览层。
     *
     * @param contentMode 视频等比适配或等比裁切的显示方式。
     */
    internal fun prepareDecodedVideoPreview(contentMode: VideoContentMode) {
        imageView.scaleType = when (contentMode) {
            VideoContentMode.FIT -> ImageView.ScaleType.FIT_CENTER
            VideoContentMode.FILL -> ImageView.ScaleType.CENTER_CROP
        }
        renderView?.visibility = View.GONE
    }

    /**
     * 更新本地解码视频的当前预览图片。
     *
     * @param bitmap 待处理或显示的 Android 图片。
     * @param contentMode 视频等比适配或等比裁切的显示方式。
     */
    internal fun displayDecodedVideoBitmap(bitmap: Bitmap, contentMode: VideoContentMode) {
        displayBitmap(bitmap, contentMode)
    }

    /**
     * 在预览层显示指定图片并应用显示模式。
     *
     * @param bitmap 待处理或显示的 Android 图片。
     * @param contentMode 视频等比适配或等比裁切的显示方式。
     */
    private fun displayBitmap(bitmap: Bitmap, contentMode: VideoContentMode) {
        isFrameDisplayEnabled = true
        displayedBitmap?.recycle()
        displayedBitmap = bitmap
        imageView.scaleType = when (contentMode) {
            VideoContentMode.FIT -> ImageView.ScaleType.FIT_CENTER
            VideoContentMode.FILL -> ImageView.ScaleType.CENTER_CROP
        }
        imageView.setImageBitmap(bitmap)
        imageView.visibility = View.VISIBLE
        renderView?.visibility = View.GONE
    }

    /**
     * 清除本地解码视频的预览图片。
     */
    internal fun clearDecodedVideoPreview() {
        clearImageFrame()
    }

    /**
     * 清除当前图片预览。
     */
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

    /**
     * 在视图进入窗口后恢复显示所需的绑定。
     */
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attachCurrentTrackIfNeeded()
    }

    /**
     * 在视图离开窗口后解除绑定并停止显示回调。
     */
    override fun onDetachedFromWindow() {
        detachCurrentTrack()
        super.onDetachedFromWindow()
    }

    /**
     * 完成子视图绘制并报告画面已提交显示。
     *
     * @param canvas 用于绘制当前视图的画布。
     */
    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (imageView.visibility == View.VISIBLE && displayedBitmap != null) {
            scheduleFrameDisplayed()
        }
    }

    /**
     * 为当前尚未绑定的视频轨道建立显示绑定。
     */
    private fun attachCurrentTrackIfNeeded() {
        if (!isAttachedToWindow) return
        val currentTrack = track ?: return
        VideoRenderRegistry.binding(currentTrack)?.let { binding ->
            runCatching {
                binding.attach(this, videoContentMode)
                attachedBinding = binding
            }.onFailure { error ->
                attachedBinding = null
                XmaxLogger.render.error(
                    message = {
                        "绑定视频渲染视图失败 (Failed to Attach Video Render View)\n" +
                            "└─ ${XmaxLogger.localized("原因：", "Reason: ")}${ErrorMessageFormatter.format(error)}"
                    },
                )
            }
        }
        TrajectoryRegistry.binding(currentTrack)?.let { binding ->
            binding.attach(trajectoryOverlayView, videoContentMode)
            attachedTrajectoryBinding = binding
        }
        trajectoryOverlayView.bringToFront()
    }

    /**
     * 解除当前视频轨道的显示绑定。
     */
    private fun detachCurrentTrack() {
        invalidateVideoPresentation()
        attachedBinding?.let { binding ->
            runCatching { binding.detach(this) }
                .onFailure { error ->
                    XmaxLogger.render.error(
                        message = {
                            "解绑视频渲染视图失败 (Failed to Detach Video Render View)\n" +
                                "└─ ${XmaxLogger.localized("原因：", "Reason: ")}${ErrorMessageFormatter.format(error)}"
                        },
                    )
                }
        }
        attachedTrajectoryBinding?.detach(trajectoryOverlayView)
        attachedBinding = null
        attachedTrajectoryBinding = null
        removeRtcRenderView()
        clearImageFrame()
    }

    /**
     * 移除 RTC 渲染视图并清空引用。
     */
    private fun removeRtcRenderView() {
        renderView?.let { textureView ->
            textureView.onFrameDisplayed = null
            removeView(textureView)
        }
        renderView = null
    }

    /**
     * 安排在视图绘制完成后通知画面已显示。
     */
    private fun scheduleFrameDisplayed() {
        if (!isFrameDisplayEnabled || hasDisplayedFrame || pendingFrameDisplay != null || attachedBinding == null) return
        val currentTrack = track ?: return
        val generation = renderGeneration
        pendingFrameDisplay = Runnable {
            pendingFrameDisplay = null
            if (isFrameDisplayEnabled && renderGeneration == generation && track === currentTrack && isAttachedToWindow) {
                hasDisplayedFrame = true
                frameDisplayHandler?.invoke(currentTrack)
            }
        }.also(mainHandler::post)
    }

    /**
     * 将 SDK 视频帧转换为可用于预览的 Bitmap。
     *
     * @param frame 待处理、显示或提交的视频帧。
     * @return 用于本地显示的图片。
     */
    private fun makeBitmap(frame: VideoFrame): Bitmap {
        if (frame.format.pixelFormat != VideoPixelFormat.RGBA || frame.planes.size != 1) {
            throw IllegalArgumentException("Image preview requires a single RGBA video plane")
        }
        val width = frame.format.width
        val height = frame.format.height
        val plane = frame.planes.single()
        val bytes = plane.selectedBytesView()
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

    /**
     * 提供 XmaxVideoView 使用的构造入口、共享配置或常量。
     */
    private companion object {
        /**
         * 共享常量、默认配置与工厂入口。
         */
        const val RGBA_BYTES_PER_PIXEL = 4
    }
}
