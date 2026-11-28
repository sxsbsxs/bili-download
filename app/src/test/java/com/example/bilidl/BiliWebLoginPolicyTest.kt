package com.example.bilidl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BiliWebLoginPolicyTest {

    @Test
    fun usesBvdMobilePassportLoginUrl() {
        assertEquals(
            "https://passport.bilibili.com/h5-app/passport/login",
            BiliWebLoginPolicy.LOGIN_URL
        )
    }

    @Test
    fun capturesOnlyTheBilibiliMobileCallback() {
        assertTrue(BiliWebLoginPolicy.isCallbackUrl("https://m.bilibili.com/"))
        assertFalse(BiliWebLoginPolicy.isCallbackUrl("https://passport.bilibili.com/"))
        assertFalse(BiliWebLoginPolicy.isCallbackUrl("https://example.com/"))
    }

    @Test
    fun mergesFullCookieHeaderAndDropsSetCookieAttributes() {
        assertEquals(
            "SESSDATA=abc%2Cdef; bili_jct=csrf; DedeUserID=123",
            BiliWebLoginPolicy.mergeCookieHeaders(
                listOf(
                    "SESSDATA=abc%2Cdef; Path=/; Secure",
                    "bili_jct=csrf; HttpOnly",
                    "DedeUserID=123; Domain=.bilibili.com"
                )
            )
        )
    }

    @Test
    fun requiresSessdataForLoginHandoff() {
        assertNull(BiliWebLoginPolicy.mergeCookieHeaders(listOf("bili_jct=csrf; DedeUserID=123")))
        assertNull(BiliWebLoginPolicy.mergeCookieHeaders(listOf(null, "")))
    }

    @Test
    fun laterCookieForSameNameReplacesEarlierValue() {
        assertEquals(
            "SESSDATA=new",
            BiliWebLoginPolicy.mergeCookieHeaders(
                listOf("SESSDATA=old", "SESSDATA=new")
            )
        )
    }
}
