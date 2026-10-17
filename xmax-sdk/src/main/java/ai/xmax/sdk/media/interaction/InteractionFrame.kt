//
//  InteractionFrame.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.media.interaction

import ai.xmax.sdk.VideoContentMode
import ai.xmax.sdk.RealtimeVideoFormat
import android.graphics.PointF
import android.util.SizeF

/**
 * 一次从渲染视口采集到的交互输入。
 *
 * @param points 视频画面像素坐标中的轨迹点集合。
 * @param viewportSize 预览视图的尺寸，单位为像素。
 * @param contentMode 视频等比适配或等比裁切的显示方式。
 * @param displayedVideoFormat 当前实际显示画面的视频规格。
 */
internal data class InteractionFrame(
    val points: List<PointF>,
    val viewportSize: SizeF,
    val contentMode: VideoContentMode,
    val displayedVideoFormat: RealtimeVideoFormat? = null,
)
