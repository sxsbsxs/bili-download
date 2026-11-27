package com.example.bilidl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BiliPlayUrlRequestPolicyTest {
    @Test
    fun anonymousPlayurlRequestsIncludeTryLookBeforeWbiSigning() {
        val parameters = BiliPlayUrlRequestPolicy.createParameters(
            bvid = "BV1xx411c7mD",
            aid = 1L,
            cid = 100L,
            quality = 80,
            hasSessionCookie = false
        )

        assertEquals("1", parameters["try_look"])
        assertEquals("80", parameters["qn"])
        assertEquals("4048", parameters["fnval"])
        assertEquals("BV1xx411c7mD", parameters["bvid"])
        assertFalse(parameters.containsKey("avid"))
    }

    @Test
    fun authenticatedPlayurlRequestsDoNotUseAnonymousPreviewFlag() {
        val parameters = BiliPlayUrlRequestPolicy.createParameters(
            bvid = null,
            aid = 123L,
            cid = 456L,
            quality = 80,
            hasSessionCookie = true
        )

        assertFalse(parameters.containsKey("try_look"))
        assertEquals("123", parameters["avid"])
        assertEquals("80", parameters["qn"])
    }

    @Test
    fun anonymousAndAuthenticatedRequestsKeepSameDashNegotiation() {
        val anonymous = BiliPlayUrlRequestPolicy.createParameters(
            "BV1xx411c7mD", 1L, 100L, 64, false
        )
        val authenticated = BiliPlayUrlRequestPolicy.createParameters(
            "BV1xx411c7mD", 1L, 100L, 64, true
        )

        assertEquals("4048", anonymous["fnval"])
        assertEquals("4048", authenticated["fnval"])
        assertEquals("1", anonymous["fourk"])
        assertEquals("1", authenticated["fourk"])
        assertTrue(anonymous.containsKey("try_look"))
    }
}
