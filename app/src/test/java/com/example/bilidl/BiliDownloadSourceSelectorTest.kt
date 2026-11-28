package com.example.bilidl

import org.junit.Assert.assertEquals
import org.junit.Test

class BiliDownloadSourceSelectorTest {
    @Test
    fun ordersReadableCandidatesByMeasuredThroughputAndKeepsRejectedFallback() {
        val urls = listOf("cdn-slow", "cdn-rejected", "cdn-fast")
        val ordered = BiliDownloadSourceSelector.orderBySampleRates(
            urls = urls,
            sampleRates = mapOf(
                "cdn-slow" to 0.25,
                "cdn-fast" to 0.90
            )
        )

        assertEquals(
            listOf("cdn-fast", "cdn-slow", "cdn-rejected"),
            ordered
        )
    }

    @Test
    fun customHostFallbacksRemainAfterSuccessfullyProbedHosts() {
        val custom = listOf("custom-a", "custom-b")
        val original = listOf("original-a", "original-b")
        val ordered = BiliDownloadSourceSelector.orderBySampleRates(
            urls = custom,
            sampleRates = mapOf("custom-b" to 0.5),
            fallback = original
        )

        assertEquals(
            listOf("custom-b", "original-a", "original-b", "custom-a"),
            ordered
        )
    }

    @Test
    fun equalThroughputRetainsInputOrder() {
        val ordered = BiliDownloadSourceSelector.orderBySampleRates(
            urls = listOf("first", "second", "third"),
            sampleRates = mapOf("first" to 0.5, "second" to 0.5)
        )
        assertEquals(listOf("first", "second", "third"), ordered)
    }

    @Test
    fun ignoresZeroNegativeAndNonFiniteSampleRates() {
        val ordered = BiliDownloadSourceSelector.orderBySampleRates(
            urls = listOf("rejected", "zero", "negative", "nan"),
            sampleRates = mapOf(
                "zero" to 0.0,
                "negative" to -1.0,
                "nan" to Double.NaN
            )
        )
        assertEquals(listOf("rejected", "zero", "negative", "nan"), ordered)
    }
}
