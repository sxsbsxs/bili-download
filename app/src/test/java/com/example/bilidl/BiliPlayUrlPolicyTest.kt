package com.example.bilidl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BiliPlayUrlPolicyTest {
    @Test
    fun anonymousPlaybackRequestsIncludeTryLook() {
        val params = mutableMapOf("bvid" to "BV1xx", "qn" to "80")

        BiliPlayUrlPolicy.applyTryLook(params, hasSessionCookie = false)

        assertEquals("1", params["try_look"])
        assertEquals("80", params["qn"])
    }

    @Test
    fun authenticatedPlaybackDoesNotIncludeTryLook() {
        val params = mutableMapOf(
            "bvid" to "BV1xx",
            "qn" to "80",
            "try_look" to "1"
        )

        BiliPlayUrlPolicy.applyTryLook(params, hasSessionCookie = true)

        assertFalse(params.containsKey("try_look"))
    }
}
