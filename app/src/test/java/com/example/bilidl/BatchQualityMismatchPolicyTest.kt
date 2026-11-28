package com.example.bilidl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchQualityMismatchPolicyTest {
    private val available = listOf(64, 32, 16)

    @Test
    fun exactQualityNeverCountsAsMismatch() {
        val result = BatchQualityMismatchPolicy.assess(
            BatchQualityMismatchMode.ASK,
            requestedQualityId = 64,
            availableQualityIds = available
        )
        assertFalse(result.mismatched)
        assertFalse(result.shouldAsk)
        assertFalse(result.shouldSkip)
    }

    @Test
    fun automaticModeUsesExistingFallbackWithoutPrompt() {
        val result = BatchQualityMismatchPolicy.assess(
            BatchQualityMismatchMode.AUTO_FALLBACK,
            requestedQualityId = 80,
            availableQualityIds = available
        )
        assertTrue(result.mismatched)
        assertFalse(result.shouldAsk)
        assertFalse(result.shouldSkip)
    }

    @Test
    fun askModeRequestsUserDecision() {
        val result = BatchQualityMismatchPolicy.assess(
            BatchQualityMismatchMode.ASK,
            requestedQualityId = 80,
            availableQualityIds = available
        )
        assertTrue(result.mismatched)
        assertTrue(result.shouldAsk)
        assertFalse(result.shouldSkip)
    }

    @Test
    fun skipModeSkipsOnlyWhenKnownQualitiesDoNotMatch() {
        val mismatch = BatchQualityMismatchPolicy.assess(
            BatchQualityMismatchMode.SKIP,
            requestedQualityId = 80,
            availableQualityIds = available
        )
        assertTrue(mismatch.shouldSkip)

        val unknown = BatchQualityMismatchPolicy.assess(
            BatchQualityMismatchMode.SKIP,
            requestedQualityId = 80,
            availableQualityIds = emptyList()
        )
        assertFalse(unknown.mismatched)
        assertFalse(unknown.shouldSkip)
    }

    @Test
    fun unknownPersistedModeFallsBackToAutomaticMode() {
        assertEquals(
            BatchQualityMismatchMode.AUTO_FALLBACK,
            BatchQualityMismatchMode.fromCode(Int.MAX_VALUE)
        )
    }
}
