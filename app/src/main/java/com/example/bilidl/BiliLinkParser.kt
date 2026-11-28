package com.example.bilidl

/**
 * Extracts Bilibili targets from a pasted URL or the text of a share message.
 * Short links are intentionally returned unchanged; BiliRepository follows their redirects.
 */
object BiliLinkParser {
    private val acceptedUrl = Regex(
        """https?://(?:(?:www|m)\.)?(?:bilibili\.com|b23\.tv)/[^\s<>"']+""",
        RegexOption.IGNORE_CASE
    )
    private val anyUrl = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)
    private val bvidPattern = Regex("""\bBV[0-9A-Za-z]{10}\b""", RegexOption.IGNORE_CASE)
    private val avidPattern = Regex("""\bav\d+\b""", RegexOption.IGNORE_CASE)
    private val epPattern = Regex("""\bep\d+\b""", RegexOption.IGNORE_CASE)
    private val ssPattern = Regex("""\bss\d+\b""", RegexOption.IGNORE_CASE)

    fun normalize(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty()) return null

        val url = acceptedUrl.find(text)?.value
            ?.trimEnd('.', ',', ';', '，', '。', ')', '）', ']', '】')
        if (!url.isNullOrBlank()) return url
        // Do not salvage an embedded BV token from an unrelated website URL.
        if (anyUrl.containsMatchIn(text)) return null

        bvidPattern.find(text)?.value?.let {
            return "https://www.bilibili.com/video/" + it
        }
        avidPattern.find(text)?.value?.let {
            return "https://www.bilibili.com/video/" + it
        }
        epPattern.find(text)?.value?.let {
            return "https://www.bilibili.com/bangumi/play/" + it
        }
        ssPattern.find(text)?.value?.let {
            return "https://www.bilibili.com/bangumi/play/" + it
        }
        return null
    }

    /** Extract Bilibili links or IDs from pasted text, preserving order. */
    fun extractAll(raw: String): List<String> {
        val text = raw.trim()
        if (text.isEmpty()) return emptyList()

        val urls = acceptedUrl.findAll(text)
            .map { it.value.trimEnd('.', ',', ';', '，', '。', ')', '）', ']', '】') }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
        if (urls.isNotEmpty()) return urls
        if (anyUrl.containsMatchIn(text)) return emptyList()

        val tokenPattern = Regex(
            """\b(BV[0-9A-Za-z]{10}|av\d+|ep\d+|ss\d+)\b""",
            RegexOption.IGNORE_CASE
        )
        return tokenPattern.findAll(text).map { match ->
            val token = match.value
            when {
                token.startsWith("BV", ignoreCase = true) ||
                    token.startsWith("av", ignoreCase = true) ->
                    "https://www.bilibili.com/video/" + token
                token.startsWith("ep", ignoreCase = true) ->
                    "https://www.bilibili.com/bangumi/play/" + token
                else -> "https://www.bilibili.com/bangumi/play/" + token
            }
        }.distinct().toList()
    }
}
