package com.example.bilidl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BiliWebLoginPolicyTest {
    @Test
    fun onlyAcceptsBilibiliMainSiteCallbackHosts() {
        assertTrue(BiliWebLoginPolicy.shouldCaptureCookie("m.bilibili.com"))
        assertTrue(BiliWebLoginPolicy.shouldCaptureCookie("www.bilibili.com"))
        assertTrue(BiliWebLoginPolicy.shouldCaptureCookie("bilibili.com"))
        assertFalse(BiliWebLoginPolicy.shouldCaptureCookie("passport.bilibili.com"))
        assertFalse(BiliWebLoginPolicy.shouldCaptureCookie("evil.m.bilibili.com"))
        assertFalse(BiliWebLoginPolicy.shouldCaptureCookie("bilibili.com.attacker.test"))
        assertFalse(BiliWebLoginPolicy.shouldCaptureCookie(null))
    }

    @Test
    fun extractsOnlySessdataFromWebViewCookieHeaderWithoutDecoding() {
        assertEquals(
            "abc%2Cdef%2Aghi",
            BiliWebLoginPolicy.extractSessdata(
                listOf("DedeUserID=123; SESSDATA=abc%2Cdef%2Aghi; bili_jct=csrf")
            )
        )
        assertNull(BiliWebLoginPolicy.extractSessdata(listOf("DedeUserID=123; bili_jct=csrf")))
    }

    @Test
    fun usesOfficialBilibiliPassportLoginPage() {
        assertEquals(
            "https://passport.bilibili.com/h5-app/passport/login",
            BiliWebLoginPolicy.LOGIN_URL
        )
    }
}
