package com.example.bilidl

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadStoragePolicyTest {
    @Test
    fun reservesEightMibForSmallOutputs() {
        assertEquals(8L * 1024L * 1024L, DownloadStoragePolicy.requiredFreeBytes(0L))
        assertEquals(
            10L * 1024L * 1024L,
            DownloadStoragePolicy.requiredFreeBytes(2L * 1024L * 1024L)
        )
    }

    @Test
    fun reservesFivePercentForLargeOutputs() {
        val sourceBytes = 200L * 1024L * 1024L
        assertEquals(210L * 1024L * 1024L, DownloadStoragePolicy.requiredFreeBytes(sourceBytes))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNegativeSourceSize() {
        DownloadStoragePolicy.requiredFreeBytes(-1L)
    }
}
