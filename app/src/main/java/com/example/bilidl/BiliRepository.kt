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
    val pages: List<BiliPage> = emptyList()
)

data class BiliPlaySource(
    val videoUrls: List<String>,
    val audioUrls: List<String>,
    val directSegments: List<BiliDirectSegment>,
    val format: String,
    val videoMime: String,
    val audioMime: String
)

class BiliRepository(
    private val cookieProvider: () -> String? = { null }
) {
    private val userAgent = BiliCdnRequestPolicy.USER_AGENT
    private var mixinKeyCache: String? = null
    private var mixinKeyExpiresAt = 0L
    @Volatile private var sessionCookieRejected = false

    fun verifyCookie(rawCookie: String): Pair<String, Boolean> {
        val cookie = SecureCookieStore.normalizeCookie(rawCookie)
        if (cookie.isBlank()) throw IOException("登录信息不包含 SESSDATA")
        val cookieHeader = SecureCookieStore.normalizeCookieHeader(rawCookie)
        val root = requestJson("https://api.bilibili.com/x/web-interface/nav", cookieHeader)
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
        val coverUrl = BiliCoverUrlPolicy.normalize(data.optString("pic"))
        // Public metadata must remain usable even when the anonymous playurl
        // endpoint refuses to return a quality list. Download re-resolves the
        // playurl at execution time and may succeed with a different CDN token.
        val qualities = getAvailableQualities(actualBvid, aid, cid)
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
        val coverUrl = BiliCoverUrlPolicy.normalize(selected.optString("cover"))
            ?: BiliCoverUrlPolicy.normalize(result.optString("cover"))
        val qualities = getAvailableQualities(bvid, aid, cid)
        return BiliVideo(
            bvid, aid, cid, 1, title,
            result.optJSONObject("up_info")?.optString("uname") ?: "番剧",
            selected.optInt("duration"), coverUrl, null, qualities
        )
    }


    /**
     * BVD-style all-site search: videos, anime, and film/TV media.
     * Returns canonical Bilibili URLs; selecting a row reuses the normal
     * inspect -> quality selection -> download pipeline.
     */
    fun searchBilibili(
        keyword: String,
        page: Int = 1,
        category: BiliSearchCategory = BiliSearchCategory.VIDEO
    ): List<BiliSearchResult> {
        val cleanKeyword = keyword.trim()
        require(cleanKeyword.isNotEmpty()) { "请输入搜索关键词" }
        require(page >= 1) { "搜索页码无效" }

        val parameters = linkedMapOf(
            "search_type" to category.apiType,
            "keyword" to cleanKeyword,
            "page" to page.toString()
        )
        val signedQuery = signWbi(parameters)
        val root = requestJson(
            "https://api.bilibili.com/x/web-interface/wbi/search/type?$signedQuery"
        )
        val data = checkedData(root)
        val items = data.optJSONArray("result") ?: JSONArray()

        return (0 until items.length()).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            val bvid = item.optString("bvid").takeIf { it.isNotBlank() }
            val aid = item.optLong("aid")
            val seasonId = item.optLong("season_id")
            val episodeId = item.optLong("ep_id")
            val resultUrl = when {
                !bvid.isNullOrBlank() -> "https://www.bilibili.com/video/$bvid"
                seasonId > 0L -> "https://www.bilibili.com/bangumi/play/ss$seasonId"
                episodeId > 0L -> "https://www.bilibili.com/bangumi/play/ep$episodeId"
                aid > 0L -> "https://www.bilibili.com/video/av$aid"
                else -> null
            } ?: return@mapNotNull null

            val rawTitle = item.optString("title").ifBlank { "B站内容" }
            val title = android.text.Html.fromHtml(
                rawTitle,
                android.text.Html.FROM_HTML_MODE_LEGACY
            ).toString().replace(Regex("\\s+"), " ").trim()
            val author = item.optString("author")
                .ifBlank { item.optString("uname") }
                .ifBlank { item.optString("up_name") }
                .ifBlank { item.optString("styles") }
                .ifBlank { "B站创作者" }
            val coverUrl = BiliCoverUrlPolicy.normalize(
                item.optString("pic").ifBlank { item.optString("cover") }
            )
            val detail = when (category) {
                BiliSearchCategory.VIDEO -> buildList {
                    item.optString("duration").takeIf { it.isNotBlank() }?.let(::add)
                    item.opt("play")?.toString()?.takeIf { it.isNotBlank() && it != "0" && it != "null" }
                        ?.let { add("播放 $it") }
                    item.opt("video_review")?.toString()?.takeIf { it.isNotBlank() && it != "0" && it != "null" }
                        ?.let { add("弹幕 $it") }
                }.joinToString(" · ")
                else -> listOf(
                    item.optString("index_show"),
                    item.optString("styles"),
                    item.optString("areas")
                ).filter { it.isNotBlank() }.distinct().joinToString(" · ")
            }
            BiliSearchResult(
                url = resultUrl,
                title = title,
                author = author,
                coverUrl = coverUrl,
                detail = detail
            )
        }
    }


    /** Read the signed-in Bilibili profile used by the BVD personal center. */
    fun getMyAccountProfile(): BiliAccountProfile {
        val data = checkedData(requestJson("https://api.bilibili.com/x/web-interface/nav"))
        if (!data.optBoolean("isLogin", false)) {
            throw IOException("请先登录 B站账号后查看个人内容")
        }
        val mid = data.optLong("mid")
        if (mid <= 0L) throw IOException("B站没有返回有效账号信息")
        return BiliAccountProfile(
            mid = mid,
            username = data.optString("uname").ifBlank { "B站用户" },
            faceUrl = BiliCoverUrlPolicy.normalize(data.optString("face")),
            sign = data.optString("sign")
        )
    }

    /**
     * Load the account pages exposed by BVD: submissions, viewing history,
     * watch-later, recent likes and favorite folders / folder contents.
     */
    fun fetchAccountPage(
        section: BiliAccountSection,
        mid: Long,
        page: Int = 1,
        historyCursor: BiliHistoryCursorState? = null,
        favoriteFolderId: Long = 0L,
        submissionOrder: BiliSubmissionOrder = BiliSubmissionOrder.LATEST,
        submissionTid: Long = 0L,
        submissionKeyword: String = ""
    ): BiliAccountLibraryPage {
        require(mid > 0L) { "账号 ID 无效" }
        require(page >= 1) { "页码无效" }

        return when (section) {
            BiliAccountSection.SUBMISSIONS -> {
                // Match BVD's space search: sort, partition, keyword and page are WBI-signed.
                val parameters = BiliSubmissionQueryPolicy.buildParameters(
                    mid = mid,
                    page = page,
                    order = submissionOrder,
                    tid = submissionTid,
                    keyword = submissionKeyword
                )
                val query = signWbi(parameters)
                val data = checkedData(
                    requestJson("https://api.bilibili.com/x/space/wbi/arc/search?$query")
                )
                val list = data.optJSONObject("list") ?: JSONObject()
                val rows = list.optJSONArray("vlist") ?: JSONArray()
                val count = data.optJSONObject("page")?.optLong("count") ?: rows.length().toLong()
                val partitionJson = list.optJSONObject("tlist") ?: JSONObject()
                val partitions = buildList {
                    val keys = partitionJson.keys()
                    while (keys.hasNext()) {
                        val item = partitionJson.optJSONObject(keys.next()) ?: continue
                        val tid = item.optLong("tid")
                        val name = item.optString("name")
                        if (tid > 0L && name.isNotBlank()) {
                            add(BiliSubmissionPartition(tid, name, item.optInt("count")))
                        }
                    }
                }.sortedBy { it.name }
                BiliAccountLibraryPage(
                    items = parseAccountItems(rows, "投稿"),
                    partitions = partitions,
                    hasMore = (page * 20L) < count && rows.length() > 0
                )
            }

            BiliAccountSection.HISTORY -> {
                // BVD preserves all three cursor fields; max alone can repeat or skip history rows.
                val query = BiliHistoryCursorPolicy.buildQuery(historyCursor)
                val data = checkedData(
                    requestJson("https://api.bilibili.com/x/web-interface/history/cursor?$query")
                )
                val rows = data.optJSONArray("list") ?: JSONArray()
                val responseCursor = data.optJSONObject("cursor")
                val nextCursor = responseCursor?.let {
                    BiliHistoryCursorState(
                        max = it.optLong("max"),
                        viewAt = it.optLong("view_at"),
                        business = it.optString("business")
                    )
                }
                BiliAccountLibraryPage(
                    items = parseAccountItems(rows, "观看历史"),
                    nextHistoryCursor = nextCursor,
                    hasMore = rows.length() > 0 && nextCursor != null && nextCursor != historyCursor
                )
            }

            BiliAccountSection.WATCH_LATER -> {
                val data = checkedData(requestJson("https://api.bilibili.com/x/v2/history/toview"))
                val rows = data.optJSONArray("list") ?: JSONArray()
                BiliAccountLibraryPage(items = parseAccountItems(rows, "稍后再看"))
            }

            BiliAccountSection.RECENT_LIKES -> {
                val data = checkedData(
                    requestJson("https://api.bilibili.com/x/space/like/video?vmid=$mid")
                )
                val rows = data.optJSONArray("list") ?: JSONArray()
                BiliAccountLibraryPage(items = parseAccountItems(rows, "最近点赞"))
            }

            BiliAccountSection.FAVORITES -> {
                if (favoriteFolderId <= 0L) {
                    val data = checkedData(
                        requestJson(
                            "https://api.bilibili.com/x/v3/fav/folder/created/list-all" +
                                "?up_mid=$mid&type=2"
                        )
                    )
                    val rows = data.optJSONArray("list") ?: JSONArray()
                    val folders = (0 until rows.length()).mapNotNull { index ->
                        val item = rows.optJSONObject(index) ?: return@mapNotNull null
                        val id = item.optLong("id")
                        if (id <= 0L) return@mapNotNull null
                        BiliFavoriteFolder(
                            id = id,
                            title = item.optString("title").ifBlank { "收藏夹" },
                            mediaCount = item.optInt("media_count"),
                            coverUrl = BiliCoverUrlPolicy.normalize(item.optString("cover"))
                        )
                    }
                    BiliAccountLibraryPage(folders = folders)
                } else {
                    val url = "https://api.bilibili.com/x/v3/fav/resource/list" +
                        "?media_id=$favoriteFolderId&pn=$page&ps=20"
                    val data = checkedData(requestJson(url))
                    val rows = data.optJSONArray("medias") ?: JSONArray()
                    val count = data.optJSONObject("info")?.optInt("media_count") ?: rows.length()
                    BiliAccountLibraryPage(
                        items = parseAccountItems(rows, "收藏"),
                        hasMore = (page * 20) < count && rows.length() > 0
                    )
                }
            }
        }
    }

    private fun parseAccountItems(rows: JSONArray, fallbackDetail: String): List<BiliAccountLibraryItem> =
        (0 until rows.length()).mapNotNull { index ->
            val item = rows.optJSONObject(index) ?: return@mapNotNull null
            val history = item.optJSONObject("history") ?: JSONObject()
            val bvid = item.optString("bvid").takeIf { it.isNotBlank() }
                ?: history.optString("bvid").takeIf { it.isNotBlank() }
            val aid = item.optLong("aid").takeIf { it > 0L }
                ?: item.optLong("id").takeIf { it > 0L }
                ?: history.optLong("oid").takeIf { it > 0L }
            val episodeId = history.optLong("epid").takeIf { it > 0L }
            val originalUri = item.optString("uri").takeIf {
                it.startsWith("https://", ignoreCase = true) &&
                    (it.contains("bilibili.com", ignoreCase = true) ||
                        it.contains("b23.tv", ignoreCase = true))
            }
            val url = when {
                !bvid.isNullOrBlank() -> "https://www.bilibili.com/video/$bvid"
                episodeId != null -> "https://www.bilibili.com/bangumi/play/ep$episodeId"
                originalUri != null -> originalUri
                aid != null -> "https://www.bilibili.com/video/av$aid"
                else -> null
            } ?: return@mapNotNull null

            val title = android.text.Html.fromHtml(
                item.optString("title").ifBlank { "B站视频" },
                android.text.Html.FROM_HTML_MODE_LEGACY
            ).toString().replace(Regex("\\s+"), " ").trim()
            val owner = item.optJSONObject("owner")
                ?: item.optJSONObject("upper")
                ?: JSONObject()
            val author = item.optString("author")
                .ifBlank { item.optString("author_name") }
                .ifBlank { owner.optString("name") }
                .ifBlank { fallbackDetail }
            val cover = item.optString("pic")
                .ifBlank { item.optString("cover") }
                .ifBlank { item.optString("cover_url") }
            val duration = item.optLong("duration")
            val durationText = item.optString("length").takeIf { it.isNotBlank() }
            val detail = when {
                item.optString("index_show").isNotBlank() -> item.optString("index_show")
                item.optString("show_title").isNotBlank() -> item.optString("show_title")
                durationText != null -> durationText
                duration > 0L -> formatSearchDuration(duration.toInt())
                else -> fallbackDetail
            }
            BiliAccountLibraryItem(
                url = url,
                title = title.ifBlank { "B站视频" },
                author = author,
                coverUrl = BiliCoverUrlPolicy.normalize(cover),
                detail = detail
            )
        }

    private fun formatSearchDuration(seconds: Int): String {
        val safe = seconds.coerceAtLeast(0)
        return "%02d:%02d:%02d".format(
            Locale.US,
            safe / 3600,
            safe % 3600 / 60,
            safe % 60
        )
    }

    /** Fetch a public cover image for search result cards. */
    fun fetchCover(url: String?): Bitmap? = downloadCover(url)

    fun getPlaySource(video: BiliVideo, requestedQuality: Int): BiliPlaySource {
        // DASH is the preferred format. Older videos may expose only the
        // legacy DURL response, which requires fnval=0 rather than fnval=4048.
        val dashAttempt = runCatching {
            checkedData(getPlayInfoWithFallback(video.bvid, video.aid, video.cid, requestedQuality))
        }
        val dashData = dashAttempt.getOrNull()
        var dashQualityUnavailable = false

        if (dashData != null) {
            val dash = dashData.optJSONObject("dash")
            if (dash != null) {
                val videos = dash.optJSONArray("video") ?: JSONArray()
                val audios = dash.optJSONArray("audio") ?: JSONArray()
                val videoCandidates = (0 until videos.length()).mapNotNull { videos.optJSONObject(it) }
                val selectedIndex = BiliQualitySelector.chooseIndex(
                    videoCandidates.map { it.optInt("id") },
                    videoCandidates.map { it.optString("codecs") },
                    requestedQuality
                )
                if (selectedIndex != null) {
                    val videoItem = videoCandidates[selectedIndex]
                    val audioCandidates = (0 until audios.length()).mapNotNull { audios.optJSONObject(it) }
                    val audioItem = audioCandidates.firstOrNull {
                        it.optString("codecs").contains("mp4a", ignoreCase = true)
                    } ?: audioCandidates.maxByOrNull { it.optInt("id") }

                    val videoUrls = streamUrls(videoItem)
                    val audioUrls = audioItem?.let { streamUrls(it) }.orEmpty()
                    if (videoUrls.isNotEmpty()) {
                        return BiliPlaySource(
                            videoUrls, audioUrls, emptyList(),
                            "dash", videoItem.optString("mimeType"),
                            audioItem?.optString("mimeType").orEmpty()
                        )
                    }
                } else {
                    dashQualityUnavailable = videoCandidates.isNotEmpty()
                }
            }

            directPlaySource(
                dashData,
                defaultFormat = "mp4",
                requestedQuality = requestedQuality
            )?.let { return it }
        }

        // Do not strand older multi-part FLV videos merely because a DASH
        // response exists but contains no usable video track or no durl field.
        val legacyAttempt = runCatching {
            checkedData(getPlayInfoWithFallback(video.bvid, video.aid, video.cid, requestedQuality, fnval = "0"))
        }
        val legacyData = legacyAttempt.getOrNull()
        if (legacyData != null) {
            directPlaySource(
                legacyData,
                defaultFormat = "flv",
                requestedQuality = requestedQuality
            )?.let { return it }
        }

        if (dashQualityUnavailable) {
            throw IOException("B站没有返回所选画质或更低画质，且旧式 DURL 接口也没有可用视频流")
        }
        dashAttempt.exceptionOrNull()?.let { throw it }
        legacyAttempt.exceptionOrNull()?.let { throw it }
        throw IOException("B站没有返回可下载的音视频流；可能需要登录或该视频限制下载")
    }

    private fun directPlaySource(
        data: JSONObject,
        defaultFormat: String,
        requestedQuality: Int
    ): BiliPlaySource? {
        val durl = data.optJSONArray("durl") ?: return null
        val returnedQuality = data.optInt("quality", 0)
        if (!BiliDirectQualityPolicy.canUse(requestedQuality, returnedQuality)) return null
        val direct = BiliDurlParser.parse(durl)
        if (direct.isEmpty()) return null
        return BiliPlaySource(
            emptyList(),
            emptyList(),
            direct,
            data.optString("format").ifBlank { defaultFormat },
            "video/mp4",
            ""
        )
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
                setRequestProperty("Cookie", SecureCookieStore.normalizeCookieHeader(cookieOverride))
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
                setRequestProperty("Cookie", SecureCookieStore.normalizeCookieHeader(cookieOverride))
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

    private fun getPlayInfo(
        bvid: String?,
        aid: Long,
        cid: Long,
        quality: Int,
        fnval: String = "4048"
    ): JSONObject {
        // Capture the credential once so the preview flag and Cookie header
        // cannot disagree if the session changes while a request is running.
        val sessionCookie = if (sessionCookieRejected) {
            null
        } else {
            cookieProvider()?.takeIf { it.isNotBlank() }
        }

        fun request(cookie: String?): JSONObject {
            val parameters = BiliPlayUrlRequestPolicy.createParameters(
                bvid = bvid,
                aid = aid,
                cid = cid,
                quality = quality,
                hasSessionCookie = !cookie.isNullOrBlank(),
                fnval = fnval
            )
            // try_look must be included before WBI signing for anonymous requests.
            val signedQuery = signWbi(parameters)
            return requestJson(
                "https://api.bilibili.com/x/player/wbi/playurl?$signedQuery",
                cookie
            )
        }

        val response = request(sessionCookie)
        if (!BiliPlayUrlRequestPolicy.shouldRetryWithoutSession(
                response.optInt("code", 0),
                hadSessionCookie = !sessionCookie.isNullOrBlank()
            )
        ) {
            return response
        }

        // The app may have started before restoreSession() finished validating
        // a persisted cookie. Remember this rejection for the lifetime of this
        // repository so each lower-quality retry does not resend the same stale cookie.
        sessionCookieRejected = true
        return request(null)
    }

    private fun getPlayInfoWithFallback(
        bvid: String?,
        aid: Long,
        cid: Long,
        requestedQuality: Int,
        fnval: String = "4048"
    ): JSONObject {
        var lastLoginError: JSONObject? = null
        for (quality in BiliPlayUrlRequestPolicy.qualityFallbacks(requestedQuality)) {
            val response = getPlayInfo(bvid, aid, cid, quality, fnval)
            if (response.optInt("code", 0) != -101) return response
            lastLoginError = response
        }
        // Preserve Bilibili's final response so checkedData() can display the
        // real access restriction rather than pretending a lower tier exists.
        return lastLoginError ?: getPlayInfo(bvid, aid, cid, requestedQuality, fnval)
    }

    private fun getAvailableQualities(bvid: String?, aid: Long, cid: Long): List<BiliQuality> {
        val dash = runCatching { getPlayInfo(bvid, aid, cid, 0) }.getOrNull()
        dash?.let { response ->
            val qualities = runCatching { parseQualities(response) }.getOrDefault(emptyList())
            if (qualities.isNotEmpty()) return qualities
        }

        val legacy = runCatching { getPlayInfo(bvid, aid, cid, 0, fnval = "0") }.getOrNull()
        legacy?.let { response ->
            val qualities = runCatching { parseQualities(response) }.getOrDefault(emptyList())
            if (qualities.isNotEmpty()) return qualities
        }

        // Do not turn a public metadata parse into a login wall merely because
        // Bilibili withholds the quality list. The user can still try download;
        // the worker fetches fresh playurl data when the task actually runs.
        return emptyList()
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
            // Legacy fnval=0 playurl responses may only expose the selected
            // direct-stream quality at the top level.
            val actualQuality = data.optInt("quality")
            if (actualQuality > 0) labels[actualQuality] = qualityLabel(actualQuality)
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
        // The anonymous nav response can be code=-101 while still containing
        // data.wbi_img. Those public keys are not tied to the login session;
        // do not route this response through checkedData(), which rejects every
        // non-zero code before the WBI keys can be read.
        val apiCode = root.optInt("code", 0)
        val image = root.optJSONObject("data")?.optJSONObject("wbi_img")
        mixinKeyCache = BiliWbiKeyPolicy.createMixinKey(
            apiCode = apiCode,
            imgUrl = image?.optString("img_url"),
            subUrl = image?.optString("sub_url")
        )
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
            val message = if (code == -101) {
                "账号未登录。公开视频可以匿名解析；此视频或当前画质可能需要 B站账号授权"
            } else {
                root.optString("message").ifBlank { "未知错误" }
            }
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
        val normalized = BiliCoverUrlPolicy.normalize(url) ?: return null
        val referers = listOf("https://www.bilibili.com/", "https://m.bilibili.com/")
        for (referer in referers) {
            val connection = runCatching {
                (URL(normalized).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout = 15_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", userAgent)
                    setRequestProperty("Referer", referer)
                    setRequestProperty(
                        "Accept",
                        "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8"
                    )
                    setRequestProperty("Accept-Encoding", "identity")
                    setRequestProperty("Connection", "close")
                }
            }.getOrNull() ?: continue
            try {
                if (connection.responseCode !in 200..299) continue
                val bytes = connection.inputStream.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(16 * 1024)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > 8 * 1024 * 1024) throw IOException("封面图片超过大小限制")
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) continue
                var sampleSize = 1
                while (bounds.outWidth / sampleSize > 960 ||
                    bounds.outHeight / sampleSize > 720
                ) {
                    sampleSize *= 2
                }
                val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.let { return it }
            } catch (_: Exception) {
                // Retry the same CDN image with the mobile web Referer below.
            } finally {
                connection.disconnect()
            }
        }
        return null
    }

    private fun requestJson(url: String, cookieOverride: String? = cookieProvider()): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 25_000
            instanceFollowRedirects = true
            BiliCdnRequestPolicy.configure(this, cookieOverride)
            // BVD uses okhttp/4.12.0 for view metadata, but a browser UA for playurl.
            setRequestProperty("User-Agent", BiliApiRequestPolicy.userAgent(url))
            setRequestProperty("Accept", "application/json, text/plain, */*")
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