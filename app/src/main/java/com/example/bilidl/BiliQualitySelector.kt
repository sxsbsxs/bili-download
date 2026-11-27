package com.example.bilidl

/**
 * Deterministic, testable BiliBili DASH quality selection.
 * Returns an index into the original candidate list, or null when no
 * candidate is at or below the user's requested quality.
 */
internal object BiliQualitySelector {
    fun chooseIndex(
        qualityIds: List<Int>,
        codecs: List<String>,
        requestedQuality: Int
    ): Int? {
        if (qualityIds.isEmpty() || qualityIds.size != codecs.size) return null

        return qualityIds.indices
            .filter { index -> requestedQuality <= 0 || qualityIds[index] <= requestedQuality }
            .maxWithOrNull(
                compareBy<Int> { qualityIds[it] }
                    .thenBy { codecs[it].contains("avc", ignoreCase = true) }
            )
    }
}

/**
 * Resume checkpoints are only safe when the server supplies a stable
 * representation validator. Without ETag or Last-Modified we restart cleanly.
 */
internal object TransferCheckpointPolicy {
    fun canReuse(
        etag: String,
        lastModified: String,
        targetMatches: Boolean,
        metadataMatches: Boolean,
        mapMatches: Boolean
    ): Boolean {
        val hasStableValidator = etag.isNotBlank() || lastModified.isNotBlank()
        return hasStableValidator && targetMatches && metadataMatches && mapMatches
    }
}
