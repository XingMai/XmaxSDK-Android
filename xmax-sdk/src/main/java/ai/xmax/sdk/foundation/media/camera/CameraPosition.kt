//
//  CameraPosition.kt
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
 * 相机采集使用的镜头方向。
 *
 * @param value 需要规范化、校验或显示的文本。
 */
public enum class CameraPosition(public val value: String) {
    /**
     * 前置摄像头。
     */
    FRONT("front"),

    /**
     * 后置摄像头。
     */
    BACK("back"),
}
