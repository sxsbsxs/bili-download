package com.example.bilidl

/**
 * Builds the parameter set for Bilibili's WBI playurl endpoint.
 *
 * try_look is an undocumented preview parameter observed in Bilibili's web
 * API. It is sent only for anonymous requests; it does not bypass paid/DRM
 * access controls and the API may stop honoring it at any time.
 */
internal object BiliPlayUrlRequestPolicy {
    fun createParameters(
        bvid: String?,
        aid: Long,
        cid: Long,
        quality: Int,
        hasSessionCookie: Boolean,
        fnval: String = "4048"
    ): LinkedHashMap<String, String> {
        val parameters = linkedMapOf<String, String>()
        if (!bvid.isNullOrBlank()) {
            parameters["bvid"] = bvid
        } else {
            parameters["avid"] = aid.toString()
        }
        parameters["cid"] = cid.toString()
        // A no-quality probe must ask for the 1080P preview tier; a qn=0
        // request can negotiate a lower anonymous default before try_look is applied.
        val requestedQuality = if (!hasSessionCookie && quality <= 0) 80 else quality
        parameters["qn"] = requestedQuality.toString()
        parameters["fnval"] = fnval
        // Keep the DASH request on the desktop/web player path. The HTML5
        // platform negotiates MP4-only playback and is not our DASH pipeline.
        parameters["platform"] = "pc"
        parameters["fourk"] = "1"
        parameters["web_location"] = "1315873"
        if (!hasSessionCookie) {
            parameters["try_look"] = "1"
        }
        return parameters
    }

    /**
     * A stored SESSDATA may have expired between app launch and a download.
     * If Bilibili rejects that authenticated request with -101, retry the
     * same request without the stale cookie and with the anonymous preview flag.
     */
    fun shouldRetryWithoutSession(apiCode: Int, hadSessionCookie: Boolean): Boolean =
        apiCode == -101 && hadSessionCookie

    /**
     * If an anonymous playurl request is still login-gated, step down through
     * standard lower tiers instead of failing immediately at the requested tier.
     * Returned order is highest-to-lowest and never invents a quality label.
     */
    fun qualityFallbacks(requestedQuality: Int): List<Int> {
        val requested = requestedQuality.takeIf { it > 0 } ?: 80
        val lowerTiers = listOf(127, 126, 125, 120, 116, 112, 80, 74, 64, 32, 16, 6)
        return (listOf(requested) + lowerTiers.filter { it < requested }).distinct()
    }
}
