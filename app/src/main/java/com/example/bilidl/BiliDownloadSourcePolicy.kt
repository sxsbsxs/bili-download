package com.example.bilidl

import java.net.URI

/**
 * Host-only rewrites deliberately preserve the signed path and query exactly.
 * This follows BVD's custom-host behavior: try the rewritten media URL first,
 * then retain the original Bilibili candidates as fallback.
 */
internal object BiliDownloadSourcePolicy {
    const val PREFERENCES_FILE = "ui"
    const val KEY_MODE = "download_source_mode"
    const val KEY_CUSTOM_HOST = "download_source_custom_host"

    fun customHostCandidates(urls: List<String>, rawHost: String): List<String> {
        val host = normalizeHost(rawHost) ?: return emptyList()
        return urls.mapNotNull { rewriteHost(it, host) }.distinct()
    }

    fun normalizeHost(rawHost: String): String? {
        val value = rawHost.trim().trimEnd('.')
        if (value.isBlank() || value.any { it.isWhitespace() || it in "/?#@" }) return null
        val parsed = runCatching { URI("https://$value") }.getOrNull() ?: return null
        if (parsed.host.isNullOrBlank() || parsed.port != -1 ||
            !parsed.rawPath.isNullOrBlank() || parsed.rawQuery != null || parsed.rawFragment != null ||
            parsed.rawUserInfo != null
        ) return null
        val host = parsed.host.lowercase()
        // The media downloader may attach SESSDATA. Never send it to an arbitrary host.
        val isBilibiliCdn = host.endsWith(".bilivideo.com") ||
            host.endsWith(".akamaized.net")
        if (!isBilibiliCdn) return null
        return host
    }

    fun rewriteHost(url: String, host: String): String? {
        val parsed = runCatching { URI(url) }.getOrNull() ?: return null
        if (parsed.scheme !in setOf("http", "https") || parsed.host.isNullOrBlank()) return null
        val normalizedHost = normalizeHost(host) ?: return null
        val authorityStart = url.indexOf("://").takeIf { it >= 0 }?.plus(3) ?: return null
        val authorityEndRelative = url.substring(authorityStart).indexOfAny(charArrayOf('/', '?', '#'))
        val authorityEnd = if (authorityEndRelative < 0) url.length else authorityStart + authorityEndRelative
        val originalAuthority = url.substring(authorityStart, authorityEnd)
        val userInfo = originalAuthority.substringBeforeLast('@', "").let {
            if (it.isEmpty()) "" else "$it@"
        }
        val port = if (parsed.port >= 0) ":${parsed.port}" else ""
        return url.substring(0, authorityStart) +
            userInfo + normalizedHost + port + url.substring(authorityEnd)
    }
}
