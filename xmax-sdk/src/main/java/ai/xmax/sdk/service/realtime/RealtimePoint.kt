//
//  RealtimePoint.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

/**
 * 归一化坐标系中的二维点。
 *
 * @param x 媒体横向相对位置，范围为 [0, 1]。
 * @param y 媒体纵向相对位置，范围为 [0, 1]。
 */
public data class RealtimePoint(
    public val x: Double,
    public val y: Double,
)
