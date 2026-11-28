package com.example.bilidl

import java.net.URI

/**
 * Mirrors BVD's endpoint-specific UA rule:
 * - view/archive metadata endpoints use okhttp/4.12.0;
 * - player/playurl and other browser-facing endpoints use the desktop browser UA.
 */
internal object BiliApiRequestPolicy {
    const val OKHTTP_USER_AGENT = "okhttp/4.12.0"

    private val okhttpUserAgentPaths = listOf(
        "/x/web-interface/view",
        "/x/web-interface/archive",
        "/x/v2/view"
    )

    fun userAgent(url: String): String {
        val path = runCatching { URI(url).path.orEmpty() }.getOrDefault("")
        return if (okhttpUserAgentPaths.any { path.startsWith(it) }) {
            OKHTTP_USER_AGENT
        } else {
            BiliCdnRequestPolicy.USER_AGENT
        }
    }
}
