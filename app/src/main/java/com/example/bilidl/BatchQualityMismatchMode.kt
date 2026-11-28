package com.example.bilidl

/**
 * How a batch download handles links that do not expose the requested quality.
 * Integer values are persisted in SharedPreferences and must remain stable.
 */
enum class BatchQualityMismatchMode(val code: Int) {
    AUTO_FALLBACK(0),
    ASK(1),
    SKIP(2);

    companion object {
        fun fromCode(code: Int): BatchQualityMismatchMode =
            values().firstOrNull { it.code == code } ?: AUTO_FALLBACK
    }
}

data class BatchQualityMismatchAssessment(
    val mismatched: Boolean,
    val shouldAsk: Boolean,
    val shouldSkip: Boolean
)

/** Pure policy shared by batch parsing and tests. */
object BatchQualityMismatchPolicy {
    fun assess(
        mode: BatchQualityMismatchMode,
        requestedQualityId: Int,
        availableQualityIds: Collection<Int>
    ): BatchQualityMismatchAssessment {
        val mismatched = requestedQualityId > 0 &&
            availableQualityIds.isNotEmpty() &&
            requestedQualityId !in availableQualityIds
        if (!mismatched) {
            return BatchQualityMismatchAssessment(false, false, false)
        }
        return when (mode) {
            BatchQualityMismatchMode.AUTO_FALLBACK ->
                BatchQualityMismatchAssessment(true, false, false)
            BatchQualityMismatchMode.ASK ->
                BatchQualityMismatchAssessment(true, true, false)
            BatchQualityMismatchMode.SKIP ->
                BatchQualityMismatchAssessment(true, false, true)
        }
    }
}
