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
 * @param x 画面横坐标，单位为像素。
 * @param y 画面纵坐标，单位为像素。
 */
public data class RealtimePoint(
    public val x: Double,
    public val y: Double,
)
