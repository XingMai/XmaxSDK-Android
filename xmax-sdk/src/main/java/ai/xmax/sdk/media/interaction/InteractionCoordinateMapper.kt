//
//  InteractionCoordinateMapper.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.media.interaction

import ai.xmax.sdk.RealtimePoint
import ai.xmax.sdk.VideoContentMode
import android.graphics.PointF
import android.graphics.RectF
import android.util.SizeF
import kotlin.math.max
import kotlin.math.min

/**
 * 将渲染视口坐标映射到固定媒体基准的归一化坐标。
 */
internal object InteractionCoordinateMapper {
    /**
     * 按处理后的媒体比例和显示模式计算视口中的媒体区域。
     *
     * @param viewportSize 预览视图的尺寸，单位为像素。
     * @param resolvedMediaSize 经面积上限和对齐处理的固定媒体尺寸。
     * @param contentMode 视频等比适配或等比裁切的显示方式。
     * @return 视频在视图中的实际显示区域；尺寸无效时为 null。
     */
    fun displayedFrame(
        viewportSize: SizeF,
        resolvedMediaSize: SizeF,
        contentMode: VideoContentMode,
    ): RectF? {
        if (!viewportSize.width.isFinite() || !viewportSize.height.isFinite() || !resolvedMediaSize.width.isFinite() || !resolvedMediaSize.height.isFinite()) return null

        if (viewportSize.width <= 0f || viewportSize.height <= 0f || resolvedMediaSize.width < 1f || resolvedMediaSize.height < 1f) return null
        val widthScale = viewportSize.width / resolvedMediaSize.width
        val heightScale = viewportSize.height / resolvedMediaSize.height
        val scale = if (contentMode == VideoContentMode.FILL) {
            max(widthScale, heightScale)
        } else {
            min(widthScale, heightScale)
        }
        if (!scale.isFinite() || scale <= 0f) return null

        val displayedWidth = resolvedMediaSize.width * scale
        val displayedHeight = resolvedMediaSize.height * scale
        val left = (viewportSize.width - displayedWidth) / 2f
        val top = (viewportSize.height - displayedHeight) / 2f
        return RectF(left, top, left + displayedWidth, top + displayedHeight)
    }

    /**
     * 根据处理后的媒体比例计算完整媒体的归一化坐标；FIT 留白区域不产生轨迹点。
     *
     * @param point 预览视图坐标系中的触点，单位为像素。
     * @param viewportSize 预览视图的尺寸，单位为像素。
     * @param resolvedMediaSize 经面积上限和对齐处理的固定媒体尺寸，作为归一化坐标基准。
     * @param contentMode 视频等比适配或等比裁切的显示方式。
     * @return 范围为 [0, 1] 的媒体坐标；输入无效或位于 FIT 留白区时为 null。
     */
    fun map(
        point: PointF,
        viewportSize: SizeF,
        resolvedMediaSize: SizeF,
        contentMode: VideoContentMode,
    ): RealtimePoint? {
        if (!point.x.isFinite() || !point.y.isFinite()) return null

        val displayedFrame = displayedFrame(viewportSize, resolvedMediaSize, contentMode) ?: return null
        if (contentMode == VideoContentMode.FIT && !displayedFrame.contains(point.x, point.y)) {
            return null
        }
        val normalizedX = (point.x.toDouble() - displayedFrame.left) / displayedFrame.width()
        val normalizedY = (point.y.toDouble() - displayedFrame.top) / displayedFrame.height()
        return RealtimePoint(normalizedX.coerceIn(0.0, 1.0), normalizedY.coerceIn(0.0, 1.0))
    }
}
