package com.example.bilidl

import java.net.URI

/**
 * BVD's QR login screen opens the exact QR URL returned by Bilibili through
 * Android ACTION_VIEW. Installed Bilibili versions can claim this HTTPS URL
 * and take over the confirmation flow.
 */
internal object BiliQrAppLoginPolicy {
    fun isTrustedQrUrl(value: String): Boolean =
        runCatching {
            val uri = URI(value)
            uri.scheme.equals("https", ignoreCase = true) &&
                (uri.host.equals("passport.bilibili.com", ignoreCase = true) ||
                    uri.host.endsWith(".bilibili.com", ignoreCase = true))
        }.getOrDefault(false)
}
