package com.example.bilidl

/**
 * Selects the Bilibili playback request parameters used by the anonymous
 * public playurl and WBI playurl fallback.
 *
 * try_look is a Bilibili preview/anonymous hint, not an authorization bypass
 * and not a grant to premium-only formats.
 */
internal object BiliPlayUrlPolicy {
    const val PLAYER_USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_12_6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/63.0.3239.84 Safari/537.36"

    fun videoReferer(aid: Long): String = "https://www.bilibili.com/av$aid"

    fun hasPlayableStream(dashVideoCount: Int, durlCount: Int): Boolean =
        dashVideoCount > 0 || durlCount > 0

    fun publicPlayUrlParameters(
        aid: Long,
        cid: Long,
        quality: Int
    ): MutableMap<String, String> = linkedMapOf(
        "avid" to aid.toString(),
        "cid" to cid.toString(),
        "qn" to quality.toString(),
        "fnval" to "4048",
        "fnver" to "0",
        "force_host" to "2",
        "type" to "",
        "otype" to "json",
        "fourk" to "1"
    )

    fun applyTryLook(
        parameters: MutableMap<String, String>,
        hasSessionCookie: Boolean
    ) {
        if (hasSessionCookie) {
            parameters.remove("try_look")
        } else {
            parameters["try_look"] = "1"
        }
    }
}
