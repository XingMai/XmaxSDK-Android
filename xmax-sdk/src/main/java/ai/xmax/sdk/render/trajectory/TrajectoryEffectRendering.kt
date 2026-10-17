//
//  TrajectoryEffectRendering.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

import android.graphics.PointF
import android.view.View
import java.util.UUID

/**
 * 一次活动轨迹的稳定标识。
 *
 * @param rawValue 用于构造值对象的底层值。
 */
public class TrajectoryID internal constructor(
    internal val rawValue: UUID = UUID.randomUUID(),
) {
    /**
     * 比较对象的值是否相等。
     *
     * @param other 用于比较或组合的另一个对象。
     * @return 对象的内容相等时为 true，否则为 false。
     */
    override fun equals(other: Any?): Boolean = other is TrajectoryID && rawValue == other.rawValue

    /**
     * 生成与值相等规则一致的哈希值。
     *
     * @return 与值相等规则一致的哈希值。
     */
    override fun hashCode(): Int = rawValue.hashCode()
}

/**
 * 交给轨迹效果渲染器的可视化触点。
 *
 * @param id 用于区分当前对象或操作的唯一标识。
 * @param location 触点在视图中的位置，单位为像素。
 * @param normalizedLocation 触点在画面中的归一化位置。
 * @param timestamp 触点的单调时间戳，单位为秒。
 */
public class TrajectoryPoint internal constructor(
    public val id: TrajectoryID,
    public val location: PointF,
    public val normalizedLocation: PointF,
    public val timestamp: Double,
)

/**
 * 自定义轨迹效果的绘制接口。
 */
public interface TrajectoryEffectRendering {
    /**
     * 提供给容器的轨迹显示视图。
     */
    public val view: View

    /**
     * 开始绘制新加入的触控轨迹。
     *
     * @param points 包含位置和时间戳的触控轨迹点。
     */
    public fun renderBegan(points: List<TrajectoryPoint>)

    /**
     * 更新正在显示的触控轨迹位置。
     *
     * @param points 包含位置和时间戳的触控轨迹点。
     */
    public fun renderMoved(points: List<TrajectoryPoint>)

    /**
     * 结束指定轨迹的显示。
     *
     * @param identifiers 需要结束的轨迹标识集合。
     */
    public fun renderEnded(identifiers: List<TrajectoryID>)

    /**
     * 清除所有轨迹及渲染效果。
     */
    public fun reset()
}
