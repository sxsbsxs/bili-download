package com.example.bilidl

/**
 * One old-style Bilibili DURL segment. A segment may expose several CDN URLs;
 * those are alternatives for the same bytes, not additional pieces of the video.
 */
data class BiliDirectSegment(
    val urls: List<String>,
    val lengthMs: Long,
    val sizeBytes: Long,
    val order: Int
)

/** Normalizes DURL segment order and validates the URL candidates. */
internal object BiliDurlParser {
    fun normalize(segments: List<BiliDirectSegment>): List<BiliDirectSegment> {
        return segments
            .mapNotNull { segment ->
                val urls = segment.urls
                    .asSequence()
                    .map { it.trim() }
                    .filter { it.startsWith("https://", ignoreCase = true) ||
                        it.startsWith("http://", ignoreCase = true) }
                    .distinct()
                    .toList()
                if (urls.isEmpty()) null else segment.copy(
                    urls = urls,
                    lengthMs = segment.lengthMs.coerceAtLeast(0L),
                    sizeBytes = segment.sizeBytes.coerceAtLeast(0L)
                )
            }
            .sortedWith(compareBy<BiliDirectSegment> { it.order }.thenBy { it.urls.first() })
    }
}
