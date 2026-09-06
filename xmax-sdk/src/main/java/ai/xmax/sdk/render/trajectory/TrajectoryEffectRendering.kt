package ai.xmax.sdk

import android.graphics.PointF
import android.view.View
import java.util.UUID

/** 一次活动轨迹的稳定标识。 */
public class TrajectoryID internal constructor(
    internal val rawValue: UUID = UUID.randomUUID(),
) {
    override fun equals(other: Any?): Boolean = other is TrajectoryID && rawValue == other.rawValue

    override fun hashCode(): Int = rawValue.hashCode()
}

/** 交给轨迹效果渲染器的可视化触点。 */
public class TrajectoryPoint internal constructor(
    public val id: TrajectoryID,
    public val location: PointF,
    public val normalizedLocation: PointF,
    public val timestamp: Double,
)

/** 自定义轨迹效果的绘制接口。 */
public interface TrajectoryEffectRendering {
    public val view: View

    public fun renderBegan(points: List<TrajectoryPoint>)

    public fun renderMoved(points: List<TrajectoryPoint>)

    public fun renderEnded(identifiers: List<TrajectoryID>)

    public fun reset()
}
