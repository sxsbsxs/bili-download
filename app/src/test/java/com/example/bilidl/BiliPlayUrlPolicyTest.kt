package com.example.bilidl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BiliPlayUrlPolicyTest {
    @Test
    fun publicPlayUrlUsesBilimiaoCompatibleParameters() {
        val params = BiliPlayUrlPolicy.publicPlayUrlParameters(
            aid = 123456L,
            cid = 654321L,
            quality = 80
        )

        assertEquals("123456", params["avid"])
        assertEquals("654321", params["cid"])
        assertEquals("80", params["qn"])
        assertEquals("4048", params["fnval"])
        assertEquals("0", params["fnver"])
        assertEquals("2", params["force_host"])
        assertEquals("", params["type"])
        assertEquals("json", params["otype"])
        assertEquals("1", params["fourk"])
    }

    @Test
    fun anonymousWbiPlaybackRequestsIncludeTryLook() {
        val params = mutableMapOf("bvid" to "BV1xx", "qn" to "80")

        BiliPlayUrlPolicy.applyTryLook(params, hasSessionCookie = false)

        assertEquals("1", params["try_look"])
        assertEquals("80", params["qn"])
    }

    @Test
    fun authenticatedWbiPlaybackDoesNotIncludeTryLook() {
        val params = mutableMapOf(
            "bvid" to "BV1xx",
            "qn" to "80",
            "try_look" to "1"
        )

        BiliPlayUrlPolicy.applyTryLook(params, hasSessionCookie = true)

        assertFalse(params.containsKey("try_look"))
    }
}
