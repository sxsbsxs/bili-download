package com.example.bilidl

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeMediaConverterPolicyTest {
    @Test
    fun createsSafeOutputNamesWithoutDuplicatingExtension() {
        assertEquals(
            "A title.mp4",
            NativeMediaConverter.suggestOutputName("A title.mkv", "mp4")
        )
    }

    @Test
    fun sanitizesIllegalFileNameCharacters() {
        assertEquals(
            "A_B_C.m4a",
            NativeMediaConverter.suggestOutputName("A/B:C.mp3", "m4a")
        )
    }

    @Test
    fun fallsBackToDefaultNameForEmptyTitle() {
        assertEquals(
            "BiliDl media.mp4",
            NativeMediaConverter.suggestOutputName(".mkv", "mp4")
        )
    }
}
