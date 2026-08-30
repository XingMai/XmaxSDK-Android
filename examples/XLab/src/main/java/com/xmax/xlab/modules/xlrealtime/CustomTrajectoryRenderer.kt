package com.xmax.xlab.modules.xlrealtime

import ai.xmax.sdk.TrajectoryEffectRendering
import ai.xmax.sdk.TrajectoryID
import ai.xmax.sdk.TrajectoryPoint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** XLab 自定义轨迹效果，以蓝、粉双色区分多指轨迹。 */
internal class CustomTrajectoryRenderer(context: Context) : View(context),
    TrajectoryEffectRendering {
    override val view: View
        get() = this

    private val activeTrajectories = mutableMapOf<TrajectoryID, ActiveTrajectory>()
    private val trailSegments = mutableListOf<TrailSegment>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private var nextPaletteIndex = 0

    init {
        setBackgroundColor(Color.TRANSPARENT)
        isClickable = false
        isFocusable = false
    }

    override fun renderBegan(points: List<TrajectoryPoint>) {
        points.forEach { point ->
            activeTrajectories[point.id] = makeTrajectory(point)
        }
        invalidate()
    }

    override fun renderMoved(points: List<TrajectoryPoint>) {
        points.forEach { point ->
            val trajectory = activeTrajectories[point.id]
            if (trajectory == null) {
                activeTrajectories[point.id] = makeTrajectory(point)
                return@forEach
            }
            if (distanceSquared(trajectory.location, point.location) >= MINIMUM_MOVE_SQUARED) {
                trailSegments += TrailSegment(
                    start = PointF(trajectory.location.x, trajectory.location.y),
                    end = PointF(point.location.x, point.location.y),
                    coreColor = trajectory.coreColor,
                    glowColor = trajectory.glowColor,
                )
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
        trailSegments.clear()
        nextPaletteIndex = 0
        invalidate()
    }

    override fun onDetachedFromWindow() {
        reset()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawTrails(canvas)
        val now = SystemClock.elapsedRealtimeNanos() / 1_000_000_000.0
        activeTrajectories.values.forEach { trajectory ->
            drawPulsingRings(canvas, trajectory, now)
            drawOrbitParticles(canvas, trajectory, now)
            drawHeadGlow(canvas, trajectory)
        }
        if (trailSegments.isNotEmpty() || activeTrajectories.isNotEmpty()) {
            postInvalidateOnAnimation()
        }
    }

    private fun makeTrajectory(point: TrajectoryPoint): ActiveTrajectory {
        val colors = PALETTE[nextPaletteIndex % PALETTE.size]
        nextPaletteIndex += 1
        return ActiveTrajectory(
            location = PointF(point.location.x, point.location.y),
            startTime = point.timestamp,
            coreColor = colors.core,
            glowColor = colors.glow,
        )
    }

    private fun drawTrails(canvas: Canvas) {
        trailSegments.forEach { segment ->
            drawLine(
                canvas = canvas,
                segment = segment,
                width = dp(18f),
                color = segment.glowColor,
                alpha = segment.alpha * 0.22f,
            )
            drawLine(
                canvas = canvas,
                segment = segment,
                width = dp(10f),
                color = segment.glowColor,
                alpha = segment.alpha * 0.52f,
            )
            drawLine(
                canvas = canvas,
                segment = segment,
                width = dp(3f),
                color = segment.coreColor,
                alpha = segment.alpha * 0.82f,
            )
            segment.alpha -= FADE_STEP
        }
        trailSegments.removeAll { it.alpha <= 0f }
    }

    private fun drawLine(
        canvas: Canvas,
        segment: TrailSegment,
        width: Float,
        color: Int,
        alpha: Float,
    ) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = width
        paint.color = color
        paint.alpha = (alpha.coerceIn(0f, 1f) * 255f).toInt()
        canvas.drawLine(
            segment.start.x,
            segment.start.y,
            segment.end.x,
            segment.end.y,
            paint,
        )
    }

    private fun drawPulsingRings(
        canvas: Canvas,
        trajectory: ActiveTrajectory,
        now: Double,
    ) {
        val elapsed = (now - trajectory.startTime).toFloat()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2f)
        paint.color = trajectory.glowColor
        repeat(2) { index ->
            val pulse = ((sin((elapsed * 1.2f + index * 0.5f) * PI * 2) + 1) / 2).toFloat()
            val radius = dp(14f + index * 18f + pulse * 8f)
            val alpha = 0.5f * (1f - index * 0.2f) * (0.5f + pulse * 0.5f)
            paint.alpha = (alpha * 255f).toInt()
            canvas.drawCircle(trajectory.location.x, trajectory.location.y, radius, paint)
        }
    }

    private fun drawOrbitParticles(
        canvas: Canvas,
        trajectory: ActiveTrajectory,
        now: Double,
    ) {
        val elapsed = (now - trajectory.startTime).toFloat()
        paint.style = Paint.Style.FILL
        paint.color = trajectory.glowColor
        repeat(4) { index ->
            val direction = if (index % 2 == 0) 1f else -1f
            val baseAngle = index.toFloat() / 4f * PI.toFloat() * 2f
            val angle = baseAngle + elapsed * 0.06f * direction
            val centerX = trajectory.location.x + cos(angle) * dp(22f)
            val centerY = trajectory.location.y + sin(angle) * dp(22f)
            val alpha = 0.6f * (0.6f + sin(elapsed * 3f + index) * 0.4f)
            paint.alpha = (alpha.coerceIn(0f, 1f) * 255f).toInt()
            canvas.drawCircle(centerX, centerY, dp(3f), paint)
            paint.alpha = (alpha.coerceIn(0f, 1f) * 72f).toInt()
            canvas.drawCircle(centerX, centerY, dp(6f), paint)
        }
    }

    private fun drawHeadGlow(canvas: Canvas, trajectory: ActiveTrajectory) {
        paint.style = Paint.Style.FILL
        paint.color = trajectory.glowColor
        paint.alpha = 48
        canvas.drawCircle(trajectory.location.x, trajectory.location.y, dp(16f), paint)
        paint.alpha = 138
        canvas.drawCircle(trajectory.location.x, trajectory.location.y, dp(8f), paint)
        paint.color = trajectory.coreColor
        paint.alpha = 255
        canvas.drawCircle(trajectory.location.x, trajectory.location.y, dp(5f), paint)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private data class ActiveTrajectory(
        var location: PointF,
        val startTime: Double,
        val coreColor: Int,
        val glowColor: Int,
    )

    private data class TrailSegment(
        val start: PointF,
        val end: PointF,
        val coreColor: Int,
        val glowColor: Int,
        var alpha: Float = 1f,
    )

    private data class TrajectoryColors(
        val core: Int,
        val glow: Int,
    )

    private companion object {
        val PALETTE = listOf(
            TrajectoryColors(core = Color.rgb(255, 230, 250), glow = Color.rgb(255, 46, 184)),
            TrajectoryColors(core = Color.rgb(230, 250, 255), glow = Color.rgb(36, 189, 255)),
        )
        const val MINIMUM_MOVE_SQUARED = 0.25f
        const val FADE_STEP = 0.05f

        fun distanceSquared(first: PointF, second: PointF): Float {
            val dx = second.x - first.x
            val dy = second.y - first.y
            return dx * dx + dy * dy
        }
    }
}
