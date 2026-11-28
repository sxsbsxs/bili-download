package com.example.bilidl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BiliDownloadFileNamePolicyTest {
    @Test
    fun replacesVideoAndPartTokensAndSanitizesIllegalCharacters() {
        val name = BiliDownloadFileNamePolicy.render(
            "{VideoName}-{PartName}",
            BiliDownloadFileNamePolicy.DEFAULT_MIXED_TEMPLATE,
            "Video/Name:*?",
            "第二集|特别篇"
        )
        assertEquals("Video_Name-第二集_特别篇", name)
    }

    @Test
    fun blankTemplateUsesTheKindSpecificDefault() {
        val name = BiliDownloadFileNamePolicy.render(
            " ",
            BiliDownloadFileNamePolicy.DEFAULT_AUDIO_TEMPLATE,
            "Sample",
            "P2"
        )
        assertEquals("Audio-Sample-P2", name)
    }

    @Test
    fun blankPartNameDoesNotLeaveTrailingSeparators() {
        val name = BiliDownloadFileNamePolicy.render(
            "{VideoName}-{PartName}",
            BiliDownloadFileNamePolicy.DEFAULT_MIXED_TEMPLATE,
            "Sample",
            ""
        )
        assertEquals("Sample", name)
    }

    @Test
    fun truncatesByUtf8BytesWithoutSplittingCodePoints() {
        val name = BiliDownloadFileNamePolicy.sanitizeBaseName("测".repeat(120), 180)
        assertTrue(name.toByteArray(Charsets.UTF_8).size <= 180)
        assertTrue(name.all { it == '测' })
    }
}
