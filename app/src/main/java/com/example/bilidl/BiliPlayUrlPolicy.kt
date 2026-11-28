package com.example.bilidl

/**
 * Selects the Bilibili preview/anonymous playurl hint.
 *
 * Bilibili may expose additional ordinary-user qualities with try_look=1.
 * This is not an authorization bypass and does not grant premium-only formats.
 */
internal object BiliPlayUrlPolicy {
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
