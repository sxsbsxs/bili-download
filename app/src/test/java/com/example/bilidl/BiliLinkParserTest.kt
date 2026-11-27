package com.example.bilidl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BiliLinkParserTest {
    @Test
    fun extractsBvidFromShareText() {
        assertEquals(
            "https://www.bilibili.com/video/BV1xx411c7mD",
            BiliLinkParser.normalize("复制打开 https://www.bilibili.com/video/BV1xx411c7mD 看看")
        )
    }

    @Test
    fun convertsAvid() {
        assertEquals(
            "https://www.bilibili.com/video/av170001",
            BiliLinkParser.normalize("av170001")
        )
    }

    @Test
    fun keepsShortLinkForRedirectResolution() {
        assertEquals(
            "https://b23.tv/AbCd12",
            BiliLinkParser.normalize("分享链接：https://b23.tv/AbCd12。")
        )
    }

    @Test
    fun convertsBangumiEpisode() {
        assertEquals(
            "https://www.bilibili.com/bangumi/play/ep12345",
            BiliLinkParser.normalize("ep12345")
        )
    }

    @Test
    fun extractsMultipleUrlsAndDeduplicatesInOriginalOrder() {
        assertEquals(
            listOf(
                "https://www.bilibili.com/video/BV1xx411c7mD",
                "https://b23.tv/AbCd12"
            ),
            BiliLinkParser.extractAll(
                "复制链接 https://www.bilibili.com/video/BV1xx411c7mD。\n" +
                    "https://b23.tv/AbCd12\n" +
                    "https://www.bilibili.com/video/BV1xx411c7mD"
            )
        )
    }

    @Test
    fun extractsMixedBareIdsInOriginalOrder() {
        assertEquals(
            listOf(
                "https://www.bilibili.com/bangumi/play/ep12345",
                "https://www.bilibili.com/video/BV1xx411c7mD",
                "https://www.bilibili.com/video/av170001"
            ),
            BiliLinkParser.extractAll("ep12345\nBV1xx411c7mD\nav170001")
        )
    }

    @Test
    fun rejectsForeignOnlyBatchLinks() {
        assertEquals(
            emptyList<String>(),
            BiliLinkParser.extractAll("https://example.com/BV1xx411c7mD")
        )
    }

    @Test
    fun rejectsForeignHost() {
        assertNull(BiliLinkParser.normalize("https://example.com/video/BV1xx411c7mD"))
    }
}
