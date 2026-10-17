//
//  VideoContentMode.kt
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
 * 视频内容在渲染容器中的显示模式。
 *
 * @param value 需要规范化、校验或显示的文本。
 */
public enum class VideoContentMode(public val value: String) {
    /**
     * 保持视频比例完整显示，容器剩余区域留空。
     */
    FIT("fit"),

    /**
     * 保持视频比例填满容器，超出区域裁切。
     */
    FILL("fill"),
}
