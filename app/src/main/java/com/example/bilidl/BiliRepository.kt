package com.example.bilidl

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.example.bilidl.common.content.BiliSubtitleCue
import com.example.bilidl.common.content.BiliSubtitleTrack
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
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
    val pages: List<BiliPage> = emptyList(),
    val description: String = "",
    val viewCount: Long = 0L,
    val danmakuCount: Long = 0L,
    val likeCount: Long = 0L,
    val coinCount: Long = 0L,
    val favoriteCount: Long = 0L,
    val shareCount: Long = 0L,
    val commentCount: Long = 0L,
    val publishTime: Long = 0L
)

data class BiliPlaySource(
    val videoUrls: List<String>,
    val audioUrls: List<String>,
    val directUrls: List<String>,
    val format: String,
    val videoMime: String,
    val audioMime: String,
    val videoInitializationRange: String? = null,
    val videoIndexRange: String? = null,
    val audioInitializationRange: String? = null,
    val audioIndexRange: String? = null,
    val videoBandwidth: Long = 0L,
    val audioBandwidth: Long = 0L,
    val videoCodecs: String = "",
    val audioCodecs: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val frameRate: String = ""
)

data class BiliSearchVideo(
    val bvid: String,
    val aid: Long,
    val title: String,
    val author: String,
    val coverUrl: String?,
    val duration: String,
    val viewCount: Long,
    val danmakuCount: Long,
    val likeCount: Long,
    val favoriteCount: Long,
    val commentCount: Long,
    val publishTime: Long,
    val description: String,
    val tags: List<String>
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

    /**
     * Public hot-video feed used as the initial home timeline.
     * The popular endpoint does not require a logged-in account.
     */
    fun getPopularVideos(page: Int = 1, pageSize: Int = 20): List<BiliSearchVideo> {
        val safePage = page.coerceAtLeast(1)
        val safeSize = pageSize.coerceIn(1, 50)
        val root = requestJson(
            "https://api.bilibili.com/x/web-interface/popular?ps=" +
                safeSize + "&pn=" + safePage
        )
        val data = checkedData(root)
        val list = data.optJSONArray("list") ?: data.optJSONArray("archives") ?: JSONArray()
        val results = parseVideoItems(list)
        if (results.isEmpty()) throw IOException("B站暂时没有返回热门视频，请稍后重试")
        return results
    }

    /**
     * Search public Bilibili video results. WBI is preferred; older and aggregate
     * endpoints are fallbacks so basic search still works when one route is gated.
     */
    fun searchVideos(keyword: String, page: Int = 1, order: String = "totalrank"): List<BiliSearchVideo> {
        val query = keyword.trim()
        if (query.isEmpty()) throw IOException("请输入搜索关键词")
        if (query.length > 100) throw IOException("搜索关键词不能超过 100 个字符")
        val pageNumber = page.coerceAtLeast(1)
        val parameters = linkedMapOf(
            "search_type" to "video",
            "keyword" to query,
            "page" to pageNumber.toString(),
            "order" to order,
            "duration" to "0",
            "tids" to "0",
            "page_size" to "20",
            "web_location" to "1430650"
        )

        val wbiResults = runCatching {
            val root = requestJson(
                "https://api.bilibili.com/x/web-interface/wbi/search/type?" + signWbi(parameters)
            )
            parseVideoSearchResults(root)
        }.getOrNull()
        if (!wbiResults.isNullOrEmpty()) return wbiResults

        val legacyResults = runCatching {
            val queryString = parameters.entries.joinToString("&") {
                encode(it.key) + "=" + encode(it.value)
            }
            parseVideoSearchResults(
                requestJson("https://api.bilibili.com/x/web-interface/search/type?$queryString")
            )
        }.getOrNull()
        if (!legacyResults.isNullOrEmpty()) return legacyResults

        val aggregateParameters = "keyword=" + encode(query) +
            "&page=" + pageNumber + "&pagesize=20"
        val aggregate = requestJson(
            "https://api.bilibili.com/x/web-interface/search/all/v2?$aggregateParameters"
        )
        val results = parseVideoSearchResults(aggregate)
        if (results.isEmpty()) throw IOException("没有找到相关视频，请更换关键词后重试")
        return results
    }

    private fun parseVideoSearchResults(root: JSONObject): List<BiliSearchVideo> {
        val data = checkedData(root)
        val groups = data.optJSONArray("result") ?: return emptyList()
        val directVideos = JSONArray()
        for (index in 0 until groups.length()) {
            val group = groups.optJSONObject(index) ?: continue
            if (group.optString("result_type") == "video") {
                val nested = group.optJSONArray("data") ?: continue
                for (nestedIndex in 0 until nested.length()) {
                    nested.optJSONObject(nestedIndex)?.let(directVideos::put)
                }
            } else if (
                group.optString("type") == "video" ||
                group.has("bvid") ||
                group.has("aid") && group.has("pic")
            ) {
                directVideos.put(group)
            }
        }
        return parseVideoItems(directVideos)
    }

    private fun parseVideoItems(items: JSONArray): List<BiliSearchVideo> {
        return (0 until items.length()).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            val bvid = item.optString("bvid").trim()
            val aid = item.optLong("aid", item.optLong("id"))
            if (bvid.isBlank() && aid <= 0L) return@mapNotNull null
            val rawCover = item.optString("pic").takeIf { it.isNotBlank() }
            val cover = when {
                rawCover == null -> null
                rawCover.startsWith("//") -> "https:$rawCover"
                rawCover.startsWith("http://", true) || rawCover.startsWith("https://", true) -> rawCover
                else -> null
            }
            val owner = item.optJSONObject("owner")
            val stat = item.optJSONObject("stat")
            val tagText = item.optString("tag")
            BiliSearchVideo(
                bvid = bvid.ifBlank { "av$aid" },
                aid = aid,
                title = cleanSearchText(item.optString("title")).ifBlank { "B站视频" },
                author = cleanSearchText(
                    item.optString("author").ifBlank { owner?.optString("name").orEmpty() }
                ).ifBlank { "未知 UP 主" },
                coverUrl = cover,
                duration = item.optString("duration"),
                viewCount = searchCount(item.opt("play") ?: stat?.opt("view")),
                danmakuCount = searchCount(item.opt("video_review") ?: stat?.opt("danmaku")),
                likeCount = searchCount(item.opt("like") ?: stat?.opt("like")),
                favoriteCount = searchCount(item.opt("favorites") ?: stat?.opt("favorite")),
                commentCount = searchCount(item.opt("review") ?: stat?.opt("reply")),
                publishTime = item.optLong("pubdate"),
                description = cleanSearchText(
                    item.optString("description").ifBlank { item.optString("desc") }
                ),
                tags = tagText.split(",").map { cleanSearchText(it) }.filter { it.isNotBlank() }.take(8)
            )
        }.distinctBy { it.bvid }.take(50)
    }

    private fun cleanSearchText(value: String): String {
        return android.text.Html.fromHtml(value, android.text.Html.FROM_HTML_MODE_LEGACY)
            .toString()
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun searchCount(value: Any?): Long {
        if (value is Number) return value.toLong().coerceAtLeast(0L)
        val raw = value?.toString()?.replace(",", "")?.trim().orEmpty()
        if (raw.isBlank()) return 0L
        val multiplier = when {
            raw.endsWith("亿") -> 100_000_000.0
            raw.endsWith("万") -> 10_000.0
            else -> 1.0
        }
        val number = raw.removeSuffix("亿").removeSuffix("万").toDoubleOrNull() ?: return 0L
        return (number * multiplier).toLong().coerceAtLeast(0L)
    }

    fun loadCoverImage(url: String?): Bitmap? = downloadCover(url)

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
        val stat = data.optJSONObject("stat")
        val play = getPlayInfo(actualBvid, aid, cid, 0)
        val qualities = parseQualities(play)
        return BiliVideo(
            actualBvid, aid, cid, pageIndex + 1, title, owner, duration,
            coverUrl, null, qualities, availablePages,
            description = data.optString("desc"),
            viewCount = stat?.optLong("view") ?: 0L,
            danmakuCount = stat?.optLong("danmaku") ?: 0L,
            likeCount = stat?.optLong("like") ?: 0L,
            coinCount = stat?.optLong("coin") ?: 0L,
            favoriteCount = stat?.optLong("favorite") ?: 0L,
            shareCount = stat?.optLong("share") ?: 0L,
            commentCount = stat?.optLong("reply") ?: 0L,
            publishTime = data.optLong("pubdate")
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
                val videoSegmentBase = videoItem?.optJSONObject("SegmentBase")
                    ?: videoItem?.optJSONObject("segment_base")
                val audioSegmentBase = audioItem?.optJSONObject("SegmentBase")
                    ?: audioItem?.optJSONObject("segment_base")
                return BiliPlaySource(
                    videoUrls = videoUrls,
                    audioUrls = audioUrls,
                    directUrls = emptyList(),
                    format = "dash",
                    videoMime = videoItem?.optString("mimeType").orEmpty(),
                    audioMime = audioItem?.optString("mimeType").orEmpty(),
                    videoInitializationRange = videoSegmentBase?.let { it.optString("Initialization").ifBlank { it.optString("initialization") } },
                    videoIndexRange = videoSegmentBase?.let { it.optString("indexRange").ifBlank { it.optString("index_range") } },
                    audioInitializationRange = audioSegmentBase?.let { it.optString("Initialization").ifBlank { it.optString("initialization") } },
                    audioIndexRange = audioSegmentBase?.let { it.optString("indexRange").ifBlank { it.optString("index_range") } },
                    videoBandwidth = videoItem?.optLong("bandwidth") ?: 0L,
                    audioBandwidth = audioItem?.optLong("bandwidth") ?: 0L,
                    videoCodecs = videoItem?.optString("codecs").orEmpty(),
                    audioCodecs = audioItem?.optString("codecs").orEmpty(),
                    width = videoItem?.optInt("width") ?: 0,
                    height = videoItem?.optInt("height") ?: 0,
                    frameRate = videoItem?.optString("frameRate").orEmpty()
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

    /**
     * 获取当前分P可用字幕。字幕列表由 B站 player/wbi/v2 返回。
     */
    fun getSubtitleTracks(video: BiliVideo): List<BiliSubtitleTrack> {
        val parameters = linkedMapOf<String, String>()
        if (!video.bvid.isNullOrBlank()) {
            parameters["bvid"] = video.bvid
        } else {
            parameters["aid"] = video.aid.toString()
        }
        parameters["cid"] = video.cid.toString()
        val root = requestJson(
            "https://api.bilibili.com/x/player/wbi/v2?" + signWbi(parameters)
        )
        val subtitleArray = checkedData(root)
            .optJSONObject("subtitle")
            ?.optJSONArray("subtitles")
            ?: return emptyList()

        return (0 until subtitleArray.length()).mapNotNull { index ->
            val item = subtitleArray.optJSONObject(index) ?: return@mapNotNull null
            val subtitleUrl = item.optString("subtitle_url").takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            BiliSubtitleTrack(
                id = item.optLong("id"),
                language = item.optString("lan").ifBlank { "und" },
                languageName = item.optString("lan_doc")
                    .ifBlank { item.optString("lan").ifBlank { "字幕" } },
                url = normalizeSubtitleUrl(subtitleUrl)
            )
        }.distinctBy { it.url }
    }

    /**
     * 下载并解析一条 BCC JSON 字幕轨道。
     */
    fun getSubtitleCues(track: BiliSubtitleTrack): List<BiliSubtitleCue> {
        val root = JSONObject(requestText(normalizeSubtitleUrl(track.url)))
        val body = root.optJSONArray("body") ?: return emptyList()
        return (0 until body.length()).mapNotNull { index ->
            val item = body.optJSONObject(index) ?: return@mapNotNull null
            val from = item.optDouble("from", Double.NaN)
            val to = item.optDouble("to", Double.NaN)
            val text = item.optString("content").trim()
            if (!from.isFinite() || !to.isFinite() || from < 0.0 || to <= from || text.isBlank()) {
                return@mapNotNull null
            }
            BiliSubtitleCue(from, to, text)
        }
    }

    /**
     * 获取一个 6 分钟分段的弹幕 protobuf。调用方按视频时长计算 segment_index。
     */
    fun getDanmakuSegment(cid: Long, segmentIndex: Int): ByteArray {
        require(cid > 0L) { "视频 CID 无效" }
        require(segmentIndex > 0) { "弹幕分段编号无效" }
        val url = "https://api.bilibili.com/x/v2/dm/web/seg.so" +
            "?type=1&oid=" + cid + "&segment_index=" + segmentIndex
        return requestBytes(url)
    }

    private fun normalizeSubtitleUrl(value: String): String = when {
        value.startsWith("//") -> "https:$value"
        value.startsWith("http://", ignoreCase = true) ||
            value.startsWith("https://", ignoreCase = true) -> value
        value.startsWith("/") -> "https://aisubtitle.hdslb.com$value"
        else -> "https://aisubtitle.hdslb.com/$value"
    }

    private fun requestText(url: String, cookieOverride: String? = cookieProvider()): String {
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
            if (status !in 200..299) {
                val detail = runCatching {
                    connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                }.getOrNull().orEmpty().take(160)
                throw BiliHttpException(status, "B站字幕请求", detail)
            }
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun requestBytes(url: String, cookieOverride: String? = cookieProvider()): ByteArray {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Referer", "https://www.bilibili.com/")
            setRequestProperty("Origin", "https://www.bilibili.com")
            setRequestProperty("Accept", "application/octet-stream, */*")
            setRequestProperty("Accept-Encoding", "identity")
            if (!cookieOverride.isNullOrBlank()) {
                setRequestProperty("Cookie", "SESSDATA=" + cookieOverride)
            }
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) {
                val detail = runCatching {
                    connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                }.getOrNull().orEmpty().take(160)
                throw BiliHttpException(status, "B站弹幕请求", detail)
            }
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(64 * 1024)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > 12 * 1024 * 1024) {
                        throw IOException("弹幕分段超过 12 MiB 安全上限")
                    }
                    output.write(buffer, 0, count)
                }
            }
            return output.toByteArray()
        } finally {
            connection.disconnect()
        }
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
        val secureUrl = when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("http://", ignoreCase = true) -> "https://" + url.substringAfter("://")
            else -> url
        }
        return try {
            val connection = URL(secureUrl).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 10_000
                connection.readTimeout = 15_000
                connection.instanceFollowRedirects = true
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
