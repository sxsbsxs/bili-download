package com.example.bilidl

/**
 * Selects the Bilibili playback request parameters used by the anonymous
 * public playurl and WBI playurl fallback.
 *
 * try_look is a Bilibili preview/anonymous hint, not an authorization bypass
 * and not a grant to premium-only formats.
 */
internal object BiliPlayUrlPolicy {
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
