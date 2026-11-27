package com.example.bilidl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BiliDurlParserTest {
    @Test
    fun sortsSegmentsAndKeepsBackupUrlsAsAlternatives() {
        val result = BiliDurlParser.normalize(
            listOf(
                BiliDirectSegment(
                    urls = listOf("https://cdn.example/part2", "https://backup.example/part2"),
                    lengthMs = 2_000L,
                    sizeBytes = 200L,
                    order = 2
                ),
                BiliDirectSegment(
                    urls = listOf("https://cdn.example/part1", "https://cdn.example/part1"),
                    lengthMs = 1_000L,
                    sizeBytes = 100L,
                    order = 1
                )
            )
        )

        assertEquals(2, result.size)
        assertEquals(1, result[0].order)
        assertEquals(listOf("https://cdn.example/part1"), result[0].urls)
        assertEquals(2, result[1].order)
        assertEquals(
            listOf("https://cdn.example/part2", "https://backup.example/part2"),
            result[1].urls
        )
    }

    @Test
    fun ignoresSegmentsWithoutValidHttpCandidates() {
        val result = BiliDurlParser.normalize(
            listOf(
                BiliDirectSegment(listOf(""), 10L, 10L, 1),
                BiliDirectSegment(listOf("file:///tmp/part"), 10L, 10L, 2),
                BiliDirectSegment(listOf("https://cdn.example/ok"), -1L, -1L, 3)
            )
        )

        assertEquals(1, result.size)
        assertEquals(0L, result.single().lengthMs)
        assertEquals(0L, result.single().sizeBytes)
        assertTrue(result.single().urls.single().startsWith("https://"))
    }
}
