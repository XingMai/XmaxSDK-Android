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
import kotlin.math.round

/**
 * 将渲染视口坐标映射到指定视频尺寸的像素坐标。
 */
internal object InteractionCoordinateMapper {
    /**
     * 计算视频按指定显示模式布局后的实际画面区域。
     *
     * @param viewportSize 预览视图的尺寸，单位为像素。
     * @param videoSize 视频经过旋转后的显示尺寸。
     * @param contentMode 视频等比适配或等比裁切的显示方式。
     * @return 视频在视图中的实际显示区域；尺寸无效时为 null。
     */
    fun displayedFrame(
        viewportSize: SizeF,
        videoSize: SizeF,
        contentMode: VideoContentMode,
    ): RectF? {
        if (viewportSize.width <= 0f || viewportSize.height <= 0f ||
            videoSize.width <= 0f || videoSize.height <= 0f
        ) {
            return null
        }
        val widthScale = viewportSize.width / videoSize.width
        val heightScale = viewportSize.height / videoSize.height
        val scale = if (contentMode == VideoContentMode.FILL) {
            max(widthScale, heightScale)
        } else {
            min(widthScale, heightScale)
        }
        if (!scale.isFinite() || scale <= 0f) return null

        val displayedWidth = videoSize.width * scale
        val displayedHeight = videoSize.height * scale
        val left = (viewportSize.width - displayedWidth) / 2f
        val top = (viewportSize.height - displayedHeight) / 2f
        return RectF(left, top, left + displayedWidth, top + displayedHeight)
    }

    /**
     * 将视图坐标转换为视频画面的像素坐标；FIT 留白区域不产生轨迹点。
     *
     * @param point 预览视图坐标系中的触点，单位为像素。
     * @param viewportSize 预览视图的尺寸，单位为像素。
     * @param videoSize 视频经过旋转后的显示尺寸。
     * @param contentMode 视频等比适配或等比裁切的显示方式。
     * @return 视频画面的像素坐标；输入无效或位于 FIT 留白区时为 null。
     */
    fun map(
        point: PointF,
        viewportSize: SizeF,
        videoSize: SizeF,
        contentMode: VideoContentMode,
    ): RealtimePoint? {
        if (!point.x.isFinite() || !point.y.isFinite()) return null
        val displayedFrame = displayedFrame(viewportSize, videoSize, contentMode) ?: return null
        if (contentMode == VideoContentMode.FIT && !displayedFrame.contains(point.x, point.y)) {
            return null
        }
        val scale = displayedFrame.width() / videoSize.width
        if (!scale.isFinite() || scale <= 0f) return null
        val mappedX = round((point.x - displayedFrame.left) / scale)
            .coerceIn(0f, videoSize.width - 1f)
        val mappedY = round((point.y - displayedFrame.top) / scale)
            .coerceIn(0f, videoSize.height - 1f)
        return RealtimePoint(mappedX.toDouble(), mappedY.toDouble())
    }
}
