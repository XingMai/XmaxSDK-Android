package ai.xmax.sdk.media.interaction

import ai.xmax.sdk.VideoContentMode
import android.graphics.PointF
import android.util.SizeF

/** 一次从渲染视口采集到的交互输入。 */
internal data class InteractionFrame(
    val points: List<PointF>,
    val viewportSize: SizeF,
    val contentMode: VideoContentMode,
)
