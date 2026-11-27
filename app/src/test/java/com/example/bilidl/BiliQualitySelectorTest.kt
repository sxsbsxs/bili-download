package com.example.bilidl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BiliQualitySelectorTest {
    @Test
    fun choosesHighestStreamAtOrBelowRequestedQuality() {
        val index = BiliQualitySelector.chooseIndex(
            qualityIds = listOf(80, 64, 32, 16),
            codecs = listOf("avc1", "avc1", "avc1", "avc1"),
            requestedQuality = 64
        )
        assertEquals(1, index)
    }

    @Test
    fun refusesToSilentlyUpgradeWhenRequestedQualityIsUnavailable() {
        val index = BiliQualitySelector.chooseIndex(
            qualityIds = listOf(64, 32, 16),
            codecs = listOf("avc1", "avc1", "avc1"),
            requestedQuality = 80
        )
        assertEquals(0, index)
    }

    @Test
    fun returnsNullWhenOnlyHigherQualityStreamsAreReturned() {
        val index = BiliQualitySelector.chooseIndex(
            qualityIds = listOf(112, 120),
            codecs = listOf("avc1", "avc1"),
            requestedQuality = 80
        )
        assertNull(index)
    }

    @Test
    fun autoSelectsHighestAvailableQuality() {
        val index = BiliQualitySelector.chooseIndex(
            qualityIds = listOf(32, 80, 64),
            codecs = listOf("avc1", "avc1", "avc1"),
            requestedQuality = 0
        )
        assertEquals(1, index)
    }

    @Test
    fun prefersAvcWhenSameQualityHasMultipleCodecs() {
        val index = BiliQualitySelector.chooseIndex(
            qualityIds = listOf(80, 80),
            codecs = listOf("hev1.1.6.L120", "avc1.640028"),
            requestedQuality = 80
        )
        assertEquals(1, index)
    }

    @Test
    fun rejectsMismatchedCandidateArrays() {
        assertNull(
            BiliQualitySelector.chooseIndex(
                qualityIds = listOf(80, 64),
                codecs = listOf("avc1"),
                requestedQuality = 80
            )
        )
    }
}
