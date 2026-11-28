package com.example.bilidl

import java.net.URI

/**
 * BVD-style mobile Passport login handoff.
 *
 * Bilibili's H5 login returns to m.bilibili.com after authorization. At that
 * point WebView cookies are collected and passed back to the app for server
 * validation before they are encrypted locally.
 */
internal object BiliWebLoginPolicy {
    const val LOGIN_URL = "https://passport.bilibili.com/h5-app/passport/login"
    const val CALLBACK_HOST = "m.bilibili.com"

    fun isCallbackUrl(url: String): Boolean =
        runCatching { URI(url).host.equals(CALLBACK_HOST, ignoreCase = true) }
            .getOrDefault(false)

    /**
     * Merge WebView cookie strings while dropping Set-Cookie attributes and
     * duplicate names. A login handoff is valid only when SESSDATA is present.
     */
    fun mergeCookieHeaders(headers: Iterable<String?>): String? {
        val cookies = linkedMapOf<String, String>()
        val ignoredAttributes = setOf(
            "path", "domain", "expires", "max-age", "secure",
            "httponly", "samesite", "priority"
        )
        headers.filterNotNull().forEach { header ->
            header.split(';').forEach { token ->
                val separator = token.indexOf('=')
                if (separator <= 0) return@forEach
                val name = token.substring(0, separator).trim()
                val value = token.substring(separator + 1).trim()
                if (name.isBlank() ||
                    name.equalsAny(ignoredAttributes) ||
                    !name.matches(Regex("[A-Za-z0-9_]+")) ||
                    value.any { it == '\\r' || it == '\\n' }
                ) return@forEach
                cookies[name] = value
            }
        }
        if (cookies["SESSDATA"].isNullOrBlank()) return null
        return cookies.entries.joinToString("; ") { (name, value) -> "$name=$value" }
    }

    private fun String.equalsAny(values: Set<String>): Boolean =
        values.any { equals(it, ignoreCase = true) }
}
