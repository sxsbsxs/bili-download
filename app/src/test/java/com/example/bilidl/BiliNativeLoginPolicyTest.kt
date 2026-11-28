package com.example.bilidl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

class BiliNativeLoginPolicyTest {
    @Test
    fun targetsTheOfficialMainlandBilibiliPackage() {
        assertEquals("tv.danmaku.bili", BiliNativeLoginPolicy.PACKAGE_NAME)
    }

    @Test
    fun usesTheNativeLoginRouteNotAWebUrl() {
        val route = URI(BiliNativeLoginPolicy.LOGIN_URI)
        assertEquals("bilibili", route.scheme)
        assertEquals("login", route.host)
        assertTrue(route.path.isNullOrEmpty())
        assertTrue(route.query.isNullOrEmpty())
    }
}
