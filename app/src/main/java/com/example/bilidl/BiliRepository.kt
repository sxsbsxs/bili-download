package com.example.bilidl

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.example.bilidl.common.content.BiliDanmaku
import com.example.bilidl.common.content.BiliDanmakuProtoParser
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
    val publishTime: Long = 0L,
    val categoryName: String = "",
    val categoryId: Int = 0,
    val copyright: Int = 0,
    val ownerMid: Long = 0L,
    val userLiked: Boolean = false,
    val userCoin: Int = 0,
    val userFavorited: Boolean = false,
    val ownerAvatarUrl: String? = null,
    val tags: List<String> = emptyList()
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

data class BiliComment(
    val id: Long,
    val userName: String,
    val avatarUrl: String?,
    val message: String,
    val likeCount: Long,
    val replyCount: Long,
    val createTime: Long,
    val likedByMe: Boolean
)

data class BiliFavoriteFolder(
    val id: Long,
    val title: String,
    val mediaCount: Long,
    val favState: Boolean = false
)

data class BiliVideoRelationState(
    val liked: Boolean,
    val coinCount: Int,
    val favorited: Boolean
)

data class BiliCommentPage(
    val comments: List<BiliComment>,
    val totalCount: Long,
    val nextPage: Int,
    val hasMore: Boolean
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
        val reqUser = data.optJSONObject("req_user")
        val relationState = if (!cookieProvider().isNullOrBlank()) {
            runCatching { getVideoRelationState(aid) }.getOrNull()
        } else null
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
            publishTime = data.optLong("pubdate"),
            categoryName = data.optString("tname"),
            categoryId = data.optInt("tid"),
            copyright = data.optInt("copyright"),
            ownerMid = data.optJSONObject("owner")?.optLong("mid") ?: 0L,
            userLiked = relationState?.liked ?: (reqUser?.optInt("like") == 1),
            userCoin = relationState?.coinCount ?: (reqUser?.optInt("coin") ?: 0),
            userFavorited = relationState?.favorited ?: (reqUser?.optInt("favorite") == 1),
            ownerAvatarUrl = data.optJSONObject("owner")?.optString("face")
                ?.takeIf { it.startsWith("http") },
            tags = getVideoTags(aid, actualBvid)
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
            val audioItem = audioCandidates
                .filter { it.optString("codecs").contains("mp4a", ignoreCase = true) }
                .maxByOrNull { it.optLong("bandwidth") }
            if (audioCandidates.isNotEmpty() && audioItem == null) {
                val codecs = audioCandidates.map { it.optString("codecs").ifBlank { "未知编码" } }
                    .distinct()
                    .joinToString("、")
                throw IOException(
                    "B站当前只返回非 AAC 音频编码（$codecs）。当前 Android 无损封装器不能保证合并成功，已停止下载以避免生成无声或损坏文件"
                )
            }

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

    /**
     * B站 XML 弹幕源。时间戳按播放器进度同步渲染。
     */
    /**
     * Load six-minute protobuf danmaku segments using Bilibili's modern web player API.
     * A failed later segment does not discard valid segments that were already received.
     */
    fun getVideoDanmaku(video: BiliVideo): List<BiliDanmaku> {
        require(video.cid > 0L) { "视频 CID 无效" }
        val segmentCount = ((video.durationSeconds.coerceAtLeast(1) + 359) / 360)
            .coerceIn(1, 120)
        val result = ArrayList<BiliDanmaku>()
        var firstError: Exception? = null
        for (segmentIndex in 1..segmentCount) {
            try {
                val bytes = getDanmakuSegment(video.cid, segmentIndex)
                result += BiliDanmakuProtoParser.parse(bytes)
            } catch (error: Exception) {
                if (segmentIndex == 1) firstError = error
            }
        }
        if (result.isEmpty() && firstError != null) {
            throw IOException("读取弹幕失败：" + (firstError.message ?: "B站弹幕接口不可用"))
        }
        return result.sortedBy { it.progressMs }
    }

    fun getVideoRelationState(aid: Long): BiliVideoRelationState {
        require(aid > 0L) { "视频 AV 号无效" }
        val data = checkedData(
            requestJson("https://api.bilibili.com/x/web-interface/archive/relation?aid=$aid")
        )
        fun flag(name: String): Boolean = when (val value = data.opt(name)) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            else -> value?.toString() == "1" || value?.toString()?.equals("true", ignoreCase = true) == true
        }
        return BiliVideoRelationState(
            liked = flag("like"),
            coinCount = data.optInt("coin").coerceIn(0, 2),
            favorited = flag("favorite")
        )
    }

    private fun getVideoTags(aid: Long, bvid: String?): List<String> {
        val query = if (!bvid.isNullOrBlank()) {
            "bvid=" + encode(bvid)
        } else {
            "aid=" + aid
        }
        return runCatching {
            val root = requestJson("https://api.bilibili.com/x/tag/archive/tags?$query")
            if (root.optInt("code", -1) != 0) return@runCatching emptyList()
            val items = root.optJSONArray("data") ?: JSONArray()
            (0 until items.length()).mapNotNull { index ->
                items.optJSONObject(index)?.optString("tag_name")
                    ?.trim()?.takeIf { it.isNotBlank() }
            }.distinct().take(20)
        }.getOrDefault(emptyList())
    }

    fun getDanmakuXml(cid: Long): String {
        require(cid > 0L) { "视频 CID 无效" }
        return requestText("https://comment.bilibili.com/" + cid + ".xml")
    }

    fun sendDanmaku(
        video: BiliVideo,
        text: String,
        progressMs: Long,
        color: Int = 0xFFFFFF,
        fontSize: Int = 25,
        mode: Int = 1
    ) {
        val message = text.trim().replace(Regex("\\s+"), " ")
        if (message.isBlank()) throw IOException("请输入弹幕内容")
        if (message.codePointCount(0, message.length) > 50) throw IOException("弹幕最多 50 个字符")
        if (mode !in setOf(1, 4, 5)) throw IOException("暂不支持这种弹幕类型")
        val csrf = requireCsrf()
        val root = requestFormJson(
            "https://api.bilibili.com/x/v2/dm/post",
            linkedMapOf(
                "type" to "1",
                "oid" to video.cid.toString(),
                "aid" to video.aid.toString(),
                "msg" to message,
                "progress" to progressMs.coerceAtLeast(0L).toString(),
                "color" to color.toString(),
                "fontsize" to fontSize.coerceIn(18, 36).toString(),
                "mode" to mode.toString(),
                "rnd" to System.currentTimeMillis().toString(),
                "csrf" to csrf,
                "csrf_token" to csrf
            )
        )
        ensureMutationSuccess(root, "弹幕发送失败")
    }

    fun setVideoLike(aid: Long, liked: Boolean) {
        val csrf = requireCsrf()
        val root = requestFormJson(
            "https://api.bilibili.com/x/web-interface/archive/like",
            mapOf(
                "aid" to aid.toString(),
                "like" to if (liked) "1" else "2",
                "dislike" to "0",
                "csrf" to csrf,
                "csrf_token" to csrf
            )
        )
        ensureMutationSuccess(root, if (liked) "点赞失败" else "取消点赞失败")
    }

    fun tripleLike(aid: Long) {
        val csrf = requireCsrf()
        val root = requestFormJson(
            "https://api.bilibili.com/x/web-interface/archive/like/triple",
            mapOf("aid" to aid.toString(), "csrf" to csrf, "csrf_token" to csrf)
        )
        ensureMutationSuccess(root, "一键三连失败")
    }

    fun addVideoCoin(aid: Long, count: Int = 1, alsoLike: Boolean = false) {
        val csrf = requireCsrf()
        val root = requestFormJson(
            "https://api.bilibili.com/x/web-interface/coin/add",
            mapOf(
                "aid" to aid.toString(),
                "multiply" to count.coerceIn(1, 2).toString(),
                "select_like" to if (alsoLike) "1" else "0",
                "cross_domain" to "true",
                "csrf" to csrf,
                "csrf_token" to csrf
            )
        )
        ensureMutationSuccess(root, "投币失败")
    }

    fun getFavoriteFolders(aid: Long, ownerMid: Long = 0L): List<BiliFavoriteFolder> {
        val mid = if (ownerMid > 0L) ownerMid else getCurrentMid()
        val root = requestJson(
            "https://api.bilibili.com/medialist/gateway/base/created?up_mid=" + mid +
                "&rid=" + aid + "&type=2&pn=1&ps=100"
        )
        val data = checkedData(root)
        val list = data.optJSONArray("list") ?: JSONArray()
        return (0 until list.length()).mapNotNull { index ->
            val item = list.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optLong("id").takeIf { it > 0L } ?: return@mapNotNull null
            BiliFavoriteFolder(
                id = id,
                title = item.optString("title").ifBlank { "收藏夹 " + id },
                mediaCount = item.optLong("media_count"),
                favState = item.optInt("fav_state") == 1
            )
        }
    }

    fun addVideoToFavorite(aid: Long, folderId: Long) {
        updateVideoFavoriteFolders(aid, listOf(folderId), emptyList())
    }

    fun updateVideoFavoriteFolders(
        aid: Long,
        addFolderIds: List<Long>,
        removeFolderIds: List<Long>
    ) {
        require(addFolderIds.all { it > 0L } && removeFolderIds.all { it > 0L }) {
            "收藏夹 ID 无效"
        }
        val csrf = requireCsrf()
        val root = requestFormJson(
            "https://api.bilibili.com/medialist/gateway/coll/resource/deal",
            mapOf(
                "rid" to aid.toString(),
                "type" to "2",
                "add_media_ids" to addFolderIds.distinct().joinToString(","),
                "del_media_ids" to removeFolderIds.distinct().joinToString(","),
                "csrf" to csrf,
                "csrf_token" to csrf
            )
        )
        ensureMutationSuccess(root, "收藏夹更新失败")
    }

    fun getVideoComments(aid: Long, page: Int = 1, pageSize: Int = 20, sort: Int = 2): BiliCommentPage {
        val currentPage = page.coerceAtLeast(1)
        val size = pageSize.coerceIn(1, 50)
        val root = requestJson(
            "https://api.bilibili.com/x/v2/reply/main?oid=" + aid +
                "&plat=2&type=1&sort=" + (if (sort == 0) 0 else 2) +
                "&pn=" + currentPage + "&ps=" + size
        )
        val data = checkedData(root)
        val replies = data.optJSONArray("replies") ?: JSONArray()
        val comments = (0 until replies.length()).mapNotNull { index ->
            val item = replies.optJSONObject(index) ?: return@mapNotNull null
            val content = item.optJSONObject("content")
            val member = item.optJSONObject("member")
            val message = content?.optString("message").orEmpty().trim()
            val id = item.optLong("rpid")
            if (id <= 0L || message.isBlank()) return@mapNotNull null
            BiliComment(
                id = id,
                userName = member?.optString("uname").orEmpty().ifBlank { "B站用户" },
                avatarUrl = member?.optString("avatar")?.takeIf { it.startsWith("http") },
                message = message,
                likeCount = item.optLong("like"),
                replyCount = item.optLong("rcount"),
                createTime = item.optLong("ctime"),
                likedByMe = item.optInt("action") == 1
            )
        }
        val total = data.optJSONObject("page")?.optLong("count") ?: comments.size.toLong()
        return BiliCommentPage(
            comments = comments,
            totalCount = total,
            nextPage = currentPage + 1,
            hasMore = currentPage * size < total && comments.isNotEmpty()
        )
    }

    fun sendVideoComment(
        aid: Long,
        message: String,
        rootId: Long? = null,
        parentId: Long? = null
    ) {
        val text = message.trim()
        if (text.isBlank()) throw IOException("请输入评论内容")
        if (text.length > 1000) throw IOException("评论内容过长")
        val csrf = requireCsrf()
        val form = linkedMapOf(
            "type" to "1",
            "oid" to aid.toString(),
            "message" to text,
            "plat" to "2",
            "csrf" to csrf,
            "csrf_token" to csrf
        )
        rootId?.takeIf { it > 0L }?.let { form["root"] = it.toString() }
        parentId?.takeIf { it > 0L }?.let { form["parent"] = it.toString() }
        val root = requestFormJson("https://api.bilibili.com/x/v2/reply/add", form)
        ensureMutationSuccess(root, "评论发送失败")
    }

    fun getVideoCommentReplies(
        aid: Long,
        rootId: Long,
        page: Int = 1,
        pageSize: Int = 20
    ): BiliCommentPage {
        require(rootId > 0L) { "根评论 ID 无效" }
        val currentPage = page.coerceAtLeast(1)
        val size = pageSize.coerceIn(1, 50)
        val root = requestJson(
            "https://api.bilibili.com/x/v2/reply/main?oid=" + aid +
                "&plat=2&root=" + rootId + "&sort=0&type=1&pn=" + currentPage + "&ps=" + size
        )
        val data = checkedData(root)
        val replies = data.optJSONArray("replies") ?: JSONArray()
        val comments = (0 until replies.length()).mapNotNull { index ->
            val item = replies.optJSONObject(index) ?: return@mapNotNull null
            val content = item.optJSONObject("content")
            val member = item.optJSONObject("member")
            val message = content?.optString("message").orEmpty().trim()
            val id = item.optLong("rpid")
            if (id <= 0L || message.isBlank()) return@mapNotNull null
            BiliComment(
                id = id,
                userName = member?.optString("uname").orEmpty().ifBlank { "B站用户" },
                avatarUrl = member?.optString("avatar")?.takeIf { it.startsWith("http") },
                message = message,
                likeCount = item.optLong("like"),
                replyCount = item.optLong("rcount"),
                createTime = item.optLong("ctime"),
                likedByMe = item.optInt("action") == 1
            )
        }
        val total = data.optJSONObject("page")?.optLong("count") ?: comments.size.toLong()
        return BiliCommentPage(
            comments = comments,
            totalCount = total,
            nextPage = currentPage + 1,
            hasMore = currentPage * size < total && comments.isNotEmpty()
        )
    }

    fun likeVideoComment(aid: Long, rpid: Long, liked: Boolean) {
        val csrf = requireCsrf()
        val root = requestFormJson(
            "https://api.bilibili.com/x/v2/reply/action",
            mapOf(
                "type" to "1",
                "oid" to aid.toString(),
                "rpid" to rpid.toString(),
                "action" to if (liked) "1" else "0",
                "csrf" to csrf,
                "csrf_token" to csrf
            )
        )
        ensureMutationSuccess(root, "评论互动失败")
    }

    fun getRelatedVideos(aid: Long): List<BiliSearchVideo> {
        val root = requestJson("https://api.bilibili.com/x/web-interface/archive/related?aid=" + aid)
        val data = checkedData(root)
        val array = data.optJSONArray("list") ?: data.optJSONArray("archives") ?: JSONArray()
        return parseVideoItems(array)
    }

    private fun getCurrentMid(): Long {
        val root = requestJson("https://api.bilibili.com/x/web-interface/nav")
        return checkedData(root).optLong("mid").takeIf { it > 0L }
            ?: throw IOException("无法获取当前 B 站账号 ID")
    }

    private fun requireCsrf(): String {
        val cookie = cookieHeader(cookieProvider()).orEmpty()
        val csrf = SecureCookieStore.cookieValue(cookie, "bili_jct")
        if (csrf.isBlank()) {
            throw IOException(
                "登录凭据缺少 bili_jct。请重新扫码登录，或粘贴包含 bili_jct 的完整 Cookie"
            )
        }
        return csrf
    }

    private fun ensureMutationSuccess(root: JSONObject, fallback: String) {
        val code = root.optInt("code", -1)
        if (code != 0) {
            val message = root.optString("message").ifBlank { fallback }
            throw IOException(fallback + "：" + message + "（" + code + "）")
        }
    }

    private fun requestFormJson(url: String, form: Map<String, String>): JSONObject {
        val cookie = cookieHeader(cookieProvider())
            ?: throw IOException("请先登录 B 站账号")
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 25_000
            doOutput = true
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", userAgent)
            val archiveId = form["aid"] ?: form["oid"] ?: form["rid"]
            val referer = archiveId?.takeIf { it.toLongOrNull() != null }
                ?.let { "https://www.bilibili.com/video/av$it" }
                ?: "https://www.bilibili.com/"
            setRequestProperty("Referer", referer)
            setRequestProperty("Origin", "https://www.bilibili.com")
            setRequestProperty("Accept", "application/json, text/plain, */*")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            setRequestProperty("Cookie", cookie)
        }
        try {
            val body = form.entries.joinToString("&") { encode(it.key) + "=" + encode(it.value) }
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream
                else connection.errorStream ?: throw BiliHttpException(status, "B站互动请求")
            val response = stream.use {
                BufferedReader(InputStreamReader(it, Charsets.UTF_8)).readText()
            }
            if (status !in 200..299) throw BiliHttpException(status, "B站互动请求", response.take(160))
            return JSONObject(response)
        } finally {
            connection.disconnect()
        }
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
            cookieHeader(cookieOverride)?.let { setRequestProperty("Cookie", it) }
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) {
                val detail = runCatching {
                    connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                }.getOrNull().orEmpty().take(160)
                throw BiliHttpException(status, "B站字幕请求", detail)
            }
            val stream = connection.inputStream
            val decoded = if (connection.contentEncoding.equals("gzip", ignoreCase = true)) {
                java.util.zip.GZIPInputStream(stream)
            } else stream
            return decoded.bufferedReader(Charsets.UTF_8).use { it.readText() }
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
            cookieHeader(cookieOverride)?.let { setRequestProperty("Cookie", it) }
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
        // First mirror Bilimiao2's public HTTP playurl request. It uses the aid/cid
        // pair and does not require WBI signing when Bilibili returns a public stream.
        val publicParameters = BiliPlayUrlPolicy.publicPlayUrlParameters(aid, cid, quality)
        val publicQuery = publicParameters.entries.joinToString("&") {
            encode(it.key) + "=" + encode(it.value)
        }
        val publicResponse = runCatching {
            requestJson("https://api.bilibili.com/x/player/playurl?" + publicQuery)
        }.getOrNull()
        if (publicResponse != null && hasPlayablePlayInfo(publicResponse)) {
            return publicResponse
        }

        // Fall back to WBI. For anonymous requests try_look=1 may expose additional
        // ordinary-user formats; authenticated requests omit it. Neither path
        // bypasses premium-only restrictions.
        val parameters = linkedMapOf<String, String>()
        if (!bvid.isNullOrBlank()) parameters["bvid"] = bvid else parameters["aid"] = aid.toString()
        parameters["cid"] = cid.toString()
        parameters["qn"] = quality.toString()
        parameters["fnval"] = "4048"
        parameters["fnver"] = "0"
        parameters["force_host"] = "2"
        parameters["fourk"] = "1"
        parameters["otype"] = "json"
        parameters["type"] = ""
        parameters["web_location"] = "1315873"
        BiliPlayUrlPolicy.applyTryLook(
            parameters,
            hasSessionCookie = !cookieHeader(cookieProvider()).isNullOrBlank()
        )
        val signedQuery = signWbi(parameters)
        return requestJson("https://api.bilibili.com/x/player/wbi/playurl?" + signedQuery)
    }

    private fun hasPlayablePlayInfo(root: JSONObject): Boolean {
        if (root.optInt("code", -1) != 0) return false
        val data = root.optJSONObject("data") ?: return false
        val dash = data.optJSONObject("dash")
        val hasDashVideo = (dash?.optJSONArray("video")?.length() ?: 0) > 0
        val hasDashAudio = (dash?.optJSONArray("audio")?.length() ?: 0) > 0
        val hasDurl = (data.optJSONArray("durl")?.length() ?: 0) > 0
        return (hasDashVideo && hasDashAudio) || hasDurl
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
            cookieHeader(cookieOverride)?.let { setRequestProperty("Cookie", it) }
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

    private fun cookieHeader(rawCookie: String?): String? {
        if (rawCookie.isNullOrBlank()) return null
        val value = rawCookie.trim()
        return if (value.contains(Regex("""(?:^|;\s*)SESSDATA="""))) {
            value
        } else {
            "SESSDATA=" + value
        }
    }

    private fun encode(text: String): String = URLEncoder.encode(text, "UTF-8")
}
