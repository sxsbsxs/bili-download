package com.example.bilidl

/**
 * BVD-style web login uses the official Bilibili H5 passport page and only
 * accepts the redirect back to Bilibili's own main/mobile site as a cookie
 * hand-off point.
 */
internal object BiliWebLoginPolicy {
    const val LOGIN_URL = "https://passport.bilibili.com/h5-app/passport/login"

    private val callbackHosts = setOf(
        "m.bilibili.com",
        "www.bilibili.com",
        "bilibili.com"
    )

    fun shouldCaptureCookie(host: String?): Boolean =
        host?.lowercase() in callbackHosts

    fun extractSessdata(cookieHeaders: Iterable<String>): String? =
        BiliQrLoginProtocol.extractSessdata(cookieHeaders)
}
