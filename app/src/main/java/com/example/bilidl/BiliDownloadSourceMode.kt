package com.example.bilidl

/** Mirrors BVD's three media-source strategies. */
enum class BiliDownloadSourceMode(val code: Int) {
    DEFAULT(0),
    AUTO_PROBE(1),
    CUSTOM_HOST(2);

    companion object {
        fun fromCode(code: Int): BiliDownloadSourceMode =
            entries.firstOrNull { it.code == code } ?: DEFAULT
    }
}
