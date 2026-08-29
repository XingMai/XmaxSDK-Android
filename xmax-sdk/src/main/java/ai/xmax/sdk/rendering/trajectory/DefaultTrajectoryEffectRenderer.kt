package ai.xmax.sdk

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.view.View

/** SDK 内置的发光轨迹效果。 */
public class DefaultTrajectoryEffectRenderer(context: Context) : View(context),
    TrajectoryEffectRendering {
    override val view: View
        get() = this

    private val activePoints = mutableMapOf<TrajectoryID, TrajectoryPoint>()
    private val trails = mutableListOf<TrailSegment>()
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = GLOW_COLOR
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 18f * resources.displayMetrics.density
    }
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 3f * resources.displayMetrics.density
    }

    init {
        setBackgroundColor(Color.TRANSPARENT)
        isClickable = false
        isFocusable = false
    }

    override fun renderBegan(points: List<TrajectoryPoint>) {
        points.forEach { activePoints[it.id] = it }
        invalidate()
    }

    override fun renderMoved(points: List<TrajectoryPoint>) {
        points.forEach { point ->
            activePoints[point.id]?.let { previous ->
                trails += TrailSegment(previous.location, point.location)
            }
            activePoints[point.id] = point
        }
        invalidate()
    }

    override fun renderEnded(identifiers: List<TrajectoryID>) {
        identifiers.forEach(activePoints::remove)
        invalidate()
    }

    override fun reset() {
        activePoints.clear()
        trails.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        trails.forEach { segment ->
            glowPaint.alpha = (segment.alpha * 92f).toInt()
            corePaint.alpha = (segment.alpha * 210f).toInt()
            canvas.drawLine(
                segment.start.x,
                segment.start.y,
                segment.end.x,
                segment.end.y,
                glowPaint,
            )
            canvas.drawLine(
                segment.start.x,
                segment.start.y,
                segment.end.x,
                segment.end.y,
                corePaint,
            )
            segment.alpha -= FADE_STEP
        }
        trails.removeAll { it.alpha <= 0f }

        activePoints.values.forEach { point ->
            glowPaint.alpha = 180
            corePaint.alpha = 255
            canvas.drawCircle(
                point.location.x,
                point.location.y,
                12f * resources.displayMetrics.density,
                glowPaint,
            )
            canvas.drawCircle(
                point.location.x,
                point.location.y,
                4f * resources.displayMetrics.density,
                corePaint,
            )
        }
        if (trails.isNotEmpty() || activePoints.isNotEmpty()) postInvalidateOnAnimation()
    }

    private data class TrailSegment(
        val start: PointF,
        val end: PointF,
        var alpha: Float = 1f,
    )

    private companion object {
        const val GLOW_COLOR: Int = -16_711_836
        const val FADE_STEP: Float = 0.05f
    }
}
