package com.example.bilidl

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadSourceRankingPolicyTest {
    @Test
    fun ranksSuccessfulSamplesByThroughputAndKeepsFailedUrlsAsFallbacks() {
        val urls = listOf("slow", "blocked", "fast", "medium")
        val ranked = DownloadSourceRankingPolicy.rank(
            originalUrls = urls,
            samples = listOf(
                DownloadSourceSample("slow", 64 * 1024, 2_000_000_000L),
                DownloadSourceSample("blocked", 0, 300_000_000L),
                DownloadSourceSample("fast", 64 * 1024, 400_000_000L),
                DownloadSourceSample("medium", 64 * 1024, 1_000_000_000L)
            )
        )
        assertEquals(listOf("fast", "medium", "slow", "blocked"), ranked)
    }

    @Test
    fun duplicateAndForeignProbeResultsAreIgnored() {
        val ranked = DownloadSourceRankingPolicy.rank(
            originalUrls = listOf("primary", "backup"),
            samples = listOf(
                DownloadSourceSample("outside", 100_000, 1L),
                DownloadSourceSample("backup", 10_000, 100L),
                DownloadSourceSample("backup", 0, 1L)
            )
        )
        assertEquals(listOf("backup", "primary"), ranked)
    }
}
