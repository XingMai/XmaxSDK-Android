//
//  QualityTiers.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.stream.quality

/**
 * 上下行共用的尺寸比例与上行帧率上限，档位编号从 1 开始。
 */
internal object QualityTiers {
    /**
     * 默认档位与最低输出尺寸约束。
     */
    const val INITIAL_LEVEL = 2
    const val MINIMUM_SHORT_SIDE = 240

    /**
     * 各档位的分辨率比例和帧率上限。
     */
    val percentages = listOf(100, 80, 65, 50, 50)
    val frameRates = listOf(30, 24, 24, 20, 16)
}
