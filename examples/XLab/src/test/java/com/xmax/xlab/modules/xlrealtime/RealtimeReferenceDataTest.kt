package com.xmax.xlab.modules.xlrealtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeReferenceDataTest {
    @Test
    fun categoriesMatchHarmonySnapshot() {
        assertEquals(
            listOf("charx", "clothx", "vibex", "dimx", "mox", "free"),
            realtimeReferenceCategories.map { it.id },
        )
        assertEquals(
            listOf("换形象", "换装", "换风格", "虚拟召唤", "触控动图", "自由"),
            realtimeReferenceCategories.map { it.name },
        )

        val referenceCounts = realtimeReferenceCategories.associate { it.id to it.references.size }
        assertEquals(
            mapOf(
                "charx" to 12,
                "clothx" to 12,
                "vibex" to 15,
                "mox" to 0,
                "dimx" to 12,
                "free" to 0,
            ),
            referenceCounts,
        )
        assertEquals(51, realtimeReferenceCategories.sumOf { it.references.size })
    }

    @Test
    fun remoteReferencesHaveStableIdsAndSecureUrls() {
        val references = realtimeReferenceCategories.flatMap { it.references }

        assertEquals(references.size, references.map { it.id }.toSet().size)
        assertTrue(references.all { it.iconUrl.startsWith("https://") })
        assertTrue(references.all { it.defaultReferenceUrl.startsWith("https://") })
    }
}
