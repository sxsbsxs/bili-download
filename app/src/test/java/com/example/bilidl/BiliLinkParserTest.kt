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
    fun rejectsForeignHost() {
        assertNull(BiliLinkParser.normalize("https://example.com/video/BV1xx411c7mD"))
    }
}
