package com.example.bilidl

import java.io.IOException

/**
 * Extracts Bilibili's public WBI mixin key from the nav response.
 *
 * An unauthenticated nav response may use API code -101 but still include
 * data.wbi_img. Login status must not be used as the availability check for
 * these public signing keys.
 */
internal object BiliWbiKeyPolicy {
    private val permutation = intArrayOf(
        46,47,18,2,53,8,23,32,15,50,10,31,58,3,45,35,
        27,43,5,49,33,9,42,19,29,28,14,39,12,38,41,13,
        37,48,7,16,24,55,40,61,26,17,0,1,60,51,30,4,
        22,25,54,21,56,59,6,63,57,62,11,36,20,34,44,52
    )

    fun createMixinKey(apiCode: Int, imgUrl: String?, subUrl: String?): String {
        if (apiCode != 0 && apiCode != -101) {
            throw IOException("B站 WBI 密钥接口失败（code=$apiCode）")
        }

        val imgKey = keyFromUrl(imgUrl)
        val subKey = keyFromUrl(subUrl)
        if (imgKey == null || subKey == null) {
            val reason = if (apiCode == -101) {
                "未登录响应没有包含公开的 data.wbi_img 密钥"
            } else {
                "B站未返回有效的 WBI 图片密钥"
            }
            throw IOException(reason)
        }
        if (imgKey.length != 32 || subKey.length != 32) {
            throw IOException("B站 WBI 图片密钥长度异常")
        }

        val raw = imgKey + subKey
        val mixinKey = buildString(32) {
            permutation.forEach { index -> append(raw[index]) }
        }
        if (mixinKey.length != 32) {
            throw IOException("B站 WBI 密钥格式异常")
        }
        return mixinKey
    }

    private fun keyFromUrl(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val cleanUrl = value.trim().substringBefore('#').substringBefore('?')
        val filename = cleanUrl.substringAfterLast('/')
        val key = filename.substringBeforeLast('.', filename)
        return key.takeIf { it.isNotBlank() }
    }
}
