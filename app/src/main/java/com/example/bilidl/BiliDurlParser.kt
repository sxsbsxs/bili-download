package com.example.bilidl

import org.json.JSONArray

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
    fun parse(durl: JSONArray): List<BiliDirectSegment> {
        return normalize(
            (0 until durl.length()).mapNotNull { index ->
                val item = durl.optJSONObject(index) ?: return@mapNotNull null
                val urls = ArrayList<String>()
                item.optString("url").takeIf(String::isNotBlank)?.let(urls::add)
                val backups = item.optJSONArray("backup_url")
                    ?: item.optJSONArray("backupUrl")
                for (backupIndex in 0 until (backups?.length() ?: 0)) {
                    backups?.optString(backupIndex)
                        ?.takeIf(String::isNotBlank)
                        ?.let(urls::add)
                }
                if (urls.isEmpty()) return@mapNotNull null
                val apiOrder = item.optInt("order", index + 1).takeIf { it > 0 } ?: index + 1
                BiliDirectSegment(
                    urls = urls,
                    lengthMs = item.optLong("length").coerceAtLeast(0L),
                    sizeBytes = item.optLong("size").coerceAtLeast(0L),
                    order = apiOrder
                )
            }
        )
    }

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
            .sortedBy { it.order } // Kotlin's stable sort preserves API array order for equal/missing order values.
    }
}

/** Prevents a legacy direct response from silently exceeding the user's choice. */
internal object BiliDirectQualityPolicy {
    fun canUse(requestedQuality: Int, returnedQuality: Int): Boolean =
        requestedQuality <= 0 || returnedQuality <= 0 || returnedQuality <= requestedQuality
}
