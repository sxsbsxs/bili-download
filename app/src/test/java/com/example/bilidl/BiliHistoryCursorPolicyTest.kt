package com.example.bilidl

import org.junit.Assert.assertEquals
import org.junit.Test

class BiliHistoryCursorPolicyTest {
    @Test
    fun firstHistoryPageUsesArchiveCursorDefaults() {
        assertEquals(
            "max=0&view_at=0&business=&ps=20&type=archive",
            BiliHistoryCursorPolicy.buildQuery(null)
        )
    }

    @Test
    fun nextHistoryPagePreservesAllThreeBvdCursorFields() {
        assertEquals(
            "max=1024&view_at=1712345678&business=archive&ps=20&type=archive",
            BiliHistoryCursorPolicy.buildQuery(
                BiliHistoryCursorState(max = 1024, viewAt = 1712345678, business = "archive")
            )
        )
    }

    @Test
    fun cursorBusinessIsUrlEncoded() {
        assertEquals(
            "max=1&view_at=2&business=article-list&ps=20&type=archive",
            BiliHistoryCursorPolicy.buildQuery(
                BiliHistoryCursorState(max = 1, viewAt = 2, business = "article-list")
            )
        )
    }
}
