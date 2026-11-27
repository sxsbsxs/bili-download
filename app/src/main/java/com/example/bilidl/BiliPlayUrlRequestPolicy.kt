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
        hasSessionCookie: Boolean
    ): LinkedHashMap<String, String> {
        val parameters = linkedMapOf<String, String>()
        if (!bvid.isNullOrBlank()) {
            parameters["bvid"] = bvid
        } else {
            parameters["avid"] = aid.toString()
        }
        parameters["cid"] = cid.toString()
        parameters["qn"] = quality.toString()
        parameters["fnval"] = "4048"
        parameters["fourk"] = "1"
        parameters["web_location"] = "1315873"
        if (!hasSessionCookie) {
            parameters["try_look"] = "1"
        }
        return parameters
    }
}
