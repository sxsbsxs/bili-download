package com.example.bilidl

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BiliQrAppLoginPolicyTest {
    @Test
    fun acceptsBilibiliPassportQrUrlForAndroidAppHandoff() {
        assertTrue(
            BiliQrAppLoginPolicy.isTrustedQrUrl(
                "https://passport.bilibili.com/x/passport-login/web/qrcode/scan?qrcode_key=test"
            )
        )
    }

    @Test
    fun rejectsNonHttpsAndUntrustedHosts() {
        assertFalse(BiliQrAppLoginPolicy.isTrustedQrUrl("http://passport.bilibili.com/login"))
        assertFalse(BiliQrAppLoginPolicy.isTrustedQrUrl("https://example.com/login"))
        assertFalse(BiliQrAppLoginPolicy.isTrustedQrUrl("javascript:alert(1)"))
    }
}
