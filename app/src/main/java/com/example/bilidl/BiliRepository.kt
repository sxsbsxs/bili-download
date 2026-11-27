package com.example.bilidl

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale

data class BiliQuality(val id: Int, val label: String)

data class BiliPage(
    val pageNumber: Int,
    val cid: Long,
    val part: String,
    val durationSeconds: Int
)

data class BiliVideo(
    val bvid: String?,
    val aid: Long,
    val cid: Long,
    val page: Int,
    val title: String,
    val uploader: String,
    val durationSeconds: Int,
    val coverUrl: String?,
    val cover: Bitmap?,
    val qualities: List<BiliQuality>,
    val pages: List<BiliPage> = emptyList()
)

data class BiliPlaySource(
    val videoUrls: List<String>,
    val audioUrls: List<String>,
    val directUrls: List<String>,
    val format: String,
    val videoMime: String,
    val audioMime: String
)

class BiliRepository(
    private val cookieProvider: () -> String? = { null }
) {
    private val userAgent =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    private var mixinKeyCache: String? = null
    private var mixinKeyExpiresAt = 0L

    fun verifyCookie(rawCookie: String): Pair<String, Boolean> {
        val cookie = SecureCookieStore.normalizeCookie(rawCookie)
        val root = requestJson("https://api.bilibili.com/x/web-interface/nav", cookie)
        val data = root.optJSONObject("data") ?: throw IOException("B站没有返回账号信息")
        if (!data.optBoolean("isLogin", false)) {
            throw IOException("SESSDATA 无效或已过期，请重新授权")
        }
        val name = data.optString("uname").ifBlank { "B站用户" }
        val vip = data.optInt("vipStatus", 0) == 1
        return name to vip
    }

    fun inspect(rawInput: String): BiliVideo {
        val normalized = BiliLinkParser.normalize(rawInput)
            ?: throw IOException("不是有效的 B站链接或 BV / av / ep / ss 编号")
        val resolved = resolveRedirect(normalized)
        val uri = Uri.parse(resolved)
        val path = uri.path.orEmpty()

        val episodeId = Regex("""(?:^|/)(ep\d+)(?:/|$)""", RegexOption.IGNORE_CASE)
            .find(path)?.groupValues?.get(1)
        val seasonId = Regex("""(?:^|/)(ss\d+)(?:/|$)""", RegexOption.IGNORE_CASE)
            .find(path)?.groupValues?.get(1)

        val result = if (episodeId != null || seasonId != null) {
            inspectBangumi(episodeId, seasonId)
        } else {
            inspectVideo(uri)
        }
        return result.copy(cover = downloadCover(result.coverUrl))
    }

    private fun inspectVideo(uri: Uri): BiliVideo {
        val path = uri.path.orEmpty()
        val bvid = Regex("""BV[0-9A-Za-z]{10}""", RegexOption.IGNORE_CASE)
            .find(path)?.value
        val avidText = Regex("""av(\d+)""", RegexOption.IGNORE_CASE).find(path)?.groupValues?.get(1)
            ?: uri.getQueryParameter("aid")
        if (bvid == null && avidText == null) {
            throw IOException("短链接没有跳转到可识别的视频地址")
        }

        val query = if (bvid != null) {
            "bvid=" + encode(bvid)
        } else {
            "aid=" + encode(avidText!!)
        }
        val root = requestJson("https://api.bilibili.com/x/web-interface/view?" + query)
        val data = checkedData(root)
        val aid = data.optLong("aid", avidText?.toLongOrNull() ?: 0L)
        val actualBvid = data.optString("bvid").takeIf { it.isNotBlank() } ?: bvid
        val pages = data.optJSONArray("pages") ?: JSONArray()
        val requestedPage = (uri.getQueryParameter("p")?.toIntOrNull() ?: 1).coerceAtLeast(1)
        val pageIndex = (requestedPage - 1).coerceAtMost((pages.length() - 1).coerceAtLeast(0))
        val availablePages = (0 until pages.length()).mapNotNull { index ->
            val item = pages.optJSONObject(index) ?: return@mapNotNull null
            val pageCid = item.optLong("cid")
            if (pageCid <= 0L) return@mapNotNull null
            BiliPage(
                pageNumber = item.optInt("page", index + 1),
                cid = pageCid,
                part = item.optString("part").ifBlank { "P" + (index + 1) },
                durationSeconds = item.optInt("duration")
            )
        }
        val page = pages.optJSONObject(pageIndex)
        val cid = page?.optLong("cid") ?: data.optLong("cid")
        if (cid <= 0L) throw IOException("无法获取视频 CID")

        val baseTitle = data.optString("title").ifBlank { "B站视频" }
        val part = page?.optString("part").orEmpty()
        val title = if (pages.length() > 1 && part.isNotBlank()) baseTitle + " - " + part else baseTitle
        val owner = data.optJSONObject("owner")?.optString("name").orEmpty().ifBlank { "未知 UP 主" }
        val duration = page?.optInt("duration") ?: data.optInt("duration")
        val coverUrl = data.optString("pic").takeIf { it.startsWith("http") }
        val play = getPlayInfo(actualBvid, aid, cid, 0)
        val qualities = parseQualities(play)
        return BiliVideo(
            actualBvid, aid, cid, pageIndex + 1, title, owner, duration,
            coverUrl, null, qualities, availablePages
        )
    }

    private fun inspectBangumi(episodeId: String?, seasonId: String?): BiliVideo {
        val key = if (episodeId != null) "ep_id=" + episodeId.removePrefix("ep")
            else "season_id=" + seasonId!!.removePrefix("ss")
        val root = requestJson("https://api.bilibili.com/pgc/view/web/season?" + key)
        val result = checkedData(root, "result")
        val episodes = result.optJSONArray("episodes") ?: JSONArray()
        val selected = if (episodeId != null) {
            (0 until episodes.length()).mapNotNull { episodes.optJSONObject(it) }
                .firstOrNull { it.optLong("id") == episodeId.removePrefix("ep").toLongOrNull() }
        } else {
            episodes.optJSONObject(0)
        } ?: throw IOException("没有找到这个番剧剧集")
        val bvid = selected.optString("bvid").takeIf { it.isNotBlank() }
        val aid = selected.optLong("aid")
        val cid = selected.optLong("cid")
        if (cid <= 0L) throw IOException("番剧接口没有返回 CID")
        val seasonTitle = result.optString("title").ifBlank { "B站番剧" }
        val episodeTitle = selected.optString("long_title").ifBlank { selected.optString("title") }
        val title = (seasonTitle + " - " + episodeTitle).trimEnd(' ', '-')
        val coverUrl = selected.optString("cover")
            .takeIf { it.startsWith("http") }
            ?: result.optString("cover").takeIf { it.startsWith("http") }
        val play = getPlayInfo(bvid, aid, cid, 0)
        return BiliVideo(
            bvid, aid, cid, 1, title,
            result.optJSONObject("up_info")?.optString("uname") ?: "番剧",
            selected.optInt("duration"), coverUrl, null, parseQualities(play)
        )
    }

    fun getPlaySource(video: BiliVideo, requestedQuality: Int): BiliPlaySource {
        val root = getPlayInfo(video.bvid, video.aid, video.cid, requestedQuality)
        val data = checkedData(root)
        val dash = data.optJSONObject("dash")
        if (dash != null) {
            val videos = dash.optJSONArray("video") ?: JSONArray()
            val audios = dash.optJSONArray("audio") ?: JSONArray()
            val videoCandidates = (0 until videos.length()).mapNotNull { videos.optJSONObject(it) }
            val selectedIndex = BiliQualitySelector.chooseIndex(
                videoCandidates.map { it.optInt("id") },
                videoCandidates.map { it.optString("codecs") },
                requestedQuality
            ) ?: throw IOException(
                "B站没有返回所选画质或更低画质，已停止下载以避免超出你的画质选择"
            )
            val videoItem = videoCandidates[selectedIndex]
            val audioCandidates = (0 until audios.length()).mapNotNull { audios.optJSONObject(it) }
            val audioItem = audioCandidates.firstOrNull {
                it.optString("codecs").contains("mp4a", ignoreCase = true)
            } ?: audioCandidates.maxByOrNull { it.optInt("id") }

            val videoUrls = videoItem?.let { streamUrls(it) }.orEmpty()
            val audioUrls = audioItem?.let { streamUrls(it) }.orEmpty()
            if (videoUrls.isNotEmpty()) {
                return BiliPlaySource(
                    videoUrls, audioUrls, emptyList(),
                    "dash", videoItem?.optString("mimeType").orEmpty(),
                    audioItem?.optString("mimeType").orEmpty()
                )
            }
        }

        val durl = data.optJSONArray("durl") ?: JSONArray()
        val direct = (0 until durl.length()).mapNotNull { durl.optJSONObject(it) }
            .mapNotNull { it.optString("url").takeIf(String::isNotBlank) }
        if (direct.isNotEmpty()) {
            return BiliPlaySource(
                emptyList(), emptyList(), direct,
                data.optString("format", "mp4"), "video/mp4", ""
            )
        }
        throw IOException("B站没有返回可下载的音视频流；可能需要登录或该视频限制下载")
    }

    private fun getPlayInfo(bvid: String?, aid: Long, cid: Long, quality: Int): JSONObject {
        val parameters = linkedMapOf<String, String>()
        if (!bvid.isNullOrBlank()) parameters["bvid"] = bvid else parameters["avid"] = aid.toString()
        parameters["cid"] = cid.toString()
        parameters["qn"] = quality.toString()
        parameters["fnval"] = "4048"
        parameters["fourk"] = "1"
        parameters["web_location"] = "1315873"

        val signedQuery = signWbi(parameters)
        return requestJson("https://api.bilibili.com/x/player/wbi/playurl?" + signedQuery)
    }

    private fun parseQualities(play: JSONObject): List<BiliQuality> {
        val data = checkedData(play)
        val labels = linkedMapOf<Int, String>()
        val support = data.optJSONArray("support_formats") ?: JSONArray()
        for (i in 0 until support.length()) {
            val item = support.optJSONObject(i) ?: continue
            val id = item.optInt("quality")
            val label = item.optString("new_description")
                .ifBlank { item.optString("display_desc") }
            if (id > 0 && label.isNotBlank()) labels[id] = label
        }

        val accepted = data.optJSONArray("accept_quality") ?: JSONArray()
        val descriptions = data.optJSONArray("accept_description") ?: JSONArray()
        for (i in 0 until accepted.length()) {
            val id = accepted.optInt(i)
            val label = descriptions.optString(i).ifBlank { qualityLabel(id) }
            if (id > 0) labels.putIfAbsent(id, label)
        }

        if (labels.isEmpty()) {
            val dash = data.optJSONObject("dash")?.optJSONArray("video") ?: JSONArray()
            for (i in 0 until dash.length()) {
                val id = dash.optJSONObject(i)?.optInt("id") ?: continue
                labels[id] = qualityLabel(id)
            }
        }
        return labels.map { BiliQuality(it.key, it.value) }.sortedByDescending { it.id }
    }

    private fun qualityLabel(id: Int): String = when (id) {
        127 -> "8K"
        126 -> "杜比视界"
        125 -> "HDR"
        120 -> "4K"
        116 -> "1080P 60帧"
        112 -> "1080P+"
        80 -> "1080P"
        74 -> "720P 60帧"
        64 -> "720P"
        32 -> "480P"
        16 -> "360P"
        6 -> "240P"
        else -> "画质 " + id
    }

    private fun streamUrls(item: JSONObject): List<String> {
        val result = ArrayList<String>()
        val primary = item.optString("baseUrl").ifBlank { item.optString("base_url") }
        if (primary.isNotBlank()) result.add(primary)
        val backup = item.optJSONArray("backupUrl") ?: item.optJSONArray("backup_url")
        for (i in 0 until (backup?.length() ?: 0)) {
            backup?.optString(i)?.takeIf { it.isNotBlank() }?.let(result::add)
        }
        return result.distinct()
    }

    private fun signWbi(input: Map<String, String>): String {
        val key = getMixinKey()
        val params = input.toMutableMap()
        params["wts"] = (System.currentTimeMillis() / 1000L).toString()
        val cleaned = params.toSortedMap().mapValues { entry ->
            entry.value.replace(Regex("[!'()*]"), "")
        }
        val canonical = cleaned.entries.joinToString("&") {
            encode(it.key) + "=" + encode(it.value)
        }
        val sign = md5(canonical + key)
        return canonical + "&w_rid=" + sign
    }

    private fun getMixinKey(): String {
        if (mixinKeyCache != null && System.currentTimeMillis() < mixinKeyExpiresAt) {
            return mixinKeyCache!!
        }
        val root = requestJson("https://api.bilibili.com/x/web-interface/nav")
        val data = checkedData(root)
        val image = data.optJSONObject("wbi_img") ?: throw IOException("B站未返回 WBI 图片密钥")
        val imgKey = image.optString("img_url").substringAfterLast('/').substringBefore('.')
        val subKey = image.optString("sub_url").substringAfterLast('/').substringBefore('.')
        val raw = imgKey + subKey
        val permutation = intArrayOf(
            46,47,18,2,53,8,23,32,15,50,10,31,58,3,45,35,
            27,43,5,49,33,9,42,19,29,28,14,39,12,38,41,13,
            37,48,7,16,24,55,40,61,26,17,0,1,60,51,30,4,
            22,25,54,21,56,59,6,63,57,62,11,36,20,34,44,52
        )
        mixinKeyCache = buildString {
            permutation.forEach { index ->
                if (index in raw.indices) append(raw[index])
            }
        }.take(32)
        if (mixinKeyCache!!.length != 32) throw IOException("B站 WBI 密钥格式异常")
        mixinKeyExpiresAt = System.currentTimeMillis() + 60L * 60L * 1000L
        return mixinKeyCache!!
    }

    private fun md5(text: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }
    }

    private fun checkedData(root: JSONObject, key: String = "data"): JSONObject {
        val code = root.optInt("code", 0)
        if (code != 0) {
            val message = root.optString("message").ifBlank { "未知错误" }
            throw IOException("B站接口错误 " + code + "：" + message)
        }
        return root.optJSONObject(key) ?: throw IOException("B站返回内容不完整")
    }

    private fun resolveRedirect(input: String): String {
        if (!input.contains("b23.tv", ignoreCase = true)) return input
        val connection = (URL(input).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Referer", "https://www.bilibili.com/")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..399) throw IOException("B站短链接跳转失败（HTTP " + code + "）")
            return connection.url.toString()
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadCover(url: String?): Bitmap? {
        if (url.isNullOrBlank()) return null
        return try {
            val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("User-Agent", userAgent)
            connection.setRequestProperty("Referer", "https://www.bilibili.com/")
            connection.inputStream.use { BitmapFactory.decodeStream(it) }
        } finally {
            connection.disconnect()
        }
        } catch (_: Exception) {
            null
        }
    }

    private fun requestJson(url: String, cookieOverride: String? = cookieProvider()): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 25_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Referer", "https://www.bilibili.com/")
            setRequestProperty("Origin", "https://www.bilibili.com")
            setRequestProperty("Accept", "application/json, text/plain, */*")
            setRequestProperty("Accept-Encoding", "identity")
            if (!cookieOverride.isNullOrBlank()) {
                setRequestProperty("Cookie", "SESSDATA=" + cookieOverride)
            }
        }
        try {
            val status = connection.responseCode
            val stream: InputStream = if (status in 200..299) connection.inputStream
                else connection.errorStream ?: throw BiliHttpException(status, "B站接口请求")
            val body = stream.use {
                BufferedReader(InputStreamReader(it, Charsets.UTF_8)).readText()
            }
            if (status !in 200..299) {
                throw BiliHttpException(status, "B站接口请求", body.take(160))
            }
            return JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(text: String): String = URLEncoder.encode(text, "UTF-8")
}
