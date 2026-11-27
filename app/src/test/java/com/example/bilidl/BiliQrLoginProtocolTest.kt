package com.example.bilidl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BiliQrLoginProtocolTest {
    @Test
    fun mapsBilibiliQrStates() {
        assertEquals(BiliQrLoginState.WAITING, BiliQrLoginProtocol.stateFor(86101))
        assertEquals(BiliQrLoginState.SCANNED, BiliQrLoginProtocol.stateFor(86090))
        assertEquals(BiliQrLoginState.EXPIRED, BiliQrLoginProtocol.stateFor(86038))
        assertEquals(BiliQrLoginState.SUCCESS, BiliQrLoginProtocol.stateFor(0))
    }

    @Test
    fun extractsSessdataFromSetCookieWithoutDecoding() {
        val value = BiliQrLoginProtocol.extractSessdata(
            listOf("DedeUserID=123; Path=/", "SESSDATA=abc%2Cdef%2Aghi; Path=/; Secure")
        )
        assertEquals("abc%2Cdef%2Aghi", value)
    }

    @Test
    fun extractsLegacySessdataFromCallbackUrl() {
        assertEquals(
            "abc%2Cdef",
            BiliQrLoginProtocol.extractSessdata(emptyList(), "https://passport.example/login?SESSDATA=abc%2Cdef")
        )
        assertNull(BiliQrLoginProtocol.extractSessdata(emptyList()))
    }
}
