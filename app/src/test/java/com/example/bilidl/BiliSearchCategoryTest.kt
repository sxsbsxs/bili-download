package com.example.bilidl

import org.junit.Assert.assertEquals
import org.junit.Test

class BiliSearchCategoryTest {
    @Test
    fun mapsAllBvdSearchCategoriesToOfficialApiTypes() {
        assertEquals("video", BiliSearchCategory.VIDEO.apiType)
        assertEquals("media_bangumi", BiliSearchCategory.ANIME.apiType)
        assertEquals("media_ft", BiliSearchCategory.FILM_TV.apiType)
    }

    @Test
    fun exposesConciseChineseLabels() {
        assertEquals(listOf("视频", "番剧", "影视"), BiliSearchCategory.values().map { it.displayName })
    }
}
