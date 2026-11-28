package com.example.bilidl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadTransportPolicyTest {
    @Test
    fun preservesFullCookieHeaderWithoutDoublePrefixingSessdata() {
        assertEquals(
            "SESSDATA=session; bili_jct=csrf; DedeUserID=42",
            DownloadTransportPolicy.cookieHeader(
                "SESSDATA=session; bili_jct=csrf; DedeUserID=42"
            )
        )
    }

    @Test
    fun normalizesMultilineCookiePasteFromMobileBrowsers() {
        assertEquals(
            "SESSDATA=session; bili_jct=csrf; DedeUserID=42",
            DownloadTransportPolicy.cookieHeader("SESSDATA=session\r\nbili_jct=csrf\r\nDedeUserID=42")
        )
    }

    @Test
    fun supportsLegacySessdataOnlyValue() {
        assertEquals(
            "SESSDATA=legacy-session-value",
            DownloadTransportPolicy.cookieHeader("legacy-session-value")
        )
    }

    @Test
    fun ignoresUnknownCookieNamesAndEmptyValues() {
        assertEquals(
            "SESSDATA=session",
            DownloadTransportPolicy.cookieHeader("unknown=ignored; SESSDATA=session; bili_jct=")
        )
        assertEquals(null, DownloadTransportPolicy.cookieHeader(null))
        assertEquals(null, DownloadTransportPolicy.cookieHeader("unknown=value; another=value"))
    }

    @Test
    fun onlyStrongEtagOrLastModifiedCanProtectParallelRanges() {
        assertTrue(DownloadTransportPolicy.supportsVerifiedRanges("\"stable\"", ""))
        assertTrue(
            DownloadTransportPolicy.supportsVerifiedRanges(
                "W/\"weak\"",
                "Wed, 26 Nov 2026 10:00:00 GMT"
            )
        )
        assertFalse(DownloadTransportPolicy.supportsVerifiedRanges("W/\"weak\"", ""))
        assertFalse(DownloadTransportPolicy.supportsVerifiedRanges("", ""))
    }

    @Test
    fun rejectsRangeResponseFromDifferentRepresentation() {
        assertFalse(
            DownloadTransportPolicy.responseMatchesValidator(
                "\"version-a\"",
                "",
                "\"version-b\"",
                ""
            )
        )
        assertFalse(
            DownloadTransportPolicy.responseMatchesValidator(
                "",
                "Wed, 26 Nov 2026 10:00:00 GMT",
                "",
                "Thu, 27 Nov 2026 10:00:00 GMT"
            )
        )
        assertTrue(
            DownloadTransportPolicy.responseMatchesValidator(
                "\"version-a\"",
                "",
                "\"version-a\"",
                ""
            )
        )
    }

    @Test
    fun calculatesOnlyMissingBytesAndRequiresReasonableFreeSpace() {
        assertEquals(
            6L,
            DownloadTransportPolicy.additionalBytesRequired(
                totalBytes = 10L,
                chunkSize = 4L,
                completed = booleanArrayOf(true, false, false)
            )
        )
        assertTrue(DownloadTransportPolicy.hasEnoughSpace(10L, 10L))
        assertFalse(DownloadTransportPolicy.hasEnoughSpace(9L, 10L))
    }
}
