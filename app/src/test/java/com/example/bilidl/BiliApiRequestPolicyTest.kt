package com.example.bilidl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BiliApiRequestPolicyTest {
    @Test
    fun viewMetadataUsesOkHttpUserAgentLikeBvd() {
        assertEquals(
            BiliApiRequestPolicy.OKHTTP_USER_AGENT,
            BiliApiRequestPolicy.userAgent("https://api.bilibili.com/x/web-interface/view?bvid=BV1xx")
        )
    }

    @Test
    fun playurlUsesDesktopBrowserUserAgentLikeBvd() {
        assertEquals(
            BiliCdnRequestPolicy.USER_AGENT,
            BiliApiRequestPolicy.userAgent("https://api.bilibili.com/x/player/playurl?bvid=BV1xx")
        )
        assertTrue(BiliApiRequestPolicy.userAgent("https://api.bilibili.com/x/player/playurl")
            .contains("Edg/124.0.0.0"))
    }
}
