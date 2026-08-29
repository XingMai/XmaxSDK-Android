package ai.xmax.sdk.media.interaction

import ai.xmax.sdk.RealtimePoint
import ai.xmax.sdk.VideoContentMode
import android.graphics.PointF
import android.graphics.RectF
import android.util.SizeF
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/** 将渲染视口坐标映射到模型输入视频的像素坐标。 */
internal object InteractionCoordinateMapper {
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
