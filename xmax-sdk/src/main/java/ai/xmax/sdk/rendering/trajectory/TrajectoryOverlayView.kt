package ai.xmax.sdk.rendering.trajectory

import ai.xmax.sdk.DefaultTrajectoryEffectRenderer
import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.TrajectoryEffectRendering
import ai.xmax.sdk.TrajectoryID
import ai.xmax.sdk.TrajectoryPoint
import ai.xmax.sdk.VideoContentMode
import ai.xmax.sdk.media.interaction.InteractionCoordinateMapper
import ai.xmax.sdk.media.interaction.InteractionFrame
import android.content.Context
import android.graphics.PointF
import android.os.SystemClock
import android.util.SizeF
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout

/** 采集多指触摸、驱动轨迹效果并按固定频率提交交互帧。 */
internal class TrajectoryOverlayView(context: Context) : FrameLayout(context) {
    private val activeTouches = mutableMapOf<Int, ActiveTouch>()
    private var binding: TrajectoryBinding? = null
    private var videoContentMode = VideoContentMode.FILL
    private var videoFormat: RealtimeVideoFormat? = null
    private var renderer: TrajectoryEffectRendering = DefaultTrajectoryEffectRenderer(context)
    private var requestedInteractionEnabled = true
    private var lastSampleNanos = 0L
    private var frameCallbackPosted = false
    private val frameCallback = Choreographer.FrameCallback { frameTimeNanos ->
        frameCallbackPosted = false
        submitIfNeeded(frameTimeNanos)
        if (activeTouches.isNotEmpty()) postFrameCallback()
    }

    init {
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        clipChildren = false
        addRendererView(renderer)
        updateInteractionState()
    }

    fun setRenderer(renderer: TrajectoryEffectRendering) {
        cancelInteraction()
        this.renderer.reset()
        removeView(this.renderer.view)
        this.renderer = renderer
        addRendererView(renderer)
    }

    fun setBinding(binding: TrajectoryBinding?) {
        this.binding = binding
        if (binding == null) cancelInteraction()
        updateInteractionState()
    }

    fun clearBinding(expected: TrajectoryBinding) {
        if (binding === expected) setBinding(null)
    }

    fun setContentMode(contentMode: VideoContentMode) {
        if (videoContentMode == contentMode) return
        videoContentMode = contentMode
        cancelInteraction()
        updateInteractionState()
    }

    fun setVideoFormat(videoFormat: RealtimeVideoFormat?) {
        if (this.videoFormat == videoFormat) return
        this.videoFormat = videoFormat
        cancelInteraction()
        updateInteractionState()
    }

    fun setRequestedInteractionEnabled(enabled: Boolean) {
        if (requestedInteractionEnabled == enabled) return
        requestedInteractionEnabled = enabled
        if (!enabled) cancelInteraction()
        updateInteractionState()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val interactionFrame = interactionFrame()
        if (!isEnabled || !isClickable || interactionFrame == null) return false
        if ((event.actionMasked == MotionEvent.ACTION_DOWN ||
                event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) &&
            !interactionFrame.contains(
                event.getX(event.actionIndex),
                event.getY(event.actionIndex),
            )
        ) {
            return event.actionMasked != MotionEvent.ACTION_DOWN
        }
        val timestamp = nowSeconds()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN,
            -> {
                val index = event.actionIndex
                val touch = ActiveTouch(
                    id = TrajectoryID(),
                    location = clamped(PointF(event.getX(index), event.getY(index))),
                )
                activeTouches[event.getPointerId(index)] = touch
                renderer.renderBegan(listOf(touch.asTrajectoryPoint(timestamp)))
                submitIfNeeded(SystemClock.elapsedRealtimeNanos(), force = true)
                postFrameCallback()
            }
            MotionEvent.ACTION_MOVE -> {
                val moved = buildList {
                    for (index in 0 until event.pointerCount) {
                        val pointerId = event.getPointerId(index)
                        val touch = activeTouches[pointerId] ?: continue
                        val location = clamped(PointF(event.getX(index), event.getY(index)))
                        if (distanceSquared(touch.location, location) < MINIMUM_MOVE_SQUARED) continue
                        touch.location = location
                        add(touch.asTrajectoryPoint(timestamp))
                    }
                }
                if (moved.isNotEmpty()) renderer.renderMoved(moved)
            }
            MotionEvent.ACTION_UP -> {
                finishPointer(event.getPointerId(event.actionIndex))
                performClick()
            }
            MotionEvent.ACTION_POINTER_UP -> finishPointer(event.getPointerId(event.actionIndex))
            MotionEvent.ACTION_CANCEL -> cancelInteraction()
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDetachedFromWindow() {
        cancelInteraction()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (width != oldWidth || height != oldHeight) {
            cancelInteraction()
            updateInteractionState()
        }
    }

    private fun finishPointer(pointerId: Int) {
        val identifier = activeTouches.remove(pointerId)?.id ?: return
        renderer.renderEnded(listOf(identifier))
        if (activeTouches.isEmpty()) stopFrameCallback()
    }

    private fun submitIfNeeded(nowNanos: Long, force: Boolean = false) {
        if (activeTouches.isEmpty()) return
        if (!force && nowNanos - lastSampleNanos < SAMPLE_INTERVAL_NANOS) return
        lastSampleNanos = nowNanos
        binding?.submit(
            InteractionFrame(
                points = activeTouches.values.map { PointF(it.location.x, it.location.y) },
                viewportSize = SizeF(width.toFloat(), height.toFloat()),
                contentMode = videoContentMode,
            ),
        )
    }

    private fun cancelInteraction() {
        val identifiers = activeTouches.values.map(ActiveTouch::id)
        activeTouches.clear()
        if (identifiers.isNotEmpty()) renderer.renderEnded(identifiers)
        renderer.reset()
        lastSampleNanos = 0L
        stopFrameCallback()
    }

    private fun updateInteractionState() {
        isEnabled = requestedInteractionEnabled && binding != null && interactionFrame() != null
        isClickable = isEnabled
    }

    private fun interactionFrame() = videoFormat?.let { format ->
        InteractionCoordinateMapper.displayedFrame(
            viewportSize = SizeF(width.toFloat(), height.toFloat()),
            videoSize = SizeF(format.width.toFloat(), format.height.toFloat()),
            contentMode = videoContentMode,
        )?.let { displayed ->
            android.graphics.RectF(
                displayed.left.coerceAtLeast(0f),
                displayed.top.coerceAtLeast(0f),
                displayed.right.coerceAtMost(width.toFloat()),
                displayed.bottom.coerceAtMost(height.toFloat()),
            ).takeUnless { it.isEmpty }
        }
    }

    private fun clamped(point: PointF): PointF {
        val frame = interactionFrame() ?: return point
        return PointF(
            point.x.coerceIn(frame.left, frame.right),
            point.y.coerceIn(frame.top, frame.bottom),
        )
    }

    private fun postFrameCallback() {
        if (frameCallbackPosted) return
        frameCallbackPosted = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun stopFrameCallback() {
        if (!frameCallbackPosted) return
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        frameCallbackPosted = false
    }

    private fun addRendererView(renderer: TrajectoryEffectRendering) {
        val rendererView = renderer.view
        (rendererView.parent as? android.view.ViewGroup)?.removeView(rendererView)
        rendererView.isClickable = false
        addView(
            rendererView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
    }

    private data class ActiveTouch(
        val id: TrajectoryID,
        var location: PointF,
    )

    private fun ActiveTouch.asTrajectoryPoint(timestamp: Double): TrajectoryPoint {
        val frame = videoFormat?.let { format ->
            InteractionCoordinateMapper.displayedFrame(
                viewportSize = SizeF(width.toFloat(), height.toFloat()),
                videoSize = SizeF(format.width.toFloat(), format.height.toFloat()),
                contentMode = videoContentMode,
            )
        }
        val normalized = if (frame != null && frame.width() > 0f && frame.height() > 0f) {
            PointF(
                (location.x - frame.left) / frame.width(),
                (location.y - frame.top) / frame.height(),
            )
        } else {
            PointF(0f, 0f)
        }
        return TrajectoryPoint(
            id = id,
            location = PointF(location.x, location.y),
            normalizedLocation = normalized,
            timestamp = timestamp,
        )
    }

    private companion object {
        const val SAMPLE_INTERVAL_NANOS = 33_333_333L
        const val MINIMUM_MOVE_SQUARED = 0.25f

        fun distanceSquared(first: PointF, second: PointF): Float {
            val dx = second.x - first.x
            val dy = second.y - first.y
            return dx * dx + dy * dy
        }

        fun nowSeconds(): Double = SystemClock.elapsedRealtimeNanos() / 1_000_000_000.0
    }
}
