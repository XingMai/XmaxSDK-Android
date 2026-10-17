//
//  DefaultTrajectoryEffectRenderer.kt
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
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * SDK 内置的发光轨迹效果。
 *
 * @param context Android 上下文；视图应使用具备当前界面主题的 Context。
 */
public class DefaultTrajectoryEffectRenderer(context: Context) : View(context),
    TrajectoryEffectRendering {
    /**
     * 提供给交互覆盖层的效果视图。
     */
    override val view: View
        get() = this

    /**
     * 当前活动轨迹的位置和动画状态。
     */
    private val activeTrajectories = mutableMapOf<TrajectoryID, ActiveTrajectory>()

    /**
     * 轨迹绘制所需的画笔与混合模式。
     */
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val additiveXfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)

    /**
     * 拖尾绘制缓冲及淡出状态。
     */
    private var trailBitmap: Bitmap? = null
    private var trailCanvas: Canvas? = null
    private var hasTrailPixels = false
    private var idleFadeFrameCount = 0

    init {
        setBackgroundColor(Color.TRANSPARENT)
        isClickable = false
        isFocusable = false
    }

    /**
     * 开始绘制新加入的触控轨迹。
     *
     * @param points 包含位置和时间戳的触控轨迹点。
     */
    override fun renderBegan(points: List<TrajectoryPoint>) {
        points.forEach { point ->
            activeTrajectories[point.id] = ActiveTrajectory(
                location = PointF(point.location.x, point.location.y),
                startTime = point.timestamp,
            )
        }
        invalidate()
    }

    /**
     * 更新正在显示的触控轨迹位置。
     *
     * @param points 包含位置和时间戳的触控轨迹点。
     */
    override fun renderMoved(points: List<TrajectoryPoint>) {
        points.forEach { point ->
            val trajectory = activeTrajectories[point.id]
            if (trajectory == null) {
                activeTrajectories[point.id] = ActiveTrajectory(
                    location = PointF(point.location.x, point.location.y),
                    startTime = point.timestamp,
                )
                return@forEach
            }
            if (distanceSquared(trajectory.location, point.location) >= MINIMUM_MOVE_SQUARED) {
                drawTrailSegment(trajectory.location, point.location)
            }
            trajectory.location = PointF(point.location.x, point.location.y)
        }
        invalidate()
    }

    /**
     * 结束指定轨迹的显示。
     *
     * @param identifiers 需要结束的轨迹标识集合。
     */
    override fun renderEnded(identifiers: List<TrajectoryID>) {
        identifiers.forEach(activeTrajectories::remove)
        invalidate()
    }

    /**
     * 清除所有轨迹及拖尾效果。
     */
    override fun reset() {
        activeTrajectories.clear()
        clearTrailBitmap()
        invalidate()
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
        rebuildTrailBitmap(width, height)
    }

    /**
     * 在视图进入窗口后恢复显示所需的绑定。
     */
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (trailBitmap == null) rebuildTrailBitmap(width, height)
    }

    /**
     * 在视图离开窗口后解除绑定并停止显示回调。
     */
    override fun onDetachedFromWindow() {
        reset()
        trailBitmap?.recycle()
        trailBitmap = null
        trailCanvas = null
        super.onDetachedFromWindow()
    }

    /**
     * 绘制当前轨迹的拖尾、光晕及动态效果。
     *
     * @param canvas 用于绘制当前视图的画布。
     */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        fadeTrailBitmap()
        trailBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }

        val now = SystemClock.elapsedRealtimeNanos() / NANOS_PER_SECOND
        activeTrajectories.values.forEach { trajectory ->
            drawPulsingRings(canvas, trajectory, now)
            drawOrbitParticles(canvas, trajectory, now)
            drawHeadGlow(canvas, trajectory.location)
        }

        if (hasTrailPixels || activeTrajectories.isNotEmpty()) {
            postInvalidateOnAnimation()
        }
    }

    /**
     * 按当前视图尺寸重建拖尾绘制缓冲。
     *
     * @param width 画面宽度，单位为像素。
     * @param height 画面高度，单位为像素。
     */
    private fun rebuildTrailBitmap(width: Int, height: Int) {
        trailBitmap?.recycle()
        trailBitmap = null
        trailCanvas = null
        hasTrailPixels = false
        idleFadeFrameCount = 0
        if (width <= 0 || height <= 0) return

        trailBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            trailCanvas = Canvas(bitmap)
        }
    }

    /**
     * 绘制相邻触点之间的发光拖尾。
     *
     * @param start 线段起点，使用视图像素坐标。
     * @param end 线段终点，使用视图像素坐标。
     */
    private fun drawTrailSegment(start: PointF, end: PointF) {
        val canvas = trailCanvas ?: return
        drawLine(canvas, start, end, dp(18f), GLOW_COLOR, 0.22f, dp(12f))
        drawLine(canvas, start, end, dp(10f), GLOW_COLOR, 0.52f, dp(6f))
        drawLine(canvas, start, end, dp(3f), Color.WHITE, 0.82f, 0f)
        hasTrailPixels = true
        idleFadeFrameCount = 0
    }

    /**
     * 按指定线宽、透明度和模糊半径绘制线段。
     *
     * @param canvas 用于绘制当前视图的画布。
     * @param start 线段起点，使用视图像素坐标。
     * @param end 线段终点，使用视图像素坐标。
     * @param width 画面宽度，单位为像素。
     * @param color ARGB 颜色值。
     * @param alpha 不透明度，范围为 0 到 1。
     * @param blur 模糊半径，单位为像素。
     */
    private fun drawLine(
        canvas: Canvas,
        start: PointF,
        end: PointF,
        width: Float,
        color: Int,
        alpha: Float,
        blur: Float,
    ) {
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeWidth = width
        paint.color = color
        paint.alpha = (alpha * 255f).toInt()
        paint.maskFilter = if (blur > 0f) BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL) else null
        paint.xfermode = additiveXfermode
        canvas.drawLine(start.x, start.y, end.x, end.y, paint)
        paint.xfermode = null
        paint.maskFilter = null
    }

    /**
     * 降低已有拖尾的不透明度，产生逐渐消失的效果。
     */
    private fun fadeTrailBitmap() {
        if (!hasTrailPixels) return
        trailCanvas?.drawColor(FADE_COLOR, PorterDuff.Mode.DST_OUT)
        if (activeTrajectories.isNotEmpty()) {
            idleFadeFrameCount = 0
            return
        }

        idleFadeFrameCount += 1
        if (idleFadeFrameCount > IDLE_FADE_FRAME_LIMIT) clearTrailBitmap()
    }

    /**
     * 清空拖尾绘制缓冲。
     */
    private fun clearTrailBitmap() {
        trailCanvas?.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        hasTrailPixels = false
        idleFadeFrameCount = 0
    }

    /**
     * 绘制随时间变化的触点环形效果。
     *
     * @param canvas 用于绘制当前视图的画布。
     * @param trajectory 当前绘制的活动轨迹。
     * @param now 当前单调时钟时间，单位为秒。
     */
    private fun drawPulsingRings(
        canvas: Canvas,
        trajectory: ActiveTrajectory,
        now: Double,
    ) {
        val elapsed = (now - trajectory.startTime).toFloat()
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2f)
        paint.color = GLOW_COLOR
        paint.xfermode = additiveXfermode
        repeat(2) { index ->
            val pulse = ((sin((elapsed * 1.2f + index * 0.5f) * PI * 2) + 1) / 2).toFloat()
            val radius = dp(14f + index * 18f + pulse * 8f)
            val alpha = 0.5f * (1f - index * 0.2f) * (0.5f + pulse * 0.5f)
            paint.alpha = (alpha * 255f).toInt()
            canvas.drawCircle(trajectory.location.x, trajectory.location.y, radius, paint)
        }
        paint.xfermode = null
    }

    /**
     * 绘制绕触点移动的粒子效果。
     *
     * @param canvas 用于绘制当前视图的画布。
     * @param trajectory 当前绘制的活动轨迹。
     * @param now 当前单调时钟时间，单位为秒。
     */
    private fun drawOrbitParticles(
        canvas: Canvas,
        trajectory: ActiveTrajectory,
        now: Double,
    ) {
        val elapsed = (now - trajectory.startTime).toFloat()
        repeat(4) { index ->
            val direction = if (index % 2 == 0) 1f else -1f
            val baseAngle = index.toFloat() / 4f * PI.toFloat() * 2f
            val angle = baseAngle + elapsed * 0.06f * direction
            val centerX = trajectory.location.x + cos(angle) * dp(22f)
            val centerY = trajectory.location.y + sin(angle) * dp(22f)
            val alpha = 0.6f * (0.6f + sin(elapsed * 3f + index) * 0.4f)
            drawRadialGlow(canvas, centerX, centerY, dp(6f), GLOW_COLOR, alpha)
        }
    }

    /**
     * 在轨迹头部绘制光晕。
     *
     * @param canvas 用于绘制当前视图的画布。
     * @param location 触点在视图中的位置，单位为像素。
     */
    private fun drawHeadGlow(canvas: Canvas, location: PointF) {
        drawRadialGlow(canvas, location.x, location.y, dp(16f), GLOW_COLOR, 0.9f)
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        paint.xfermode = additiveXfermode
        canvas.drawCircle(location.x, location.y, dp(5f), paint)
        paint.xfermode = null
    }

    /**
     * 按指定位置、半径和透明度绘制径向光晕。
     *
     * @param canvas 用于绘制当前视图的画布。
     * @param centerX 效果中心横坐标，单位为像素。
     * @param centerY 效果中心纵坐标，单位为像素。
     * @param radius 效果半径，单位为像素。
     * @param color ARGB 颜色值。
     * @param alpha 不透明度，范围为 0 到 1。
     */
    private fun drawRadialGlow(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        radius: Float,
        color: Int,
        alpha: Float,
    ) {
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(
            centerX,
            centerY,
            radius,
            intArrayOf(
                colorWithAlpha(color, alpha),
                colorWithAlpha(color, alpha * 0.6f),
                Color.TRANSPARENT,
            ),
            floatArrayOf(0f, 0.4f, 1f),
            Shader.TileMode.CLAMP,
        )
        paint.xfermode = additiveXfermode
        canvas.drawCircle(centerX, centerY, radius, paint)
        paint.xfermode = null
        paint.shader = null
    }

    /**
     * 将密度无关像素转换为当前设备像素。
     *
     * @param value 需要转换的密度无关像素数。
     * @return 转换后的像素数。
     */
    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    /**
     * 保存活动轨迹的位置和动画起始时间。
     *
     * @param location 触点在视图中的位置，单位为像素。
     * @param startTime 轨迹开始时的单调时钟秒数。
     */
    private data class ActiveTrajectory(
        var location: PointF,
        val startTime: Double,
    )

    /**
     * 提供 DefaultTrajectoryEffectRenderer 使用的构造入口、共享配置或常量。
     */
    private companion object {
        /**
         * 共享常量、默认配置与工厂入口。
         */
        const val GLOW_COLOR: Int = -16_711_836
        const val MINIMUM_MOVE_SQUARED = 0.25f
        const val IDLE_FADE_FRAME_LIMIT = 64
        const val NANOS_PER_SECOND = 1_000_000_000.0
        val FADE_COLOR: Int = Color.argb((0.05f * 255f).toInt(), 0, 0, 0)

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
         * 为指定颜色应用透明度。
         *
         * @param color ARGB 颜色值。
         * @param alpha 不透明度，范围为 0 到 1。
         * @return 应用透明度后的 ARGB 颜色值。
         */
        fun colorWithAlpha(color: Int, alpha: Float): Int {
            return Color.argb(
                (alpha.coerceIn(0f, 1f) * 255f).toInt(),
                Color.red(color),
                Color.green(color),
                Color.blue(color),
            )
        }
    }
}
