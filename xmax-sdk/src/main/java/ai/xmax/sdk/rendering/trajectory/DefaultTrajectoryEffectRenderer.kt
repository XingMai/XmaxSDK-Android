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

/** SDK 内置的发光轨迹效果。 */
public class DefaultTrajectoryEffectRenderer(context: Context) : View(context),
    TrajectoryEffectRendering {
    override val view: View
        get() = this

    private val activeTrajectories = mutableMapOf<TrajectoryID, ActiveTrajectory>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val additiveXfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    private var trailBitmap: Bitmap? = null
    private var trailCanvas: Canvas? = null
    private var hasTrailPixels = false
    private var idleFadeFrameCount = 0

    init {
        setBackgroundColor(Color.TRANSPARENT)
        isClickable = false
        isFocusable = false
    }

    override fun renderBegan(points: List<TrajectoryPoint>) {
        points.forEach { point ->
            activeTrajectories[point.id] = ActiveTrajectory(
                location = PointF(point.location.x, point.location.y),
                startTime = point.timestamp,
            )
        }
        invalidate()
    }

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

    override fun renderEnded(identifiers: List<TrajectoryID>) {
        identifiers.forEach(activeTrajectories::remove)
        invalidate()
    }

    override fun reset() {
        activeTrajectories.clear()
        clearTrailBitmap()
        invalidate()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        rebuildTrailBitmap(width, height)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (trailBitmap == null) rebuildTrailBitmap(width, height)
    }

    override fun onDetachedFromWindow() {
        reset()
        trailBitmap?.recycle()
        trailBitmap = null
        trailCanvas = null
        super.onDetachedFromWindow()
    }

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

    private fun drawTrailSegment(start: PointF, end: PointF) {
        val canvas = trailCanvas ?: return
        drawLine(canvas, start, end, dp(18f), GLOW_COLOR, 0.22f, dp(12f))
        drawLine(canvas, start, end, dp(10f), GLOW_COLOR, 0.52f, dp(6f))
        drawLine(canvas, start, end, dp(3f), Color.WHITE, 0.82f, 0f)
        hasTrailPixels = true
        idleFadeFrameCount = 0
    }

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

    private fun clearTrailBitmap() {
        trailCanvas?.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        hasTrailPixels = false
        idleFadeFrameCount = 0
    }

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

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private data class ActiveTrajectory(
        var location: PointF,
        val startTime: Double,
    )

    private companion object {
        const val GLOW_COLOR: Int = -16_711_836
        const val MINIMUM_MOVE_SQUARED = 0.25f
        const val IDLE_FADE_FRAME_LIMIT = 64
        const val NANOS_PER_SECOND = 1_000_000_000.0
        val FADE_COLOR: Int = Color.argb((0.05f * 255f).toInt(), 0, 0, 0)

        fun distanceSquared(first: PointF, second: PointF): Float {
            val dx = second.x - first.x
            val dy = second.y - first.y
            return dx * dx + dy * dy
        }

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
