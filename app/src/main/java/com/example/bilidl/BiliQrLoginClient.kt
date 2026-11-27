package com.example.bilidl

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

internal data class BiliQrSession(
    val url: String,
    val key: String
)

internal data class BiliQrPollResult(
    val state: BiliQrLoginState,
    val sessdata: String? = null
)

internal enum class BiliQrLoginState {
    WAITING,
    SCANNED,
    EXPIRED,
    SUCCESS
}

/** Direct Bilibili web QR-login client. No application backend is involved. */
internal object BiliQrLoginClient {
    private const val BASE = "https://passport.bilibili.com/x/passport-login/web/qrcode"
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 Chrome/124.0.0.0 Mobile Safari/537.36"

    fun generate(): BiliQrSession {
        val (root, _) = requestJson("$BASE/generate")
        if (root.optInt("code", -1) != 0) {
            throw IOException("B站二维码申请失败：" + root.optString("message", "未知错误"))
        }
        val data = root.optJSONObject("data") ?: throw IOException("B站未返回二维码")
        val url = data.optString("url")
        val key = data.optString("qrcode_key")
        if (url.isBlank() || key.isBlank()) throw IOException("B站二维码数据不完整")
        return BiliQrSession(url, key)
    }

    fun renderQr(content: String, size: Int = 640): Bitmap {
        val hints = mapOf(EncodeHintType.MARGIN to 1)
        val matrix = try {
            QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        } catch (error: Exception) {
            throw IOException("二维码生成失败：" + (error.message ?: "无效的二维码内容"))
        }
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                pixels[y * size + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, size, 0, 0, size, size)
        }
    }

    fun poll(session: BiliQrSession): BiliQrPollResult {
        val query = URLEncoder.encode(session.key, "UTF-8")
        val (root, responseCookies) = requestJson("$BASE/poll?qrcode_key=$query")
        if (root.optInt("code", -1) != 0) {
            throw IOException("B站扫码状态请求失败：" + root.optString("message", "未知错误"))
        }
        val data = root.optJSONObject("data") ?: throw IOException("B站未返回扫码状态")
        val code = data.optInt("code", Int.MIN_VALUE)
        return when (BiliQrLoginProtocol.stateFor(code)) {
            BiliQrLoginState.WAITING -> BiliQrPollResult(BiliQrLoginState.WAITING)
            BiliQrLoginState.SCANNED -> BiliQrPollResult(BiliQrLoginState.SCANNED)
            BiliQrLoginState.EXPIRED -> BiliQrPollResult(BiliQrLoginState.EXPIRED)
            BiliQrLoginState.SUCCESS -> {
                val crossDomainUrl = data.optString("url").takeIf { it.startsWith("https://") }
                var sessdata = BiliQrLoginProtocol.extractSessdata(responseCookies, crossDomainUrl)
                if (sessdata.isNullOrBlank() && crossDomainUrl != null) {
                    sessdata = fetchCrossDomainCookie(crossDomainUrl)
                }
                if (sessdata.isNullOrBlank()) {
                    throw IOException("B站已确认扫码，但没有返回 SESSDATA；请刷新二维码重试")
                }
                BiliQrPollResult(BiliQrLoginState.SUCCESS, sessdata)
            }
        }
    }

    private fun fetchCrossDomainCookie(startUrl: String): String? {
        var current = URL(startUrl)
        val collectedCookies = ArrayList<String>()

        // Bilibili's newer web-login flow returns a one-time cross-domain ticket.
        // Visit it with a browser User-Agent and preserve cookies across redirects.
        repeat(6) {
            val connection = (current.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 20_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Referer", "https://www.bilibili.com/")
                setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*")
            }
            try {
                val status = connection.responseCode
                collectedCookies += setCookieHeaders(connection)
                val sessdata = BiliQrLoginProtocol.extractSessdata(collectedCookies)
                if (!sessdata.isNullOrBlank()) return sessdata

                if (status in 300..399) {
                    val location = connection.getHeaderField("Location")
                        ?: throw IOException("B站跨域授权跳转缺少目标地址")
                    current = URL(current, location)
                } else {
                    val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                    stream?.use { it.readBytes() }
                    if (status !in 200..299) {
                        throw IOException("B站跨域授权失败（HTTP $status）")
                    }
                    return BiliQrLoginProtocol.extractSessdata(collectedCookies)
                }
            } finally {
                connection.disconnect()
            }
        }
        return BiliQrLoginProtocol.extractSessdata(collectedCookies)
    }

    private fun requestJson(url: String): Pair<JSONObject, List<String>> {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Referer", "https://www.bilibili.com/")
            setRequestProperty("Origin", "https://www.bilibili.com")
            setRequestProperty("Accept", "application/json, text/plain, */*")
            setRequestProperty("Accept-Encoding", "identity")
        }
        try {
            val status = connection.responseCode
            val cookies = setCookieHeaders(connection)
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                ?: throw IOException("B站登录请求失败（HTTP $status）")
            val body = stream.use {
                BufferedReader(InputStreamReader(it, Charsets.UTF_8)).readText()
            }
            if (status !in 200..299) throw IOException("B站登录请求失败（HTTP $status）")
            return JSONObject(body) to cookies
        } finally {
            connection.disconnect()
        }
    }

    private fun setCookieHeaders(connection: HttpURLConnection): List<String> {
        return connection.headerFields.entries
            .filter { it.key.equals("Set-Cookie", ignoreCase = true) }
            .flatMap { it.value.orEmpty() }
    }
}

internal object BiliQrLoginProtocol {
    fun stateFor(code: Int): BiliQrLoginState = when (code) {
        0 -> BiliQrLoginState.SUCCESS
        86101 -> BiliQrLoginState.WAITING
        86090 -> BiliQrLoginState.SCANNED
        86038, 86039 -> BiliQrLoginState.EXPIRED
        else -> throw IOException("无法识别 B站扫码状态：$code")
    }

    /** Keep percent-encoded SESSDATA intact; decoding it can corrupt the cookie. */
    fun extractSessdata(
        setCookieHeaders: Iterable<String>,
        fallbackUrl: String? = null
    ): String? {
        val cookiePattern = Regex("""(?i)(?:^|;\\s*)SESSDATA=([^;\\s]+)""")
        for (header in setCookieHeaders) {
            cookiePattern.find(header)?.groupValues?.getOrNull(1)?.let { return it }
        }
        if (fallbackUrl != null) {
            Regex("""(?i)[?&#]SESSDATA=([^&#]+)""")
                .find(fallbackUrl)?.groupValues?.getOrNull(1)?.let { return it }
        }
        return null
    }
}
