//
//  TrajectoryOverlayView.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.render.trajectory

import ai.xmax.sdk.DefaultTrajectoryEffectRenderer
import androidx.compose.ui.unit.IntSize
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

/**
 * 采集多指触摸、驱动轨迹效果并按固定频率提交交互帧。
 *
 * @param context Android 上下文；视图应使用具备当前界面主题的 Context。
 */
internal class TrajectoryOverlayView(context: Context) : FrameLayout(context) {
    /**
     * 当前触点、轨道绑定、显示模式及固定媒体尺寸。
     */
    private val activeTouches = mutableMapOf<Int, ActiveTouch>()
    private var binding: TrajectoryBinding? = null
    private var videoContentMode = VideoContentMode.FILL
    private var resolvedMediaSize: IntSize? = null

    /**
     * 轨迹效果与接入方交互开关。
     */
    private var renderer: TrajectoryEffectRendering = DefaultTrajectoryEffectRenderer(context)
    private var requestedInteractionEnabled = true

    /**
     * 触控采样节奏及屏幕刷新回调。
     */
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

    /**
     * 替换轨迹效果渲染器并更新其视图绑定。
     *
     * @param renderer 绘制触控轨迹效果的组件。
     */
    fun setRenderer(renderer: TrajectoryEffectRendering) {
        cancelInteraction()
        this.renderer.reset()
        removeView(this.renderer.view)
        this.renderer = renderer
        addRendererView(renderer)
    }

    /**
     * 设置轨迹交互的目标绑定；null 表示解除绑定。
     *
     * @param binding 视频轨道对应的显示或交互绑定。
     */
    fun setBinding(binding: TrajectoryBinding?) {
        this.binding = binding
        if (binding == null) cancelInteraction()
        updateInteractionState()
    }

    /**
     * 仅在绑定仍为指定对象时解除绑定。
     *
     * @param expected 预期仍在使用的绑定，避免解除后来替换的新绑定。
     */
    fun clearBinding(expected: TrajectoryBinding) {
        if (binding === expected) setBinding(null)
    }

    /**
     * 更新轨迹坐标换算使用的视频显示模式。
     *
     * @param contentMode 视频等比适配或等比裁切的显示方式。
     */
    fun setContentMode(contentMode: VideoContentMode) {
        if (videoContentMode == contentMode) return
        videoContentMode = contentMode
        cancelInteraction()
        updateInteractionState()
    }

    /**
     * 更新处理后的媒体尺寸并清除旧坐标下的活动轨迹。
     *
     * @param resolvedMediaSize 处理后的本地媒体尺寸；未知时为 null。
     */
    fun setResolvedMediaSize(resolvedMediaSize: IntSize?) {
        if (this.resolvedMediaSize == resolvedMediaSize) return
        this.resolvedMediaSize = resolvedMediaSize
        cancelInteraction()
        updateInteractionState()
    }

    /**
     * 设置接入方是否允许触控交互。
     *
     * @param enabled 是否启用对应能力。
     */
    fun setRequestedInteractionEnabled(enabled: Boolean) {
        if (requestedInteractionEnabled == enabled) return
        requestedInteractionEnabled = enabled
        if (!enabled) cancelInteraction()
        updateInteractionState()
    }

    /**
     * 处理触点的开始、移动和结束，并更新轨迹显示与提交。
     *
     * @param event Android 触摸事件。
     * @return 触摸事件已由该视图处理时为 true。
     */
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
                /**
                 * 与 Choreographer 的帧时间使用同一时钟，不计入设备深度休眠时间。
                 */
                submitIfNeeded(System.nanoTime(), force = true)
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

    /**
     * 转发视图点击事件以支持无障碍操作。
     *
     * @return 点击事件已被处理时为 true。
     */
    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    /**
     * 在视图离开窗口后解除绑定并停止显示回调。
     */
    override fun onDetachedFromWindow() {
        cancelInteraction()
        super.onDetachedFromWindow()
    }

    /**
     * 根据新的视图尺寸更新绘制布局。
     *
     * @param width 画面宽度，单位为像素。
     * @param height 画面高度，单位为像素。
     * @param oldWidth 视图变化前的宽度，单位为像素。
     * @param oldHeight 视图变化前的高度，单位为像素。
     */
    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (width != oldWidth || height != oldHeight) {
            cancelInteraction()
            updateInteractionState()
        }
    }

    /**
     * 结束指定触点对应的交互轨迹。
     *
     * @param pointerId Android 触摸事件中的指针标识。
     */
    private fun finishPointer(pointerId: Int) {
        val identifier = activeTouches.remove(pointerId)?.id ?: return
        renderer.renderEnded(listOf(identifier))
        if (activeTouches.isEmpty()) stopFrameCallback()
    }

    /**
     * 达到采样间隔或要求强制提交时发送当前轨迹。
     *
     * @param nowNanos 当前单调时钟时刻，单位为纳秒。
     * @param force 是否忽略采样间隔立即提交；默认值为 `false`。
     */
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

    /**
     * 结束所有活动触点并清除轨迹效果。
     */
    private fun cancelInteraction() {
        val identifiers = activeTouches.values.map(ActiveTouch::id)
        activeTouches.clear()
        if (identifiers.isNotEmpty()) renderer.renderEnded(identifiers)
        renderer.reset()
        lastSampleNanos = 0L
        stopFrameCallback()
    }

    /**
     * 按绑定、规格及接入方配置更新交互是否可用。
     */
    private fun updateInteractionState() {
        isEnabled = requestedInteractionEnabled && binding != null && interactionFrame() != null
        isClickable = isEnabled
    }

    /**
     * 计算固定媒体区域与视口相交的可交互区域。
     *
     * @return 可接收触控的视口区域；尺寸无效或没有交集时为 null。
     */
    private fun interactionFrame() = resolvedMediaSize?.let { size ->
        InteractionCoordinateMapper.displayedFrame(
            viewportSize = SizeF(width.toFloat(), height.toFloat()),
            resolvedMediaSize = SizeF(size.width.toFloat(), size.height.toFloat()),
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

    /**
     * 将触点限制在视频实际显示区域内。
     *
     * @param point 预览视图坐标系中的触点，单位为像素。
     * @return 处理后的视图像素坐标点。
     */
    private fun clamped(point: PointF): PointF {
        val frame = interactionFrame() ?: return point
        return PointF(
            point.x.coerceIn(frame.left, frame.right),
            point.y.coerceIn(frame.top, frame.bottom),
        )
    }

    /**
     * 安排下一次屏幕刷新时采样并提交轨迹。
     */
    private fun postFrameCallback() {
        if (frameCallbackPosted) return
        frameCallbackPosted = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    /**
     * 取消轨迹的屏幕刷新采样。
     */
    private fun stopFrameCallback() {
        if (!frameCallbackPosted) return
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        frameCallbackPosted = false
    }

    /**
     * 将轨迹渲染器的视图加入交互覆盖层。
     *
     * @param renderer 绘制触控轨迹效果的组件。
     */
    private fun addRendererView(renderer: TrajectoryEffectRendering) {
        val rendererView = renderer.view
        (rendererView.parent as? android.view.ViewGroup)?.removeView(rendererView)
        rendererView.isClickable = false
        addView(
            rendererView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
    }

    /**
     * 保存活动触点的轨迹标识与当前位置。
     *
     * @param id 用于区分当前对象或操作的唯一标识。
     * @param location 触点在视图中的位置，单位为像素。
     */
    private data class ActiveTouch(
        val id: TrajectoryID,
        var location: PointF,
    )

    /**
     * 将当前触点转换为带时间戳的轨迹点。
     *
     * @param timestamp 触点采样时刻，单位为秒。
     * @return 包含轨迹标识、位置及时间戳的触点。
     */
    private fun ActiveTouch.asTrajectoryPoint(timestamp: Double): TrajectoryPoint {
        val frame = resolvedMediaSize?.let { size ->
            InteractionCoordinateMapper.displayedFrame(
                viewportSize = SizeF(width.toFloat(), height.toFloat()),
                resolvedMediaSize = SizeF(size.width.toFloat(), size.height.toFloat()),
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

    /**
     * 提供 TrajectoryOverlayView 使用的构造入口、共享配置或常量。
     */
    private companion object {
        /**
         * 共享常量、默认配置与工厂入口。
         */
        const val SAMPLE_INTERVAL_NANOS = 33_333_333L
        const val MINIMUM_MOVE_SQUARED = 0.25f

        /**
         * 计算两点之间的距离平方，供位移阈值比较使用。
         *
         * @param first 第一个坐标点。
         * @param second 第二个坐标点。
         * @return 两点距离的平方，单位为平方像素。
         */
        fun distanceSquared(first: PointF, second: PointF): Float {
            val dx = second.x - first.x
            val dy = second.y - first.y
            return dx * dx + dy * dy
        }

        /**
         * 读取供轨迹动画使用的单调时钟秒数。
         *
         * @return 当前单调时钟秒数。
         */
        fun nowSeconds(): Double = SystemClock.elapsedRealtimeNanos() / 1_000_000_000.0
    }
}
