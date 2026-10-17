//
//  StatisticsPanelTest.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package com.xmax.xlab.modules.xlrealtime

import org.junit.Assert.assertEquals
import org.junit.Test

class StatisticsPanelTest {
    @Test
    fun `unavailable metrics use placeholders but zero remains visible`() {
        assertEquals("—", statisticsMetric(null, "fps"))
        assertEquals("0 fps", statisticsMetric(0, "fps"))
        assertEquals("6293 kbps", statisticsMetric(6293, "kbps"))
        assertEquals("—", statisticsResolution(1024, null))
        assertEquals("1024 × 1920", statisticsResolution(1024, 1920))
    }
}
