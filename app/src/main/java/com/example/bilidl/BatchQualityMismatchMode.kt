package com.example.bilidl

/** User-selected BVD batch quality mismatch behavior. Values are persisted in app preferences. */
internal enum class BatchQualityMismatchMode(val preferenceValue: Int) {
    AUTO_FALLBACK(0),
    ASK(1),
    SKIP(2);

    companion object {
        fun fromPreference(value: Int): BatchQualityMismatchMode =
            values().firstOrNull { it.preferenceValue == value } ?: AUTO_FALLBACK
    }
}
