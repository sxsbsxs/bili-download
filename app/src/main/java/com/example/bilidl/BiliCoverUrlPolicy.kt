package com.example.bilidl

internal object BiliCoverUrlPolicy {
    fun normalize(value: String?): String? {
        val raw = value?.trim().orEmpty()
        if (raw.isBlank()) return null
        return when {
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("http://", ignoreCase = true) -> "https://" + raw.substringAfter("://")
            raw.startsWith("https://", ignoreCase = true) -> raw
            raw.startsWith("/") -> "https://www.bilibili.com$raw"
            else -> null
        }
    }
}
