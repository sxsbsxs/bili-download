package com.example.bilidl

/**
 * Keeps selection behavior consistent across search and account content lists.
 * Returns null only when adding a new item would exceed the batch cap.
 */
internal object BatchSelectionPolicy {
    const val MAX_ITEMS = 50

    fun toggle(current: List<String>, rawUrl: String): List<String>? {
        val url = rawUrl.trim()
        if (url.isEmpty()) return current
        if (url in current) return current.filterNot { it == url }
        if (current.size >= MAX_ITEMS) return null
        return current + url
    }

    fun normalize(urls: Collection<String>): List<String> =
        urls.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(MAX_ITEMS)
}
