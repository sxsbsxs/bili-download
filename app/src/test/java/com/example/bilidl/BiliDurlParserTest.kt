package com.example.bilidl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BiliDurlParserTest {
    @Test
    fun legacyDurlCannotSilentlyExceedRequestedQuality() {
        assertTrue(BiliDirectQualityPolicy.canUse(80, 80))
        assertTrue(BiliDirectQualityPolicy.canUse(80, 64))
        assertTrue(BiliDirectQualityPolicy.canUse(0, 120))
        assertTrue(BiliDirectQualityPolicy.canUse(80, 0))
        org.junit.Assert.assertFalse(BiliDirectQualityPolicy.canUse(64, 80))
    }

    @Test
    fun parsesBilibiliDurlJsonIncludingOrderLengthSizeAndBackupUrls() {
        val durl = org.json.JSONArray().put(
            org.json.JSONObject()
                .put("order", 2)
                .put("length", 2_500)
                .put("size", 4096)
                .put("url", "https://cdn.example/part2")
                .put("backup_url", org.json.JSONArray().put("https://backup.example/part2"))
        ).put(
            org.json.JSONObject()
                .put("order", 1)
                .put("length", 1_500)
                .put("size", 2048)
                .put("url", "https://cdn.example/part1")
        )

        val result = BiliDurlParser.parse(durl)

        assertEquals(listOf(1, 2), result.map { it.order })
        assertEquals(listOf(1_500L, 2_500L), result.map { it.lengthMs })
        assertEquals(listOf(2048L, 4096L), result.map { it.sizeBytes })
        assertEquals(listOf("https://cdn.example/part2", "https://backup.example/part2"), result[1].urls)
    }

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
