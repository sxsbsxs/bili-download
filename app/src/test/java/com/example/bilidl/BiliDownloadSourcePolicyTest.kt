package com.example.bilidl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BiliDownloadSourcePolicyTest {
    @Test
    fun customHostRewritePreservesSignedPathAndQuery() {
        val source = "https://upos-sz-mirrorcosb.bilivideo.com/upgcxcode/abc/video.m4s?deadline=123&token=a%2Fb"
        val rewritten = BiliDownloadSourcePolicy.rewriteHost(
            source,
            "upos-sz-mirrorali.bilivideo.com"
        )

        assertEquals(
            "https://upos-sz-mirrorali.bilivideo.com/upgcxcode/abc/video.m4s?deadline=123&token=a%2Fb",
            rewritten
        )
    }

    @Test
    fun rejectsFullUrlOrPathAsCustomHost() {
        assertNull(BiliDownloadSourcePolicy.normalizeHost("https://example.com/path"))
        assertNull(BiliDownloadSourcePolicy.normalizeHost("example.com/path"))
        assertNull(BiliDownloadSourcePolicy.normalizeHost("user@example.com"))
        assertNull(BiliDownloadSourcePolicy.normalizeHost("example.com"))
        assertNull(BiliDownloadSourcePolicy.normalizeHost("evil.bilivideo.com.attacker.test"))
        assertEquals(
            "upos-sz-mirrorali.bilivideo.com",
            BiliDownloadSourcePolicy.normalizeHost("UPOS-SZ-MIRRORALI.BILIVIDEO.COM")
        )
    }

    @Test
    fun customHostCandidatesAreUniqueAndRetainEachOriginalPath() {
        val originals = listOf(
            "https://one.example/a?x=1",
            "https://two.example/b?y=2",
            "https://one.example/a?x=1"
        )
        assertEquals(
            listOf("https://upos-sz-mirrorali.bilivideo.com/a?x=1", "https://upos-sz-mirrorali.bilivideo.com/b?y=2"),
            BiliDownloadSourcePolicy.customHostCandidates(originals, "upos-sz-mirrorali.bilivideo.com")
        )
    }

    @Test
    fun presetListContainsBvdMirrorChoices() {
        assertTrue(BiliDownloadSourcePreset.entries.any { it.host == "upos-hz-mirrorakam.akamaized.net" })
        assertTrue(BiliDownloadSourcePreset.entries.any { it.host == "upos-sz-mirroraliov.bilivideo.com" })
    }
}
