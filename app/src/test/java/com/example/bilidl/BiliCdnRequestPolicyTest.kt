package com.example.bilidl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BiliCdnRequestPolicyTest {
    @Test
    fun requestProfileMatchesBvdBrowserDownloadHeaders() {
        assertEquals("https://m.bilibili.com", BiliCdnRequestPolicy.MOBILE_ORIGIN)
        assertEquals("https://m.bilibili.com/", BiliCdnRequestPolicy.MOBILE_REFERER)
        assertTrue(BiliCdnRequestPolicy.USER_AGENT.contains("Edg/124.0.0.0"))
    }
}
