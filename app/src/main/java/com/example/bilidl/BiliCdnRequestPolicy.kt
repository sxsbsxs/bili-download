package com.example.bilidl

import java.net.HttpURLConnection
import java.net.URL

/**
 * One request profile for CDN probing and actual media transfer.
 *
 * It mirrors BVD's browser-style DOWNLOAD_HEADERS and visitor-cookie fallback.
 * Session data is only sent when the user explicitly authorized the app.
 */
internal object BiliCdnRequestPolicy {
    const val MOBILE_ORIGIN = "https://m.bilibili.com"
    const val MOBILE_REFERER = "https://m.bilibili.com/"
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36 Edg/124.0.0.0"

    @Volatile
    private var cachedVisitorCookies: String? = null
    @Volatile
    private var cachedAtMillis: Long = 0L
    private val cookieLock = Any()

    fun configure(
        connection: HttpURLConnection,
        sessionCookie: String?
    ) {
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.setRequestProperty("Accept", "*/*")
        connection.setRequestProperty("Accept-Language", "zh-CN,zh-Hans;q=0.9")
        connection.setRequestProperty("Origin", MOBILE_ORIGIN)
        connection.setRequestProperty("Referer", MOBILE_REFERER)
        // Range validation and byte offsets require the representation bytes.
        connection.setRequestProperty("Accept-Encoding", "identity")
        connection.setRequestProperty("Connection", "keep-alive")

        val cookieHeader = sessionCookie
            ?.takeIf { it.isNotBlank() }
            ?.let { "SESSDATA=" + SecureCookieStore.normalizeCookie(it) }
            ?: visitorCookies()
        if (!cookieHeader.isNullOrBlank()) {
            connection.setRequestProperty("Cookie", cookieHeader)
        }
    }

    /** BVD's interceptor obtains public visitor cookies when no account cookie exists. */
    private fun visitorCookies(): String? {
        val now = System.currentTimeMillis()
        cachedVisitorCookies?.takeIf { now - cachedAtMillis < VISITOR_COOKIE_TTL_MS }?.let {
            return it
        }
        synchronized(cookieLock) {
            val refreshedNow = System.currentTimeMillis()
            cachedVisitorCookies?.takeIf {
                refreshedNow - cachedAtMillis < VISITOR_COOKIE_TTL_MS
            }?.let { return it }

            val connection = runCatching {
                (URL(MOBILE_REFERER).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = VISITOR_COOKIE_TIMEOUT_MS
                    readTimeout = VISITOR_COOKIE_TIMEOUT_MS
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", USER_AGENT)
                    setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    setRequestProperty("Accept-Language", "zh-CN,zh-Hans;q=0.9")
                    setRequestProperty("Referer", MOBILE_REFERER)
                }
            }.getOrNull() ?: return null

            val result = try {
                connection.responseCode
                connection.headerFields.entries
                    .firstOrNull { it.key.equals("Set-Cookie", ignoreCase = true) }
                    ?.value
                    .orEmpty()
                    .map { it.substringBefore(';').trim() }
                    .filter { it.contains('=') }
                    .distinct()
                    .joinToString("; ")
                    .takeIf { it.isNotBlank() }
            } catch (_: Exception) {
                null
            } finally {
                connection.disconnect()
            }
            cachedVisitorCookies = result
            cachedAtMillis = System.currentTimeMillis()
            return result
        }
    }

    private const val VISITOR_COOKIE_TIMEOUT_MS = 5_000
    private const val VISITOR_COOKIE_TTL_MS = 10 * 60 * 1000L
}
