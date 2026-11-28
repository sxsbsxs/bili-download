package com.example.bilidl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatchSelectionPolicyTest {
    @Test
    fun toggleAddsAndRemovesWithoutDuplicates() {
        val first = BatchSelectionPolicy.toggle(emptyList(), "https://www.bilibili.com/video/BVabc")!!
        assertEquals(listOf("https://www.bilibili.com/video/BVabc"), first)
        assertEquals(emptyList<String>(), BatchSelectionPolicy.toggle(first, first.first()))
    }

    @Test
    fun refusesMoreThanFiftyUniqueUrls() {
        val current = (1..BatchSelectionPolicy.MAX_ITEMS).map {
            "https://www.bilibili.com/video/BV" + it
        }
        assertNull(BatchSelectionPolicy.toggle(current, "https://www.bilibili.com/video/BV51"))
    }

    @Test
    fun normalizeTrimsAndDeduplicates() {
        assertEquals(
            listOf("a", "b"),
            BatchSelectionPolicy.normalize(listOf(" a ", "b", "a", " "))
        )
    }
}
