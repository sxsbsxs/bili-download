package com.example.bilidl

import org.junit.Assert.assertEquals
import org.junit.Test

class BatchQualityMismatchModeTest {
    @Test
    fun storedValuesMapToAllThreeModes() {
        assertEquals(BatchQualityMismatchMode.AUTO_FALLBACK, BatchQualityMismatchMode.fromPreference(0))
        assertEquals(BatchQualityMismatchMode.ASK, BatchQualityMismatchMode.fromPreference(1))
        assertEquals(BatchQualityMismatchMode.SKIP, BatchQualityMismatchMode.fromPreference(2))
    }

    @Test
    fun unknownStoredValuesSafelyDefaultToAutomaticFallback() {
        assertEquals(BatchQualityMismatchMode.AUTO_FALLBACK, BatchQualityMismatchMode.fromPreference(-1))
        assertEquals(BatchQualityMismatchMode.AUTO_FALLBACK, BatchQualityMismatchMode.fromPreference(99))
    }
}
