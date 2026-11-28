package com.example.bilidl

import org.junit.Assert.assertEquals
import org.junit.Test

class BiliSubmissionQueryPolicyTest {
    @Test
    fun defaultsToLatestAndAllPartitionsWithoutKeyword() {
        assertEquals(
            linkedMapOf(
                "mid" to "12345",
                "order" to "pubdate",
                "pn" to "1",
                "ps" to "20"
            ),
            BiliSubmissionQueryPolicy.buildParameters(
                mid = 12345,
                page = 1,
                order = BiliSubmissionOrder.LATEST,
                tid = 0,
                keyword = "  "
            )
        )
    }

    @Test
    fun addsOrderPartitionKeywordAndRequestedPage() {
        assertEquals(
            linkedMapOf(
                "mid" to "12345",
                "order" to "click",
                "pn" to "3",
                "ps" to "20",
                "tid" to "188",
                "keyword" to "测试视频"
            ),
            BiliSubmissionQueryPolicy.buildParameters(
                mid = 12345,
                page = 3,
                order = BiliSubmissionOrder.MOST_PLAYED,
                tid = 188,
                keyword = "  测试视频  "
            )
        )
    }

    @Test
    fun supportsMostFavoritedOrder() {
        assertEquals(
            "stow",
            BiliSubmissionQueryPolicy.buildParameters(
                mid = 1,
                page = 1,
                order = BiliSubmissionOrder.MOST_FAVORITED,
                tid = 0,
                keyword = ""
            )["order"]
        )
    }
}
