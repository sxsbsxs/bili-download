package com.example.bilidl

import androidx.fragment.app.FragmentActivity
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.sxsbsxs.bilivdl.ui.BiliVdlAccount
import com.sxsbsxs.bilivdl.ui.BiliVdlDownloadTask
import com.sxsbsxs.bilivdl.ui.BiliVdlUiHost
import com.sxsbsxs.bilivdl.ui.BiliVdlVideo
import com.sxsbsxs.bilivdl.ui.HomeFragment
import com.sxsbsxs.bilivdl.ui.DownloadsFragment
import com.sxsbsxs.bilivdl.ui.ProfileFragment
import com.sxsbsxs.bilivdl.ui.DiscoverFragment
import com.sxsbsxs.bilivdl.ui.CommentAdapter
import com.sxsbsxs.bilivdl.ui.BiliVdlComment
import com.sxsbsxs.bilivdl.ui.BiliVdlDanmaku
import com.sxsbsxs.bilivdl.ui.BiliVdlSettings
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.common.util.UnstableApi
import android.app.AlertDialog
import android.app.ProgressDialog
import android.os.Build
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import java.io.File
import android.content.ClipboardManager
import android.content.ClipData
import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.util.LruCache
import android.content.Intent
import android.net.Uri
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.io.IOException
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.bumptech.glide.Glide
import org.json.JSONObject
import com.example.bilidl.data.local.entity.DownloadTaskEntity
import com.example.bilidl.data.local.entity.DownloadTaskStatus
import com.example.bilidl.data.repository.BiliDownloadTaskRepository
import com.example.bilidl.data.repository.DownloadTaskRecoveryAction
import com.example.bilidl.data.repository.DownloadTaskRecoveryPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/*
 * ===== 给后端开发者（GPT）的说明 =====
 * 本文件只负责界面。网络/解析/下载/登录逻辑请写在别的文件里，然后在下面
 * “交给后端实现”区域的函数中调用。所有 public 函数可在任意线程调用。
 * 当前文件仅包含 UI；解析、下载、扫码登录和文件访问仍需由业务层实现。
 *
 * 首页：   showVideo(title, uploader, cover, meta) / showError(msg)
 * 下载页： addDownload(id,title,cover) / updateDownload(id,progress,info)
 *          setDownloadState(id,state,info) / finishDownload(id,sizeText)
 *          failDownload(id,reason) / removeDownload(id)
 *          状态常量 ST_DOWNLOADING / ST_WAITING / ST_PAUSED / ST_FAILED / ST_DONE
 * 登录页： showQrCode(bitmap) / setQrStatus(msg,isError) / showQrExpired()
 *          loginSuccess(name,avatar,vip) / loginFailed(msg) / logoutDone()
 * 设置：   值保存在 SharedPreferences("ui")，键：quality(Int 0..4 =自动/1080/720/480/360)
 *          concurrent(Int 0..3 =1/2/3/5) wifi_only danmaku audio_only notify(Boolean)
 *          变化时会回调 onSettingChanged(key, value)
 */
@OptIn(UnstableApi::class)
class MainActivity : FragmentActivity(), BiliVdlUiHost {

    companion object {
        private const val DOWNLOAD_TAG = "bilidl-download"
        private const val APP_REPOSITORY_URL = "https://github.com/sxsbsxs/bili-download"
        private const val APP_RELEASES_URL = "https://github.com/sxsbsxs/bili-download/releases"
        private const val PREF_PENDING_UPDATE_FILE = "pending_update_file"
        const val ST_DOWNLOADING = DownloadTaskStatus.RUNNING
        const val ST_WAITING = DownloadTaskStatus.QUEUED
        const val ST_PAUSED = DownloadTaskStatus.PAUSED
        const val ST_FAILED = DownloadTaskStatus.FAILED
        const val ST_DONE = DownloadTaskStatus.COMPLETED
    }

    // ===================== 搜索、详情与播放 =====================
    private fun onParseClicked(text: String) {
        searchRequestToken++
        searchLoading = false
        detailLoadToken++
        setBusy(parseBtn, true)
        parseBtn.text = "正在打开…"
        backgroundExecutor.execute {
            try {
                val video = BiliRepository { SecureCookieStore.loadCookieHeader(this) ?: SecureCookieStore.load(this) }.inspect(text)
                runOnUiThread {
                    setBusy(parseBtn, false)
                    parseBtn.text = "搜索"
                    parsedVideo = video
                    selectedQualityId = preferredQualityId(video.qualities)
                    showVideoDetail(video, null)
                }
            } catch (error: Exception) {
                showError(error.message ?: "打开视频失败，请检查网络后重试")
            }
        }
    }

    private fun runHomeSearch() {
        val query = input.text.toString().trim()
        if (query.isBlank()) {
            say("输入视频标题、UP主或关键词开始搜索", sub)
            input.requestFocus()
            return
        }
        val directTarget = normalizeBiliInput(query)
        if (directTarget != null) {
            onParseClicked(directTarget)
            return
        }
        startVideoSearch(query, true)
    }

    private fun loadHomeFeed(reset: Boolean) {
        if (!reset && searchLoading) return
        if (reset) {
            searchRequestToken++
            searchLoading = false
            setBusy(parseBtn, false)
            parseBtn.text = "搜索"
            searchKeyword = ""
            searchPage = 1
            searchSortContainer.visibility = View.GONE
            feedSectionTitle.text = "热门推荐"
            searchResults.clear()
            canLoadMoreSearch = true
            renderSearchResults()
        } else if (!canLoadMoreSearch) {
            return
        }
        val requestedPage = if (reset) 1 else searchPage + 1
        searchPage = requestedPage
        searchLoading = true
        val requestToken = ++searchRequestToken
        say(if (reset) "正在加载热门视频…" else "正在加载更多热门视频…", sub)
        renderSearchResults()
        backgroundExecutor.execute {
            try {
                val results = BiliRepository { SecureCookieStore.loadCookieHeader(this) ?: SecureCookieStore.load(this) }
                    .getPopularVideos(requestedPage, 20)
                runOnUiThread {
                    if (requestToken != searchRequestToken || isFinishing) return@runOnUiThread
                    searchLoading = false
                    if (reset) searchResults.clear()
                    val known = searchResults.mapTo(HashSet()) { it.bvid }
                    results.filter { known.add(it.bvid) }.forEach(searchResults::add)
                    canLoadMoreSearch = results.size >= 20
                    renderSearchResults()
                    say("热门视频 · " + searchResults.size + " 个", sub)
                }
            } catch (error: Exception) {
                runOnUiThread {
                    if (requestToken != searchRequestToken || isFinishing) return@runOnUiThread
                    searchLoading = false
                    canLoadMoreSearch = false
                    say(error.message ?: "热门视频加载失败", err)
                    renderSearchResults()
                }
            }
        }
    }
    private fun startVideoSearch(query: String, reset: Boolean) {
        if (!reset && searchLoading) return
        if (reset) {
            searchRequestToken++
            searchLoading = false
            searchKeyword = query
            searchPage = 1
            searchSortContainer.visibility = View.VISIBLE
            feedSectionTitle.text = "搜索结果"
            searchResults.clear()
            canLoadMoreSearch = true
            renderSearchResults()
        } else if (!canLoadMoreSearch || searchKeyword.isBlank()) {
            return
        }

        val requestedPage = if (reset) 1 else searchPage + 1
        searchPage = requestedPage
        searchLoading = true
        val requestToken = ++searchRequestToken
        setBusy(parseBtn, true)
        parseBtn.text = "搜索中…"
        say(if (reset) "正在搜索 B 站视频…" else "正在加载更多视频…", sub)

        backgroundExecutor.execute {
            try {
                val results = BiliRepository { SecureCookieStore.loadCookieHeader(this) ?: SecureCookieStore.load(this) }
                    .searchVideos(query, requestedPage, searchOrder)
                runOnUiThread {
                    if (requestToken != searchRequestToken || isFinishing) return@runOnUiThread
                    searchLoading = false
                    setBusy(parseBtn, false)
                    parseBtn.text = "搜索"
                    if (reset) searchResults.clear()
                    val known = searchResults.mapTo(HashSet()) { it.bvid }
                    results.filter { known.add(it.bvid) }.forEach(searchResults::add)
                    canLoadMoreSearch = results.size >= 20
                    renderSearchResults()
                    if (searchResults.isEmpty()) {
                        say("没有找到相关视频，换个关键词试试", sub)
                    } else {
                        say("找到 " + searchResults.size + " 个视频", sub)
                    }
                }
            } catch (error: Exception) {
                runOnUiThread {
                    if (requestToken != searchRequestToken || isFinishing) return@runOnUiThread
                    searchLoading = false
                    setBusy(parseBtn, false)
                    parseBtn.text = "搜索"
                    canLoadMoreSearch = false
                    say(error.message ?: "搜索失败，请稍后重试", err)
                    renderSearchResults()
                }
            }
        }
    }

    private fun renderSearchResults() {
        if (!::resultGrid.isInitialized) return
        resultGrid.removeAllViews()
        if (searchResults.isEmpty()) {
            resultGrid.addView(TextView(this).apply {
                text = if (searchLoading) "正在加载视频…" else if (searchKeyword.isBlank()) "正在准备热门视频…" else "没有找到视频，换个关键词试试"
                textSize = 13f
                setTextColor(sub)
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(32), dp(12), dp(32))
            }, LinearLayout.LayoutParams(match, wrap))
            loadMoreButton.visibility = View.GONE
            return
        }

        searchResults.chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
            }
            pair.forEachIndexed { index, item ->
                val params = LinearLayout.LayoutParams(0, wrap, 1f).apply {
                    if (index == 0) rightMargin = dp(6) else leftMargin = dp(6)
                }
                row.addView(buildSearchCard(item), params)
            }
            if (pair.size == 1) {
                row.addView(View(this), LinearLayout.LayoutParams(0, wrap, 1f).apply {
                    leftMargin = dp(6)
                })
            }
            resultGrid.addView(row, LinearLayout.LayoutParams(match, wrap).apply {
                bottomMargin = dp(12)
            })
        }
        loadMoreButton.visibility = if (canLoadMoreSearch) View.VISIBLE else View.GONE
        loadMoreButton.text = if (searchLoading) "正在加载更多…" else "继续上滑，自动加载更多"
        loadMoreButton.isEnabled = false
    }

    private fun buildSearchCard(item: BiliSearchVideo): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(Color.WHITE, 13)
            clipToOutline = true
            elevation = dp(1).toFloat()
        }
        val imageFrame = FrameLayout(this)
        val cover = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = shape(Color.parseColor("#E8EAF0"), 0)
            tag = item.coverUrl
        }
        imageFrame.addView(cover, FrameLayout.LayoutParams(match, dp(100)))
        val duration = TextView(this).apply {
            text = item.duration.ifBlank { "--:--" }
            textSize = 10f
            setTextColor(Color.WHITE)
            setPadding(dp(5), dp(2), dp(5), dp(2))
            background = shape(Color.parseColor("#B8000000"), 5)
        }
        imageFrame.addView(duration, FrameLayout.LayoutParams(wrap, wrap, Gravity.END or Gravity.BOTTOM).apply {
            rightMargin = dp(6)
            bottomMargin = dp(6)
        })
        root.addView(imageFrame, LinearLayout.LayoutParams(match, dp(100)))

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(9), dp(8), dp(9), dp(10))
        }
        body.addView(TextView(this).apply {
            text = item.title
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ink)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.08f)
        }, LinearLayout.LayoutParams(match, wrap))
        body.addView(TextView(this).apply {
            text = item.author
            textSize = 11f
            setTextColor(sub)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(6), 0, 0)
        }, LinearLayout.LayoutParams(match, wrap))
        body.addView(TextView(this).apply {
            text = "播放 " + formatCount(item.viewCount) + "  ·  弹幕 " + formatCount(item.danmakuCount)
            textSize = 10f
            setTextColor(sub)
            maxLines = 1
            setPadding(0, dp(3), 0, 0)
        }, LinearLayout.LayoutParams(match, wrap))
        root.addView(body, LinearLayout.LayoutParams(match, wrap))
        root.setOnClickListener { onSearchItemClicked(item) }
        val coverUrl = item.coverUrl
        if (!coverUrl.isNullOrBlank()) {
            val cached = coverCache.get(coverUrl)
            if (cached != null) {
                cover.setImageBitmap(cached)
            } else if (loadingCovers.add(coverUrl)) {
                imageExecutor.execute {
                    val bitmap = runCatching {
                        BiliRepository().loadCoverImage(coverUrl)
                    }.getOrNull()
                    loadingCovers.remove(coverUrl)
                    if (bitmap != null) {
                        coverCache.put(coverUrl, bitmap)
                        runOnUiThread {
                            if (!isFinishing && cover.tag == coverUrl) cover.setImageBitmap(bitmap)
                        }
                    }
                }
            }
        }
        return root
    }

    private fun onSearchItemClicked(item: BiliSearchVideo) {
        searchRequestToken++
        searchLoading = false
        val requestToken = ++detailLoadToken
        say("正在加载视频详情…", sub)
        backgroundExecutor.execute {
            try {
                val target = if (item.bvid.startsWith("av")) {
                    "https://www.bilibili.com/video/" + item.bvid
                } else {
                    "https://www.bilibili.com/video/" + item.bvid
                }
                val video = BiliRepository { SecureCookieStore.loadCookieHeader(this) ?: SecureCookieStore.load(this) }.inspect(target)
                runOnUiThread {
                    if (requestToken != detailLoadToken || isFinishing) return@runOnUiThread
                    setBusy(parseBtn, false)
                    parseBtn.text = "搜索"
                    showVideoDetail(video, item)
                }
            } catch (error: Exception) {
                runOnUiThread {
                    if (requestToken != detailLoadToken || isFinishing) return@runOnUiThread
                    setBusy(parseBtn, false)
                    parseBtn.text = "搜索"
                    say(error.message ?: "视频详情加载失败", err)
                }
            }
        }
    }

    private fun showVideoDetail(video: BiliVideo, searchItem: BiliSearchVideo?) {
        say("", sub)
        parsedVideo = video
        selectedQualityId = preferredQualityId(video.qualities)
        detailSearchItem = searchItem
        commentsRequestToken++
        commentsLoading = false
        commentsPage = 1
        commentsHasMore = false
        commentsTotalCount = 0L
        detailComments.clear()
        danmakuRequestToken++
        currentPlayer?.release()
        currentPlayer = null
        detailPage?.let { rootFrame.removeView(it) }
        mainContainer.visibility = View.GONE
        val page = buildVideoDetailPage(video, searchItem)
        detailPage = page
        rootFrame.addView(page, FrameLayout.LayoutParams(match, match))
        loadVideoDanmaku(video)
        loadVideoComments(video, true)
        loadRelatedVideos(video)
        val playerView = detailPlayerView
        val statusView = detailPlaybackStatus
        if (playerView != null && statusView != null) {
            startPlayback(video, playerView, statusView, true)
        }
    }

    private fun buildVideoDetailPage(video: BiliVideo, searchItem: BiliSearchVideo?): View {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F5F6F8"))
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(4), dp(18), dp(4))
            setBackgroundColor(Color.WHITE)
        }
        toolbar.addView(TextView(this).apply {
            text = "‹"
            textSize = 32f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#181A1F"))
            setOnClickListener { closeVideoDetail() }
        }, LinearLayout.LayoutParams(dp(44), dp(48)))
        toolbar.addView(TextView(this).apply {
            text = "视频详情"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, wrap, 1f))
        page.addView(toolbar, LinearLayout.LayoutParams(match, dp(52)))

        val content = layoutInflater.inflate(
            com.sxsbsxs.bilivdl.ui.R.layout.activity_video_player,
            page,
            false
        )
        page.addView(content, LinearLayout.LayoutParams(match, 0, 1f))

        val playerView = content.findViewById<PlayerView>(
            com.sxsbsxs.bilivdl.ui.R.id.player_view
        ).apply {
            useController = true
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setBackgroundColor(Color.BLACK)
        }
        detailPlayerView = playerView

        val overlay = content.findViewById<com.sxsbsxs.bilivdl.ui.DanmakuOverlayView>(
            com.sxsbsxs.bilivdl.ui.R.id.danmaku_overlay
        ).apply {
            positionProvider = { currentPlayer?.currentPosition ?: 0L }
            playingProvider = { currentPlayer?.isPlaying == true }
            danmakuEnabled = detailDanmakuVisible
        }
        detailDanmakuOverlay = overlay

        content.findViewById<TextView>(com.sxsbsxs.bilivdl.ui.R.id.player_title).text = video.title
        content.findViewById<TextView>(com.sxsbsxs.bilivdl.ui.R.id.owner_name).text =
            video.uploader.ifBlank { "B站创作者" }
        val avatar = content.findViewById<ImageView>(
            com.sxsbsxs.bilivdl.ui.R.id.owner_avatar
        )
        val avatarUrl = video.ownerAvatarUrl.orEmpty()
        if (avatarUrl.isNotBlank()) {
            Glide.with(this)
                .load(avatarUrl)
                .placeholder(com.sxsbsxs.bilivdl.ui.R.drawable.bg_empty_card)
                .error(com.sxsbsxs.bilivdl.ui.R.drawable.bg_empty_card)
                .circleCrop()
                .into(avatar)
        } else {
            avatar.setImageResource(com.sxsbsxs.bilivdl.ui.R.drawable.bg_empty_card)
        }

        content.findViewById<TextView>(com.sxsbsxs.bilivdl.ui.R.id.video_stats).text =
            formatCount(video.viewCount) + " 播放  ·  " +
                formatCount(video.danmakuCount) + " 弹幕  ·  " +
                formatCount(video.likeCount) + " 点赞  ·  " +
                formatCount(video.commentCount) + " 评论"

        content.findViewById<TextView>(com.sxsbsxs.bilivdl.ui.R.id.video_description).text =
            buildString {
                if (video.categoryName.isNotBlank()) append("分区：").append(video.categoryName).append('\n')
                val publishTime = if (video.publishTime > 0L) video.publishTime else searchItem?.publishTime ?: 0L
                if (publishTime > 0L) append("投稿：").append(formatPublishDate(publishTime)).append('\n')
                if (video.bvid.isNullOrBlank().not()) append("BV号：").append(video.bvid).append('\n')
                append(video.description.ifBlank { "暂无简介" })
                if (video.tags.isNotEmpty()) {
                    append("\n\n标签：")
                    append(video.tags.joinToString("  ·  "))
                }
            }

        detailPlaybackStatus = content.findViewById(
            com.sxsbsxs.bilivdl.ui.R.id.player_status
        )
        detailPlaybackStatus?.text = "正在获取播放地址…"

        val qualityButton = content.findViewById<MaterialButton>(
            com.sxsbsxs.bilivdl.ui.R.id.quality_button
        )
        detailQualityLabel = qualityButton
        qualityButton.text = qualityLabelForSelection(video)
        qualityButton.setOnClickListener { showAvailableQualities() }

        val speedButton = content.findViewById<MaterialButton>(
            com.sxsbsxs.bilivdl.ui.R.id.playback_speed_button
        )
        speedButton.text = "倍速 1.0x"
        speedButton.setOnClickListener {
            val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
            val labels = speeds.map { it.toString() + "x" }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("播放速度")
                .setSingleChoiceItems(labels, speeds.indexOf(currentPlayer?.playbackParameters?.speed ?: 1f).coerceAtLeast(0)) { dialog, which ->
                    val speed = speeds[which]
                    currentPlayer?.playbackParameters = PlaybackParameters(speed)
                    speedButton.text = "倍速 " + speed + "x"
                    dialog.dismiss()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        content.findViewById<MaterialButton>(
            com.sxsbsxs.bilivdl.ui.R.id.player_download
        ).setOnClickListener { onDownloadClicked() }

        val partButton = content.findViewById<MaterialButton>(
            com.sxsbsxs.bilivdl.ui.R.id.part_button
        )
        partButton.visibility = if (video.pages.size > 1) View.VISIBLE else View.GONE
        partButton.text = if (video.pages.size > 1) "选择分P（共 " + video.pages.size + " P）" else "单P视频"
        partButton.setOnClickListener { showAvailablePages() }

        val danmakuButton = content.findViewById<MaterialButton>(
            com.sxsbsxs.bilivdl.ui.R.id.danmaku_button
        )
        fun updateDanmakuButton() {
            danmakuButton.text = if (detailDanmakuVisible) "弹幕：开" else "弹幕：关"
        }
        updateDanmakuButton()
        danmakuButton.setOnClickListener {
            detailDanmakuVisible = !detailDanmakuVisible
            detailDanmakuOverlay?.danmakuEnabled = detailDanmakuVisible
            updateDanmakuButton()
        }

        content.findViewById<MaterialButton>(
            com.sxsbsxs.bilivdl.ui.R.id.more_actions
        ).setOnClickListener { showVideoMoreActions(video) }

        detailLikeButton = content.findViewById(
            com.sxsbsxs.bilivdl.ui.R.id.video_like
        )
        detailLikeButton.text = if (video.userLiked) "已点赞" else "点赞"
        detailLikeButton.setOnClickListener {
            parsedVideo?.let { currentVideo -> performVideoLike(currentVideo) }
        }

        content.findViewById<MaterialButton>(
            com.sxsbsxs.bilivdl.ui.R.id.video_coin
        ).setOnClickListener { showCoinDialog(video) }

        detailFavoriteButton = content.findViewById(
            com.sxsbsxs.bilivdl.ui.R.id.video_favorite
        )
        detailFavoriteButton.text = if (video.userFavorited) "已收藏" else "收藏"
        detailFavoriteButton.setOnClickListener { showFavoriteFolderPicker(video) }

        content.findViewById<MaterialButton>(
            com.sxsbsxs.bilivdl.ui.R.id.video_triple
        ).setOnClickListener { performTripleLike(video) }

        content.findViewById<MaterialButton>(
            com.sxsbsxs.bilivdl.ui.R.id.danmaku_send
        ).setOnClickListener { showDanmakuComposer(video) }

        content.findViewById<TextView>(
            com.sxsbsxs.bilivdl.ui.R.id.download_status
        ).text = "保存位置：Download/BiliDl"

        detailCommentInput = content.findViewById(
            com.sxsbsxs.bilivdl.ui.R.id.comment_input
        )
        detailCommentInput.hint = if (loggedIn) "写下你的评论…" else "登录后参与评论"
        detailCommentSend = content.findViewById(
            com.sxsbsxs.bilivdl.ui.R.id.comment_send
        )
        detailCommentSend.setOnClickListener { sendDetailComment(video) }

        detailCommentsStatus = content.findViewById(
            com.sxsbsxs.bilivdl.ui.R.id.comments_status
        )
        detailCommentSortButton = content.findViewById(
            com.sxsbsxs.bilivdl.ui.R.id.comment_sort
        )
        detailCommentSortButton?.text = if (commentsSort == 2) "按热度" else "按时间"
        detailCommentSortButton?.setOnClickListener {
            commentsSort = if (commentsSort == 2) 0 else 2
            detailCommentSortButton?.text = if (commentsSort == 2) "按热度" else "按时间"
            loadVideoComments(video, true)
        }

        val playerScroll = content as? androidx.core.widget.NestedScrollView
        playerScroll?.setOnScrollChangeListener(
            androidx.core.widget.NestedScrollView.OnScrollChangeListener { scroll, _, y, _, _ ->
                val child = scroll.getChildAt(0) ?: return@OnScrollChangeListener
                if (!commentsLoading && commentsHasMore &&
                    y + scroll.height >= child.measuredHeight - dp(420)
                ) {
                    loadVideoComments(video, false)
                }
            }
        )

        val commentsList = content.findViewById<RecyclerView>(
            com.sxsbsxs.bilivdl.ui.R.id.comments_list
        )
        detailCommentsList = commentsList
        detailCommentsAdapter = CommentAdapter(
            onReplies = { uiComment ->
                detailComments.firstOrNull { it.id == uiComment.id }?.let { comment ->
                    parsedVideo?.let { currentVideo -> showCommentReplyDialog(currentVideo, comment) }
                }
            },
            onLike = { uiComment ->
                detailComments.firstOrNull { it.id == uiComment.id }?.let { comment ->
                    parsedVideo?.let { currentVideo -> performCommentLike(currentVideo, comment) }
                }
            }
        )
        commentsList.layoutManager = LinearLayoutManager(this)
        commentsList.adapter = detailCommentsAdapter
        commentsList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                val manager = recyclerView.layoutManager as? LinearLayoutManager ?: return
                if (!commentsLoading && commentsHasMore &&
                    manager.findLastVisibleItemPosition() >= manager.itemCount - 3
                ) {
                    loadVideoComments(video, false)
                }
            }
        })

        return page
    }

    private fun showVideoMoreActions(video: BiliVideo) {
        val link = "https://www.bilibili.com/video/" +
            (video.bvid?.takeIf { it.isNotBlank() } ?: ("av" + video.aid))
        val options = arrayOf("复制视频链接", "分享视频链接", "复制 BV / AV 号")
        AlertDialog.Builder(this)
            .setTitle("更多操作")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Bilibili video", link))
                        toast("已复制视频链接")
                    }
                    1 -> {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, link)
                        }
                        startActivity(Intent.createChooser(intent, "分享视频"))
                    }
                    2 -> {
                        val id = video.bvid?.takeIf { it.isNotBlank() } ?: ("av" + video.aid)
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Bilibili ID", id))
                        toast("已复制视频编号")
                    }
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun buildCommentsSection(video: BiliVideo): View {
        val section = card()
        detailCommentsTitle = TextView(this).apply {
            text = "评论"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ink)
        }
        section.addView(detailCommentsTitle, LinearLayout.LayoutParams(match, wrap))

        val composer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(8))
        }
        detailCommentInput = EditText(this).apply {
            hint = if (loggedIn) "说点什么…" else "登录后参与评论"
            setHintTextColor(sub)
            setTextColor(ink)
            textSize = 13f
            minLines = 1
            maxLines = 3
            background = shape(field, 12)
            setPadding(dp(12), dp(9), dp(12), dp(9))
        }
        detailCommentSend = chip("发送", true)
        detailCommentSend.setOnClickListener { sendDetailComment(video) }
        composer.addView(detailCommentInput, LinearLayout.LayoutParams(0, wrap, 1f).apply {
            rightMargin = dp(8)
        })
        composer.addView(detailCommentSend, LinearLayout.LayoutParams(wrap, dp(42)))
        section.addView(composer, LinearLayout.LayoutParams(match, wrap))

        detailCommentsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        section.addView(detailCommentsContainer, LinearLayout.LayoutParams(match, wrap))
        detailCommentsMore = TextView(this).apply {
            text = "加载更多评论"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(pink)
            setPadding(dp(10), dp(12), dp(10), dp(8))
            visibility = View.GONE
            setOnClickListener {
                if (!commentsLoading && commentsHasMore) loadVideoComments(video, false)
            }
        }
        section.addView(detailCommentsMore, LinearLayout.LayoutParams(match, wrap))
        return section
    }

    private fun buildRelatedSection(): View {
        val section = card()
        section.addView(TextView(this).apply {
            text = "相关推荐"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ink)
        }, LinearLayout.LayoutParams(match, wrap))
        detailRelatedContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        section.addView(detailRelatedContainer, LinearLayout.LayoutParams(match, wrap).apply {
            topMargin = dp(10)
        })
        return section
    }

    private fun repositoryWithSavedCookie() = BiliRepository {
        SecureCookieStore.loadCookieHeader(this) ?: SecureCookieStore.load(this)
    }

    private fun requireInteractionLogin(): Boolean {
        if (!loggedIn) {
            toast("请先登录 B 站账号")
            showLogin()
            return false
        }
        if (SecureCookieStore.csrf(this).isNullOrBlank()) {
            toast("当前登录凭据缺少 bili_jct，请重新扫码登录后再互动")
            showLogin()
            return false
        }
        return true
    }

    private fun loadVideoDanmaku(video: BiliVideo) {
        val requestToken = ++danmakuRequestToken
        detailDanmakuOverlay?.clear()
        backgroundExecutor.execute {
            try {
                val repository = repositoryWithSavedCookie()
                val items = try {
                    repository.getVideoDanmaku(video)
                } catch (protoError: Exception) {
                    // Retain compatibility with the legacy XML feed if a segment request is rejected.
                    val xml = repository.getDanmakuXml(video.cid)
                    runOnUiThread {
                        if (requestToken == danmakuRequestToken &&
                            detailDanmakuOverlay != null &&
                            parsedVideo?.cid == video.cid
                        ) detailDanmakuOverlay?.setXml(xml)
                    }
                    return@execute
                }
                val xmlFallback = if (items.isEmpty()) {
                    runCatching { repository.getDanmakuXml(video.cid) }.getOrNull()
                } else null
                runOnUiThread {
                    if (requestToken != danmakuRequestToken ||
                        detailDanmakuOverlay == null ||
                        parsedVideo?.cid != video.cid
                    ) return@runOnUiThread
                    when {
                        items.isNotEmpty() -> detailDanmakuOverlay?.setDanmaku(
                            items.map { item ->
                                BiliVdlDanmaku(
                                    text = item.content,
                                    timeMs = item.progressMs,
                                    color = item.color.toInt(),
                                    fontSize = item.fontSize,
                                    mode = item.mode
                                )
                            }
                        )
                        xmlFallback != null -> detailDanmakuOverlay?.setXml(xmlFallback)
                        else -> detailPlaybackStatus?.text = "当前视频暂无可显示的弹幕"
                    }
                }
            } catch (error: Exception) {
                runOnUiThread {
                    if (requestToken == danmakuRequestToken && parsedVideo?.cid == video.cid) {
                        detailPlaybackStatus?.text = error.message?.let { "弹幕暂不可用：$it" }
                            ?: "弹幕暂不可用"
                    }
                }
            }
        }
    }

    private fun showDanmakuComposer(video: BiliVideo) {
        if (!requireInteractionLogin()) return
        val editor = EditText(this).apply {
            hint = "发送一条弹幕（最多 50 个字）"
            setTextColor(ink)
            setHintTextColor(sub)
            textSize = 15f
            minLines = 2
            maxLines = 3
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters = arrayOf(android.text.InputFilter.LengthFilter(50))
            background = shape(field, 12)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val modeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("滚动弹幕", "顶部弹幕", "底部弹幕")
            )
        }
        val colorChoices = listOf(
            "白色" to 0xFFFFFF,
            "黄色" to 0xFFFF00,
            "粉色" to 0xFF79B8,
            "蓝色" to 0x66CCFF,
            "绿色" to 0x66CC66
        )
        val colorSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                colorChoices.map { it.first }
            )
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
            addView(editor, LinearLayout.LayoutParams(match, wrap))
            addView(TextView(this@MainActivity).apply {
                text = "显示方式"
                textSize = 12f
                setTextColor(sub)
                setPadding(0, dp(12), 0, dp(2))
            })
            addView(modeSpinner, LinearLayout.LayoutParams(match, dp(44)))
            addView(TextView(this@MainActivity).apply {
                text = "弹幕颜色"
                textSize = 12f
                setTextColor(sub)
                setPadding(0, dp(8), 0, dp(2))
            })
            addView(colorSpinner, LinearLayout.LayoutParams(match, dp(44)))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("发送弹幕")
            .setView(content)
            .setNegativeButton("取消", null)
            .setPositiveButton("发送", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val message = editor.text?.toString()?.trim().orEmpty()
                if (message.isBlank()) {
                    editor.error = "请输入弹幕内容"
                    return@setOnClickListener
                }
                val modes = listOf(1, 5, 4)
                val selectedMode = modes.getOrElse(modeSpinner.selectedItemPosition) { 1 }
                val selectedColor = colorChoices.getOrElse(colorSpinner.selectedItemPosition) {
                    colorChoices.first()
                }.second
                val progressMs = currentPlayer?.currentPosition ?: 0L
                setBusy(dialog.getButton(AlertDialog.BUTTON_POSITIVE), true)
                backgroundExecutor.execute {
                    try {
                        repositoryWithSavedCookie().sendDanmaku(
                            video = video,
                            text = message,
                            progressMs = progressMs,
                            color = selectedColor,
                            mode = selectedMode
                        )
                        runOnUiThread {
                            if (parsedVideo?.cid == video.cid) {
                                detailDanmakuOverlay?.addLocalDanmaku(
                                    message, selectedColor, selectedMode, progressMs
                                )
                            }
                            toast("弹幕发送成功")
                            dialog.dismiss()
                        }
                    } catch (error: Exception) {
                        runOnUiThread {
                            setBusy(dialog.getButton(AlertDialog.BUTTON_POSITIVE), false)
                            toast(error.message ?: "弹幕发送失败")
                        }
                    }
                }
            }
        }
        dialog.show()
        editor.requestFocus()
    }

    private fun performVideoLike(video: BiliVideo) {
        if (!requireInteractionLogin()) return
        val targetLiked = !video.userLiked
        backgroundExecutor.execute {
            try {
                repositoryWithSavedCookie().setVideoLike(video.aid, targetLiked)
                runOnUiThread {
                    val updated = video.copy(userLiked = targetLiked)
                    parsedVideo = updated
                    detailLikeButton.text = if (targetLiked) "已点赞" else "点赞"
                    detailLikeButton.setTextColor(if (targetLiked) Color.WHITE else sub)
                    detailLikeButton.background = rippleShape(if (targetLiked) pink else field, 18)
                    toast(if (targetLiked) "点赞成功" else "已取消点赞")
                }
            } catch (error: Exception) {
                runOnUiThread { toast(error.message ?: "点赞失败") }
            }
        }
    }

    private fun performTripleLike(video: BiliVideo) {
        if (!requireInteractionLogin()) return
        backgroundExecutor.execute {
            try {
                repositoryWithSavedCookie().tripleLike(video.aid)
                runOnUiThread {
                    parsedVideo = video.copy(userLiked = true)
                    detailLikeButton.text = "已点赞"
                    detailLikeButton.setTextColor(Color.WHITE)
                    detailLikeButton.background = rippleShape(pink, 18)
                    toast("一键三连成功")
                }
            } catch (error: Exception) {
                runOnUiThread { toast(error.message ?: "一键三连失败") }
            }
        }
    }

    private fun showCoinDialog(video: BiliVideo) {
        if (!requireInteractionLogin()) return
        val maxCoins = if (video.copyright == 2) 1 else 2
        val options = if (maxCoins == 1) {
            arrayOf("投 1 个币")
        } else {
            arrayOf("投 1 个币", "投 2 个币")
        }
        AlertDialog.Builder(this)
            .setTitle("给 UP 主投币")
            .setItems(options) { _, index ->
                val coins = index + 1
                backgroundExecutor.execute {
                    try {
                        repositoryWithSavedCookie().addVideoCoin(video.aid, coins)
                        runOnUiThread {
                            if (parsedVideo?.aid == video.aid) {
                                val latest = parsedVideo ?: video
                                parsedVideo = latest.copy(
                                    coinCount = latest.coinCount + coins,
                                    userCoin = (latest.userCoin + coins).coerceAtMost(2)
                                )
                            }
                            toast("投币成功")
                        }
                    } catch (error: Exception) {
                        runOnUiThread { toast(error.message ?: "投币失败") }
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showFavoriteFolderPicker(video: BiliVideo) {
        if (!requireInteractionLogin()) return
        toast("正在读取收藏夹状态…")
        backgroundExecutor.execute {
            try {
                val folders = repositoryWithSavedCookie().getFavoriteFolders(video.aid)
                runOnUiThread {
                    if (detailPage == null || parsedVideo?.aid != video.aid) return@runOnUiThread
                    if (folders.isEmpty()) {
                        toast("没有可用收藏夹，请先在 B 站创建收藏夹")
                        return@runOnUiThread
                    }
                    val isAlreadyFavorited = folders.any { it.favState }
                    if (parsedVideo?.aid == video.aid) {
                        parsedVideo = video.copy(userFavorited = isAlreadyFavorited)
                        detailFavoriteButton.text = if (isAlreadyFavorited) "已收藏" else "收藏"
                        detailFavoriteButton.setTextColor(if (isAlreadyFavorited) Color.WHITE else sub)
                        detailFavoriteButton.background =
                            rippleShape(if (isAlreadyFavorited) pink else field, 18)
                    }
                    val content = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(18), dp(4), dp(18), dp(4))
                    }
                    content.addView(TextView(this).apply {
                        text = "可同时勾选多个收藏夹；取消勾选已收藏的项目即可移出。"
                        textSize = 12f
                        setTextColor(sub)
                        setPadding(0, 0, 0, dp(8))
                    })
                    val list = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                    }
                    val checks = folders.map { folder ->
                        CheckBox(this).apply {
                            text = folder.title + "  ·  " + folder.mediaCount + " 个视频"
                            textSize = 13f
                            setTextColor(ink)
                            isChecked = folder.favState
                            setPadding(dp(4), dp(5), dp(4), dp(5))
                            list.addView(this, LinearLayout.LayoutParams(match, wrap))
                        }
                    }
                    val scroll = ScrollView(this).apply {
                        isVerticalScrollBarEnabled = true
                        addView(list, FrameLayout.LayoutParams(match, wrap))
                    }
                    content.addView(scroll, LinearLayout.LayoutParams(match, dp(300)))
                    val dialog = AlertDialog.Builder(this)
                        .setTitle("收藏夹")
                        .setView(content)
                        .setNegativeButton("取消", null)
                        .setPositiveButton("保存", null)
                        .create()
                    dialog.setOnShowListener {
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                            val selected = folders.filterIndexed { index, _ ->
                                checks[index].isChecked
                            }
                            val selectedIds = selected.map { it.id }.toSet()
                            val addIds = folders.filter {
                                !it.favState && it.id in selectedIds
                            }.map { it.id }
                            val removeIds = folders.filter {
                                it.favState && it.id !in selectedIds
                            }.map { it.id }
                            if (addIds.isEmpty() && removeIds.isEmpty()) {
                                dialog.dismiss()
                                return@setOnClickListener
                            }
                            val saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                            setBusy(saveButton, true)
                            backgroundExecutor.execute {
                                try {
                                    repositoryWithSavedCookie().updateVideoFavoriteFolders(
                                        video.aid, addIds, removeIds
                                    )
                                    runOnUiThread {
                                        if (parsedVideo?.aid == video.aid) {
                                            val isFavorited = selectedIds.isNotEmpty()
                                            parsedVideo = video.copy(userFavorited = isFavorited)
                                            detailFavoriteButton.text =
                                                if (isFavorited) "已收藏" else "收藏"
                                            detailFavoriteButton.setTextColor(
                                                if (isFavorited) Color.WHITE else sub
                                            )
                                            detailFavoriteButton.background =
                                                rippleShape(if (isFavorited) pink else field, 18)
                                        }
                                        toast("收藏夹更新成功")
                                        dialog.dismiss()
                                    }
                                } catch (error: Exception) {
                                    runOnUiThread {
                                        setBusy(saveButton, false)
                                        toast(error.message ?: "收藏夹更新失败")
                                    }
                                }
                            }
                        }
                    }
                    dialog.show()
                }
            } catch (error: Exception) {
                runOnUiThread { toast(error.message ?: "读取收藏夹失败") }
            }
        }
    }

    private fun sendDetailComment(video: BiliVideo) {
        if (!requireInteractionLogin()) return
        val message = detailCommentInput.text?.toString()?.trim().orEmpty()
        if (message.isBlank()) {
            detailCommentInput.error = "请输入评论内容"
            return
        }
        setBusy(detailCommentSend, true)
        backgroundExecutor.execute {
            try {
                repositoryWithSavedCookie().sendVideoComment(video.aid, message)
                runOnUiThread {
                    setBusy(detailCommentSend, false)
                    detailCommentInput.setText("")
                    toast("评论发送成功")
                    loadVideoComments(video, true)
                }
            } catch (error: Exception) {
                runOnUiThread {
                    setBusy(detailCommentSend, false)
                    toast(error.message ?: "评论发送失败")
                }
            }
        }
    }

    private fun loadVideoComments(video: BiliVideo, reset: Boolean) {
        if (commentsLoading && !reset) return
        if (reset) {
            commentsRequestToken++
            commentsLoading = false
            detailComments.clear()
            commentsPage = 1
            commentsHasMore = true
            detailCommentsAdapter?.submit(emptyList())
            detailCommentsStatus?.text = "正在加载评论…"
        } else if (!commentsHasMore) {
            return
        }

        val requestToken = ++commentsRequestToken
        val requestedPage = if (reset) 1 else commentsPage
        commentsLoading = true
        detailCommentsStatus?.text =
            if (detailComments.isEmpty()) "正在加载评论…" else "正在加载更多评论…"

        backgroundExecutor.execute {
            try {
                val result = repositoryWithSavedCookie().getVideoComments(
                    aid = video.aid,
                    page = requestedPage,
                    sort = commentsSort
                )
                runOnUiThread {
                    if (requestToken != commentsRequestToken ||
                        detailPage == null ||
                        parsedVideo?.aid != video.aid
                    ) return@runOnUiThread

                    commentsLoading = false
                    val known = detailComments.mapTo(HashSet()) { it.id }
                    result.comments.filter { known.add(it.id) }.forEach(detailComments::add)
                    commentsPage = result.nextPage
                    commentsHasMore = result.hasMore
                    commentsTotalCount = result.totalCount
                    renderDetailComments()
                }
            } catch (error: Exception) {
                runOnUiThread {
                    if (requestToken != commentsRequestToken ||
                        detailPage == null ||
                        parsedVideo?.aid != video.aid
                    ) return@runOnUiThread
                    commentsLoading = false
                    detailCommentsStatus?.text =
                        if (detailComments.isEmpty()) {
                            "评论加载失败：${error.message ?: "网络异常"} · 下拉或重试"
                        } else {
                            "已加载 ${detailComments.size} 条 · 继续上滑加载更多"
                        }
                    if (detailComments.isEmpty()) toast(error.message ?: "评论加载失败")
                }
            }
        }
    }

    private fun renderDetailComments() {
        val mapped = detailComments.map { comment ->
            BiliVdlComment(
                id = comment.id,
                user = comment.userName,
                text = comment.message,
                likes = comment.likeCount,
                replyCount = comment.replyCount,
                liked = comment.likedByMe
            )
        }
        detailCommentsAdapter?.submit(mapped)

        detailCommentsStatus?.text = when {
            commentsLoading && detailComments.isEmpty() -> "正在加载评论…"
            commentsLoading -> "已加载 ${detailComments.size} 条 · 正在加载更多…"
            detailComments.isEmpty() -> "暂无评论"
            commentsHasMore -> "评论 · ${formatCount(commentsTotalCount)} · 已加载 ${detailComments.size} 条，上滑加载更多"
            else -> "评论 · ${formatCount(commentsTotalCount)} · 已显示全部评论"
        }
        detailCommentSortButton?.text = if (commentsSort == 2) "按热度" else "按时间"
    }

    private fun showCommentReplyDialog(video: BiliVideo, rootComment: BiliComment) {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(8))
        }
        body.addView(TextView(this).apply {
            text = rootComment.userName + "  ·  " + formatPublishDate(rootComment.createTime)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(sub)
        })
        body.addView(TextView(this).apply {
            text = rootComment.message
            textSize = 14f
            setTextColor(ink)
            setPadding(0, dp(6), 0, dp(12))
        })
        val repliesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = true
            addView(repliesContainer, FrameLayout.LayoutParams(match, wrap))
        }
        body.addView(scroll, LinearLayout.LayoutParams(match, dp(280)))

        val more = TextView(this).apply {
            text = "加载回复"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(pink)
            setPadding(0, dp(10), 0, dp(8))
        }
        body.addView(more)

        val editor = EditText(this).apply {
            hint = "回复这条评论…"
            textSize = 13f
            setTextColor(ink)
            setHintTextColor(sub)
            minLines = 1
            maxLines = 3
            background = shape(field, 12)
            setPadding(dp(12), dp(9), dp(12), dp(9))
        }
        body.addView(editor, LinearLayout.LayoutParams(match, wrap).apply {
            topMargin = dp(6)
        })
        var targetParentId = rootComment.id
        var currentPage = 1
        var hasMore = true
        var loading = false
        val replyItems = ArrayList<BiliComment>()
        lateinit var dialog: AlertDialog

        fun render() {
            repliesContainer.removeAllViews()
            if (replyItems.isEmpty()) {
                repliesContainer.addView(TextView(this).apply {
                    text = if (loading) "正在加载回复…" else "暂无回复"
                    textSize = 12f
                    setTextColor(sub)
                    setPadding(0, dp(10), 0, dp(10))
                })
            } else {
                replyItems.forEachIndexed { index, reply ->
                    if (index > 0) repliesContainer.addView(View(this).apply {
                        setBackgroundColor(field)
                    }, LinearLayout.LayoutParams(match, dp(1)).apply {
                        topMargin = dp(7)
                        bottomMargin = dp(7)
                    })
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                    }
                    row.addView(TextView(this).apply {
                        text = reply.userName + "  ·  " + formatPublishDate(reply.createTime)
                        textSize = 11f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(ink)
                    })
                    row.addView(TextView(this).apply {
                        text = reply.message
                        textSize = 13f
                        setTextColor(ink)
                        setPadding(0, dp(4), 0, dp(4))
                    })
                    row.addView(chip("回复", false).apply {
                        setOnClickListener {
                            targetParentId = reply.id
                            editor.hint = "回复 " + reply.userName
                            editor.requestFocus()
                        }
                    })
                    repliesContainer.addView(row, LinearLayout.LayoutParams(match, wrap))
                }
            }
            more.visibility = if (hasMore || loading || replyItems.isEmpty()) View.VISIBLE else View.GONE
            more.text = when {
                loading -> "正在加载回复…"
                hasMore -> "加载更多回复"
                replyItems.isEmpty() -> "加载失败，点此重试"
                else -> "已经到底了"
            }
        }

        lateinit var loadReplies: (Boolean) -> Unit
        loadReplies = { reset ->
            if (loading && !reset) {
                Unit
            } else if (!reset && !hasMore) {
                Unit
            } else {
                if (reset) {
                    currentPage = 1
                    hasMore = true
                    replyItems.clear()
                }
                loading = true
                render()
                val requestedPage = currentPage
                backgroundExecutor.execute {
                    try {
                        val result = repositoryWithSavedCookie().getVideoCommentReplies(
                            video.aid, rootComment.id, requestedPage
                        )
                        runOnUiThread {
                            if (dialog.isShowing && parsedVideo?.aid == video.aid) {
                                loading = false
                                val known = replyItems.mapTo(HashSet()) { it.id }
                                result.comments.filter { known.add(it.id) }.forEach(replyItems::add)
                                currentPage = result.nextPage
                                hasMore = result.hasMore
                                render()
                            }
                        }
                    } catch (error: Exception) {
                        runOnUiThread {
                            loading = false
                            hasMore = true
                            render()
                            more.text = "加载失败，点击重试"
                            if (replyItems.isEmpty()) toast(error.message ?: "回复加载失败")
                        }
                    }
                }
            }
        }
        more.setOnClickListener { loadReplies(false) }

        val send = chip("发送回复", true)
        body.addView(send, LinearLayout.LayoutParams(match, dp(42)).apply {
            topMargin = dp(8)
        })
        dialog = AlertDialog.Builder(this)
            .setTitle("评论回复")
            .setView(body)
            .setNegativeButton("关闭", null)
            .create()
        send.setOnClickListener {
            if (!requireInteractionLogin()) return@setOnClickListener
            val message = editor.text?.toString()?.trim().orEmpty()
            if (message.isBlank()) {
                editor.error = "请输入回复内容"
                return@setOnClickListener
            }
            setBusy(send, true)
            backgroundExecutor.execute {
                try {
                    repositoryWithSavedCookie().sendVideoComment(
                        aid = video.aid,
                        message = message,
                        rootId = rootComment.id,
                        parentId = targetParentId
                    )
                    runOnUiThread {
                        setBusy(send, false)
                        editor.setText("")
                        targetParentId = rootComment.id
                        editor.hint = "回复这条评论…"
                        toast("回复发送成功")
                        loadReplies(true)
                    }
                } catch (error: Exception) {
                    runOnUiThread {
                        setBusy(send, false)
                        toast(error.message ?: "回复发送失败")
                    }
                }
            }
        }
        dialog.setOnShowListener {
            dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.92f).toInt(), wrap)
        }
        dialog.show()
        loadReplies(true)
    }

    private fun performCommentLike(video: BiliVideo, comment: BiliComment) {
        if (!requireInteractionLogin()) return
        backgroundExecutor.execute {
            try {
                repositoryWithSavedCookie().likeVideoComment(video.aid, comment.id, !comment.likedByMe)
                runOnUiThread { loadVideoComments(video, true) }
            } catch (error: Exception) {
                runOnUiThread { toast(error.message ?: "评论点赞失败") }
            }
        }
    }

    private fun loadRelatedVideos(video: BiliVideo) {
        if (!::detailRelatedContainer.isInitialized) return
        detailRelatedContainer.removeAllViews()
        backgroundExecutor.execute {
            try {
                val related = repositoryWithSavedCookie().getRelatedVideos(video.aid)
                runOnUiThread {
                    if (detailPage == null || parsedVideo?.aid != video.aid) return@runOnUiThread
                    detailRelatedContainer.removeAllViews()
                    related.filterNot { it.aid == video.aid }.take(8).chunked(2).forEach { pair ->
                        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                        pair.forEachIndexed { index, item ->
                            row.addView(buildSearchCard(item), LinearLayout.LayoutParams(0, wrap, 1f).apply {
                                if (index == 0) rightMargin = dp(6) else leftMargin = dp(6)
                            })
                        }
                        if (pair.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, wrap, 1f))
                        detailRelatedContainer.addView(row, LinearLayout.LayoutParams(match, wrap).apply {
                            bottomMargin = dp(10)
                        })
                    }
                    if (related.isEmpty()) {
                        detailRelatedContainer.addView(TextView(this).apply {
                            text = "暂无相关推荐"
                            textSize = 12f
                            setTextColor(sub)
                        })
                    }
                }
            } catch (_: Exception) {
                runOnUiThread {
                    if (detailPage != null && parsedVideo?.aid == video.aid) {
                        detailRelatedContainer.addView(TextView(this).apply {
                            text = "相关推荐暂不可用"
                            textSize = 12f
                            setTextColor(sub)
                        })
                    }
                }
            }
        }
    }

    private fun xmlEscape(value: String): String {
        return value.replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }

    private fun createDashManifestFile(source: BiliPlaySource, durationSeconds: Int): File? {
        val videoUrl = source.videoUrls.firstOrNull() ?: return null
        val videoInit = source.videoInitializationRange?.takeIf { it.isNotBlank() } ?: return null
        val videoIndex = source.videoIndexRange?.takeIf { it.isNotBlank() } ?: return null
        val audioUrl = source.audioUrls.firstOrNull()
        val audioInit = source.audioInitializationRange?.takeIf { it.isNotBlank() }
        val audioIndex = source.audioIndexRange?.takeIf { it.isNotBlank() }
        if (!audioUrl.isNullOrBlank() && (audioInit == null || audioIndex == null)) return null
        val duration = durationSeconds.coerceAtLeast(1)
        val xml = StringBuilder()
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        xml.append("<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" type=\"static\" profiles=\"urn:mpeg:dash:profile:isoff-live:2011\" minBufferTime=\"PT1.5S\" mediaPresentationDuration=\"PT")
            .append(duration).append("S\">")
        xml.append("<Period duration=\"PT").append(duration).append("S\">")
        xml.append("<AdaptationSet id=\"1\" contentType=\"video\" mimeType=\"video/mp4\" segmentAlignment=\"true\">")
        xml.append("<Representation id=\"video\" bandwidth=\"").append(source.videoBandwidth.coerceAtLeast(1L))
            .append("\" codecs=\"").append(xmlEscape(source.videoCodecs)).append("\"")
        if (source.width > 0) xml.append(" width=\"").append(source.width).append("\"")
        if (source.height > 0) xml.append(" height=\"").append(source.height).append("\"")
        if (source.frameRate.isNotBlank()) xml.append(" frameRate=\"").append(xmlEscape(source.frameRate)).append("\"")
        xml.append(">")
        xml.append("<BaseURL>").append(xmlEscape(videoUrl)).append("</BaseURL>")
        xml.append("<SegmentBase indexRange=\"").append(xmlEscape(videoIndex)).append("\" indexRangeExact=\"true\"><Initialization range=\"")
            .append(xmlEscape(videoInit)).append("\" /></SegmentBase>")
        xml.append("</Representation></AdaptationSet>")
        if (!audioUrl.isNullOrBlank() && audioInit != null && audioIndex != null) {
            xml.append("<AdaptationSet id=\"2\" contentType=\"audio\" mimeType=\"audio/mp4\" segmentAlignment=\"true\">")
            xml.append("<Representation id=\"audio\" bandwidth=\"").append(source.audioBandwidth.coerceAtLeast(1L))
                .append("\" codecs=\"").append(xmlEscape(source.audioCodecs)).append("\">")
            xml.append("<BaseURL>").append(xmlEscape(audioUrl)).append("</BaseURL>")
            xml.append("<SegmentBase indexRange=\"").append(xmlEscape(audioIndex)).append("\" indexRangeExact=\"true\"><Initialization range=\"")
                .append(xmlEscape(audioInit)).append("\" /></SegmentBase>")
            xml.append("</Representation></AdaptationSet>")
        }
        xml.append("</Period></MPD>")

        val directory = File(cacheDir, "bilidl_playback").apply { mkdirs() }
        directory.listFiles()?.filter {
            it.extension == "mpd" && System.currentTimeMillis() - it.lastModified() > 24L * 60L * 60L * 1000L
        }?.forEach { it.delete() }
        return File(directory, "play_" + System.currentTimeMillis() + ".mpd").apply {
            writeText(xml.toString(), Charsets.UTF_8)
        }
    }
    private fun startPlayback(
        video: BiliVideo,
        playerView: PlayerView,
        statusView: TextView,
        autoPlay: Boolean
    ) {
        val requestToken = ++playerRequestToken
        currentPlayer?.release()
        currentPlayer = null
        playerView.player = null
        statusView.text = "正在向 B 站获取播放地址…"

        backgroundExecutor.execute {
            try {
                val cookie = SecureCookieStore.loadCookieHeader(this) ?: SecureCookieStore.load(this)
                val source = BiliRepository { cookie }.getPlaySource(video, selectedQualityId)
                runOnUiThread {
                    if (requestToken != playerRequestToken || detailPlayerView !== playerView || detailPage == null) {
                        return@runOnUiThread
                    }
                    val headers = HashMap<String, String>().apply {
                        put("Referer", BiliPlayUrlPolicy.videoReferer(video.aid))
                        put("Origin", "https://www.bilibili.com")
                        put("User-Agent", BiliPlayUrlPolicy.PLAYER_USER_AGENT)
                        if (!cookie.isNullOrBlank()) {
                            put(
                                "Cookie",
                                if (cookie.contains(Regex("""(?:^|;\s*)SESSDATA="""))) cookie
                                else "SESSDATA=" + cookie
                            )
                        }
                    }
                    val dataSourceFactory = DefaultHttpDataSource.Factory()
                        .setUserAgent(BiliPlayUrlPolicy.PLAYER_USER_AGENT)
                        .setAllowCrossProtocolRedirects(true)
                        .setDefaultRequestProperties(headers)
                    val progressiveFactory = ProgressiveMediaSource.Factory(dataSourceFactory)
                    val mediaSource: MediaSource = when {
                        source.videoUrls.isNotEmpty() -> {
                            val manifestFile = createDashManifestFile(source, video.durationSeconds)
                            if (manifestFile != null) {
                                val defaultDataSource = DefaultDataSource.Factory(this, dataSourceFactory)
                                DashMediaSource.Factory(defaultDataSource).createMediaSource(
                                    MediaItem.Builder()
                                        .setUri(Uri.fromFile(manifestFile))
                                        .setMimeType(MimeTypes.APPLICATION_MPD)
                                        .build()
                                )
                            } else {
                                val videoSource = progressiveFactory.createMediaSource(
                                    MediaItem.Builder()
                                        .setUri(source.videoUrls.first())
                                        .setMimeType(MimeTypes.VIDEO_MP4)
                                        .build()
                                )
                                val audioUrl = source.audioUrls.firstOrNull()
                                if (audioUrl.isNullOrBlank()) {
                                    videoSource
                                } else {
                                    val audioSource = progressiveFactory.createMediaSource(
                                        MediaItem.Builder()
                                            .setUri(audioUrl)
                                            .setMimeType(MimeTypes.AUDIO_MP4)
                                            .build()
                                    )
                                    MergingMediaSource(videoSource, audioSource)
                                }
                            }
                        }
                        source.directUrls.isNotEmpty() -> {
                            progressiveFactory.createMediaSource(
                                MediaItem.Builder()
                                    .setUri(source.directUrls.first())
                                    .setMimeType(MimeTypes.VIDEO_MP4)
                                    .build()
                            )
                        }
                        else -> throw IOException("B 站没有返回可播放的视频流")
                    }

                    val player = ExoPlayer.Builder(this).build()
                    currentPlayer = player
                    playerView.player = player
                    player.addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            statusView.text = when (playbackState) {
                                Player.STATE_BUFFERING -> "正在缓冲视频…"
                                Player.STATE_READY -> "正在播放 · " + qualityLabelForSelection(video)
                                Player.STATE_ENDED -> "播放结束"
                                else -> statusView.text
                            }
                            detailDanmakuOverlay?.invalidate()
                        }

                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            detailDanmakuOverlay?.invalidate()
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            statusView.text = "播放失败：" + error.errorCodeName +
                                "。可重新进入视频或切换可用画质。"
                        }
                    })
                    player.setMediaSource(mediaSource)
                    player.prepare()
                    player.playWhenReady = autoPlay
                }
            } catch (error: Exception) {
                runOnUiThread {
                    if (requestToken == playerRequestToken && detailPlayerView === playerView) {
                        statusView.text = error.message ?: "获取播放地址失败，请检查网络或账号权限"
                    }
                }
            }
        }
    }

    private fun closeVideoDetail() {
        detailLoadToken++
        playerRequestToken++
        currentPlayer?.release()
        currentPlayer = null
        danmakuRequestToken++
        commentsRequestToken++
        detailDanmakuOverlay?.clear()
        detailDanmakuOverlay = null
        detailPlayerView?.player = null
        detailPlayerView = null
        detailPlaybackStatus = null
        detailQualityLabel = null
        detailPage?.let { rootFrame.removeView(it) }
        detailPage = null
        detailSearchItem = null
        if (::mainContainer.isInitialized) {
            mainContainer.visibility = View.VISIBLE
        }
    }

    private fun formatCount(value: Long): String {
        val count = value.coerceAtLeast(0L)
        return when {
            count >= 100_000_000L -> String.format(java.util.Locale.getDefault(), "%.1f亿", count / 100_000_000.0)
            count >= 10_000L -> String.format(java.util.Locale.getDefault(), "%.1f万", count / 10_000.0)
            else -> count.toString()
        }
    }

    private fun formatPublishDate(timestamp: Long): String {
        return runCatching {
            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                .format(java.util.Date(timestamp * 1000L))
        }.getOrDefault("")
    }

    private fun onDownloadClicked() {
        val video = parsedVideo
        if (video == null) {
            toast("请先解析一个 B站视频")
            return
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }
        content.addView(TextView(this).apply {
            text = "选择媒体类型"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ink)
            setPadding(0, 0, 0, dp(6))
        }, LinearLayout.LayoutParams(match, wrap))

        val mediaGroup = android.widget.RadioGroup(this).apply {
            orientation = android.widget.RadioGroup.VERTICAL
        }
        val videoOption = android.widget.RadioButton(this).apply {
            id = View.generateViewId()
            text = "视频 + 音频（MP4）"
            textSize = 14f
        }
        val audioOption = android.widget.RadioButton(this).apply {
            id = View.generateViewId()
            text = "仅音频（M4A）"
            textSize = 14f
        }
        mediaGroup.addView(videoOption)
        mediaGroup.addView(audioOption)
        mediaGroup.check(
            if (prefs.getBoolean("audio_only", false)) audioOption.id else videoOption.id
        )
        content.addView(mediaGroup, LinearLayout.LayoutParams(match, wrap))

        content.addView(TextView(this).apply {
            text = "附加文件（可多选）"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ink)
            setPadding(0, dp(12), 0, dp(4))
        }, LinearLayout.LayoutParams(match, wrap))
        val coverOption = android.widget.CheckBox(this).apply {
            text = "视频封面（JPG）"
            isEnabled = !video.coverUrl.isNullOrBlank()
            textSize = 14f
        }
        val danmakuOption = android.widget.CheckBox(this).apply {
            text = "弹幕文件（XML）"
            isChecked = prefs.getBoolean("danmaku", false)
            textSize = 14f
        }
        val subtitleOption = android.widget.CheckBox(this).apply {
            text = "字幕文件（SRT）"
            isChecked = prefs.getBoolean("subtitles", false)
            textSize = 14f
        }
        content.addView(coverOption)
        content.addView(danmakuOption)
        content.addView(subtitleOption)
        content.addView(TextView(this).apply {
            text = "画质沿用当前视频的可用画质选择；附加文件会与媒体文件分开保存。"
            textSize = 11f
            setTextColor(sub)
            setPadding(dp(2), dp(8), dp(2), 0)
        }, LinearLayout.LayoutParams(match, wrap))

        AlertDialog.Builder(this)
            .setTitle("下载内容")
            .setView(content)
            .setNegativeButton("取消", null)
            .setPositiveButton("开始下载") { _, _ ->
                val spec = DownloadSpec(
                    video = video,
                    qualityId = selectedQualityId.takeIf { it > 0 }
                        ?: video.qualities.firstOrNull()?.id ?: 80,
                    audioOnly = audioOption.isChecked,
                    downloadDanmaku = danmakuOption.isChecked,
                    downloadSubtitles = subtitleOption.isChecked,
                    downloadCover = coverOption.isChecked
                )
                val id = "bili_" + System.currentTimeMillis()
                downloadSpecs[id] = spec
                persistDownloadSpec(id, spec)
                addDownload(id, video.title, video.cover)
                enqueueDownload(id, spec)
                closeVideoDetail()
                selectTab(1)
                toast("已加入下载任务")
            }
            .show()
    }

    private fun enqueueDownload(id: String, spec: DownloadSpec) {
        val networkType = if (prefs.getBoolean("wifi_only", true)) {
            androidx.work.NetworkType.UNMETERED
        } else {
            androidx.work.NetworkType.CONNECTED
        }
        val constraints = androidx.work.Constraints.Builder()
            .setRequiredNetworkType(networkType)
            .build()
        val request = OneTimeWorkRequestBuilder<BiliDownloadWorker>()
            .setConstraints(constraints)
            .setInputData(
                workDataOf(
                    BiliDownloadWorker.KEY_TASK_ID to id,
                    BiliDownloadWorker.KEY_BVID to spec.video.bvid.orEmpty(),
                    BiliDownloadWorker.KEY_AID to spec.video.aid,
                    BiliDownloadWorker.KEY_CID to spec.video.cid,
                    BiliDownloadWorker.KEY_PAGE to spec.video.page,
                    BiliDownloadWorker.KEY_DURATION to spec.video.durationSeconds,
                    BiliDownloadWorker.KEY_TITLE to spec.video.title,
                    BiliDownloadWorker.KEY_COVER_URL to spec.video.coverUrl.orEmpty(),
                    BiliDownloadWorker.KEY_QUALITY to spec.qualityId,
                    BiliDownloadWorker.KEY_AUDIO_ONLY to spec.audioOnly,
                    BiliDownloadWorker.KEY_DOWNLOAD_DANMAKU to spec.downloadDanmaku,
                    BiliDownloadWorker.KEY_DOWNLOAD_SUBTITLES to spec.downloadSubtitles,
                    BiliDownloadWorker.KEY_DOWNLOAD_COVER to spec.downloadCover,
                    BiliDownloadWorker.KEY_CONCURRENT to intArrayOf(1, 2, 3, 5)[prefs.getInt("concurrent", 1).coerceIn(0, 3)]
                )
            )
            .setBackoffCriteria(
                androidx.work.BackoffPolicy.EXPONENTIAL,
                30L,
                java.util.concurrent.TimeUnit.SECONDS
            )
            .addTag(DOWNLOAD_TAG)
            .build()

        setDownloadState(id, ST_WAITING, "等待下载")
        workManager.enqueueUniqueWork("bilidl_" + id, ExistingWorkPolicy.REPLACE, request)
        observeUniqueWork(id)
    }

    private fun observeUniqueWork(id: String) {
        workManager.getWorkInfosForUniqueWorkLiveData("bilidl_" + id).observe(this) { infos ->
            val info = infos.firstOrNull { it.state == WorkInfo.State.RUNNING }
                ?: infos.firstOrNull { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
                ?: infos.firstOrNull()
            if (info != null) handleWorkInfo(id, info)
        }
    }

    private fun persistDownloadSpec(id: String, spec: DownloadSpec) {
        val value = JSONObject()
            .put("bvid", spec.video.bvid ?: "")
            .put("aid", spec.video.aid)
            .put("cid", spec.video.cid)
            .put("page", spec.video.page)
            .put("title", spec.video.title)
            .put("uploader", spec.video.uploader)
            .put("duration", spec.video.durationSeconds)
            .put("coverUrl", spec.video.coverUrl ?: "")
            .put("quality", spec.qualityId)
            .put("audioOnly", spec.audioOnly)
            .put("downloadDanmaku", spec.downloadDanmaku)
            .put("downloadSubtitles", spec.downloadSubtitles)
            .put("downloadCover", spec.downloadCover)
        taskPrefs.edit().putString(id, value.toString()).apply()

        // Keep SharedPreferences readable for upgrades, while Room becomes the
        // durable index used by task history, retry and resource management.
        val entity = DownloadTaskEntity(
            taskId = id,
            bvid = spec.video.bvid,
            aid = spec.video.aid,
            cid = spec.video.cid,
            pageNumber = spec.video.page,
            title = spec.video.title,
            uploader = spec.video.uploader,
            durationSeconds = spec.video.durationSeconds,
            coverUrl = spec.video.coverUrl,
            qualityId = spec.qualityId,
            audioOnly = spec.audioOnly,
            downloadDanmaku = spec.downloadDanmaku,
            downloadSubtitles = spec.downloadSubtitles,
            downloadCover = spec.downloadCover
        )
        backgroundExecutor.execute {
            runCatching {
                runBlocking(Dispatchers.IO) {
                    BiliDownloadTaskRepository.getInstance(applicationContext).registerTask(entity)
                }
            }
        }
    }

    private fun persistTaskPause(id: String, reason: String) {
        backgroundExecutor.execute {
            runCatching {
                runBlocking(Dispatchers.IO) {
                    BiliDownloadTaskRepository.getInstance(applicationContext).markPaused(id, reason)
                }
            }
        }
    }

    private fun handleWorkInfo(id: String, info: WorkInfo) {
        if (!items.containsKey(id)) return
        when (info.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED ->
                setDownloadState(id, ST_WAITING, "等待下载")
            WorkInfo.State.RUNNING -> {
                val progress = info.progress.getInt(BiliDownloadWorker.KEY_PROGRESS, 0)
                val detail = info.progress.getString(BiliDownloadWorker.KEY_DETAIL).orEmpty()
                updateDownload(id, progress, detail)
            }
            WorkInfo.State.SUCCEEDED -> {
                val uriText = info.outputData.getString(BiliDownloadWorker.KEY_URI)
                if (!uriText.isNullOrBlank()) savedFileUris[id] = Uri.parse(uriText)
                finishDownload(id, info.outputData.getString(BiliDownloadWorker.KEY_SIZE_TEXT) ?: "已保存")
            }
            WorkInfo.State.FAILED -> failDownload(
                id,
                info.outputData.getString(BiliDownloadWorker.KEY_ERROR) ?: "下载失败"
            )
            WorkInfo.State.CANCELLED -> {
                persistTaskPause(id, "已取消 · 可继续下载")
                setDownloadState(id, ST_PAUSED, "已取消 · 可继续下载")
            }
        }
    }

    private fun restoreDownloadTasks() {
        taskPrefs.all.forEach { entry ->
            val id = entry.key
            val raw = entry.value as? String ?: return@forEach
            val json = try {
                JSONObject(raw)
            } catch (_: Exception) {
                taskPrefs.edit().remove(id).apply()
                return@forEach
            }
            val video = BiliVideo(
                bvid = json.optString("bvid").takeIf { it.isNotBlank() },
                aid = json.optLong("aid"),
                cid = json.optLong("cid"),
                page = json.optInt("page", 1),
                title = json.optString("title", "B站视频"),
                uploader = json.optString("uploader"),
                durationSeconds = json.optInt("duration"),
                coverUrl = json.optString("coverUrl").takeIf { it.isNotBlank() },
                cover = null,
                qualities = emptyList()
            )
            val spec = DownloadSpec(
                video,
                json.optInt("quality", 80),
                json.optBoolean("audioOnly", false),
                json.optBoolean("downloadDanmaku", false),
                json.optBoolean("downloadSubtitles", false),
                json.optBoolean("downloadCover", false)
            )
            downloadSpecs[id] = spec
            if (!items.containsKey(id)) addDownload(id, video.title, null)
            persistDownloadSpec(id, spec) // one-time migration for installations using the old task store
            observeUniqueWork(id)
        }
    }

    private fun restoreRoomTasks() {
        backgroundExecutor.execute {
            val repository = BiliDownloadTaskRepository.getInstance(applicationContext)
            val records = runCatching {
                runBlocking(Dispatchers.IO) { repository.getAllTasks() }
            }.getOrElse { emptyList() }

            // Reconcile durable task metadata with WorkManager before touching the UI.
            // A missing WorkInfo means the process/task scheduler lost the execution
            // record; only then do we enqueue again. Existing work is observed, never
            // replaced, so process recovery cannot accidentally restart a live transfer.
            val recoveryPlan = records.map { task ->
                val needsExecution = task.status == DownloadTaskStatus.QUEUED ||
                    task.status == DownloadTaskStatus.RUNNING
                val workInfos = if (needsExecution) {
                    runCatching {
                        workManager.getWorkInfosForUniqueWork("bilidl_" + task.taskId)
                            .get(15, TimeUnit.SECONDS)
                    }.getOrNull()
                } else {
                    null
                }
                task to DownloadTaskRecoveryPolicy.decide(task.status, workInfos?.size)
            }

            runOnUiThread {
                recoveryPlan.forEach { (task, shouldRequeue) ->
                    val video = BiliVideo(
                        bvid = task.bvid,
                        aid = task.aid,
                        cid = task.cid,
                        page = task.pageNumber,
                        title = task.title,
                        uploader = task.uploader,
                        durationSeconds = task.durationSeconds,
                        coverUrl = task.coverUrl,
                        cover = null,
                        qualities = emptyList()
                    )
                    val spec = DownloadSpec(
                        video = video,
                        qualityId = task.qualityId,
                        audioOnly = task.audioOnly,
                        downloadDanmaku = task.downloadDanmaku,
                        downloadSubtitles = task.downloadSubtitles,
                        downloadCover = task.downloadCover
                    )
                    downloadSpecs[task.taskId] = spec
                    if (!items.containsKey(task.taskId)) addDownload(task.taskId, task.title, null)
                    when (task.status) {
                        DownloadTaskStatus.QUEUED, DownloadTaskStatus.RUNNING -> {
                            when (shouldRequeue) {
                                DownloadTaskRecoveryAction.ENQUEUE_MISSING_WORK -> enqueueDownload(task.taskId, spec)
                                DownloadTaskRecoveryAction.OBSERVE_EXISTING_WORK -> observeUniqueWork(task.taskId)
                                DownloadTaskRecoveryAction.RESTORE_TERMINAL_STATE -> Unit
                            }
                        }
                        DownloadTaskStatus.PAUSED -> setDownloadState(task.taskId, ST_PAUSED, task.detail)
                        DownloadTaskStatus.FAILED -> failDownload(task.taskId, task.errorMessage ?: task.detail)
                        DownloadTaskStatus.COMPLETED -> {
                            task.outputUri?.let { savedFileUris[task.taskId] = Uri.parse(it) }
                            finishDownload(
                                task.taskId,
                                task.outputSizeBytes?.let(BiliDownloadEngine::formatSize) ?: "已保存"
                            )
                        }
                    }
                }
            }
        }
    }

    private fun onPauseClicked(id: String) {
        workManager.cancelUniqueWork("bilidl_" + id)
        persistTaskPause(id, "已取消，可重新下载")
        setDownloadState(id, ST_PAUSED, "已取消，可重新下载")
    }

    private fun onResumeClicked(id: String) {
        val spec = downloadSpecs[id]
        if (spec == null) {
            toast("任务参数已失效，请重新解析视频")
            return
        }
        enqueueDownload(id, spec)
    }

    private fun onDeleteClicked(id: String, deleteFile: Boolean) {
        workManager.cancelUniqueWork("bilidl_" + id)
        workManager.pruneWork()
        downloadSpecs.remove(id)
        taskPrefs.edit().remove(id).apply()
        val safeId = id.replace(Regex("[^A-Za-z0-9_-]"), "_").take(80)
        java.io.File(filesDir, "bilidl_transfers").listFiles()
            ?.filter { it.name.startsWith("task_" + safeId) }
            ?.forEach { it.delete() }
        val savedUri = savedFileUris.remove(id)
        backgroundExecutor.execute {
            runCatching {
                runBlocking(Dispatchers.IO) {
                    val repository = BiliDownloadTaskRepository.getInstance(applicationContext)
                    val resourceUris = repository.getResources(id).map { it.contentUri } +
                        listOfNotNull(savedUri?.toString())
                    if (deleteFile) {
                        resourceUris.distinct().forEach { value ->
                            runCatching { contentResolver.delete(Uri.parse(value), null, null) }
                        }
                    }
                    repository.deleteTask(id)
                }
            }
        }
    }

    private fun showTaskHistory(id: String) {
        backgroundExecutor.execute {
            val events = runCatching {
                runBlocking(Dispatchers.IO) {
                    BiliDownloadTaskRepository.getInstance(applicationContext).getRecentEvents(id, 50)
                }
            }.getOrElse { emptyList() }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (events.isEmpty()) {
                    toast("暂时没有任务状态记录")
                    return@runOnUiThread
                }
                val lines = events.map { event ->
                    val time = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault())
                        .format(java.util.Date(event.createdAt))
                    time + "  ·  " + event.stage + "\n" + event.message
                }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle("任务执行记录")
                    .setItems(lines, null)
                    .setPositiveButton("关闭", null)
                    .show()
            }
        }
    }

    private fun showTaskResources(id: String) {
        backgroundExecutor.execute {
            val resources = runCatching {
                runBlocking(Dispatchers.IO) {
                    BiliDownloadTaskRepository.getInstance(applicationContext).getResources(id)
                }
            }.getOrElse { emptyList() }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (resources.isEmpty()) {
                    toast("没有找到已保存的资源记录")
                    return@runOnUiThread
                }
                val labels = resources.map { resource ->
                    val size = BiliDownloadEngine.formatSize(resource.sizeBytes)
                    resource.displayName + "  ·  " + size
                }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle("已保存的资源")
                    .setItems(labels) { _, index ->
                        val resource = resources[index]
                        openSavedResource(Uri.parse(resource.contentUri), resource.mimeType)
                    }
                    .setNegativeButton("关闭", null)
                    .show()
            }
        }
    }

    private fun openSavedResource(uri: Uri, mimeType: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType.ifBlank { contentResolver.getType(uri) ?: "*/*" })
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (_: Exception) {
            toast("没有可打开该资源的应用")
        }
    }

    private fun onOpenClicked(id: String) {
        val uri = savedFileUris[id]
        if (uri == null) {
            toast("文件记录不存在")
            return
        }
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, contentResolver.getType(uri) ?: "video/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (_: Exception) {
            toast("没有可打开此文件的播放器")
        }
    }

    private fun onClearFinishedClicked() {
        val activeIds = items.keys
        taskPrefs.all.keys.filter { it !in activeIds }.forEach { taskPrefs.edit().remove(it).apply() }
        downloadSpecs.keys.toList().filter { it !in activeIds }.forEach(downloadSpecs::remove)
        savedFileUris.keys.toList().filter { it !in activeIds }.forEach(savedFileUris::remove)
        workManager.pruneWork()
        backgroundExecutor.execute {
            runCatching {
                runBlocking(Dispatchers.IO) {
                    BiliDownloadTaskRepository.getInstance(applicationContext).deleteCompletedTasks()
                }
            }
        }
    }

    @Volatile private var qrLoginGeneration = 0
    @Volatile private var qrLoginActive = false

    private fun onRequestQrCode() {
        val generation = ++qrLoginGeneration
        qrLoginActive = true
        qrIv.setImageDrawable(null)
        qrOverlay.text = "正在申请二维码…"
        qrOverlay.visibility = View.VISIBLE
        setQrStatus("正在连接 B站登录服务…")

        backgroundExecutor.execute {
            try {
                val session = BiliQrLoginClient.generate()
                val bitmap = BiliQrLoginClient.renderQr(session.url)
                runOnUiThread {
                    if (!isFinishing && generation == qrLoginGeneration && qrLoginActive) {
                        showQrCode(bitmap)
                        setQrStatus("请使用哔哩哔哩 App 扫描二维码")
                    }
                }

                val expiresAt = System.currentTimeMillis() + 180_000L
                var finished = false
                while (
                    !finished &&
                    qrLoginActive &&
                    generation == qrLoginGeneration &&
                    System.currentTimeMillis() < expiresAt
                ) {
                    when (val result = BiliQrLoginClient.poll(session)) {
                        is BiliQrPollResult -> when (result.state) {
                            BiliQrLoginState.WAITING -> Unit
                            BiliQrLoginState.SCANNED -> runOnUiThread {
                                if (generation == qrLoginGeneration && qrLoginActive) {
                                    setQrStatus("已扫描，请在哔哩哔哩 App 中确认登录")
                                }
                            }
                            BiliQrLoginState.EXPIRED -> {
                                finished = true
                                runOnUiThread {
                                    if (generation == qrLoginGeneration) showQrExpired()
                                }
                            }
                            BiliQrLoginState.SUCCESS -> {
                                val sessdata = result.sessdata
                                    ?: throw IOException("扫码成功，但没有获取到登录凭据")
                                val (name, vip) = BiliRepository().verifyCookie(sessdata)
                                SecureCookieStore.save(this, result.cookieHeader?.takeIf { it.isNotBlank() } ?: sessdata)
                                finished = true
                                qrLoginActive = false
                                runOnUiThread {
                                    if (generation == qrLoginGeneration && !isFinishing) {
                                        loginSuccess(name, null, vip)
                                    }
                                }
                            }
                        }
                    }
                    if (!finished) Thread.sleep(2_000L)
                }

                if (
                    !finished &&
                    generation == qrLoginGeneration &&
                    qrLoginActive
                ) {
                    runOnUiThread {
                        if (generation == qrLoginGeneration) showQrExpired()
                    }
                }
            } catch (error: Exception) {
                if (generation == qrLoginGeneration && qrLoginActive) {
                    runOnUiThread {
                        if (generation == qrLoginGeneration) {
                            qrOverlay.text = "登录失败，点击刷新"
                            qrOverlay.visibility = View.VISIBLE
                            loginFailed(error.message ?: "B站扫码登录失败，请重试")
                        }
                    }
                }
            } finally {
                if (generation == qrLoginGeneration) qrLoginActive = false
            }
        }
    }

    private fun stopQrLogin() {
        qrLoginActive = false
        qrLoginGeneration++
    }

    private fun onLogoutClicked() {
        SecureCookieStore.clear(this)
        logoutDone()
    }

    private fun onSettingChanged(key: String, value: Any) {
        if (key == "quality") refreshQuality()
    }
    private fun onChoosePathClicked() {
        toast("视频保存位置：Download/BiliDl")
    }

    private fun onClearCacheClicked() {
        // bilidl_transfers contains resumable task chunks/checkpoints. Never erase it
        // from a generic cache action, otherwise paused downloads can no longer resume.
        val cleared = java.io.File(cacheDir, "bilidl").deleteRecursively()
        toast(
            if (cleared) "临时缓存已清理，下载任务和续传断点已保留"
            else "没有可清理的临时缓存，下载任务和续传断点已保留"
        )
    }

    private fun formatDuration(seconds: Int): String {
        val safe = seconds.coerceAtLeast(0)
        return String.format(java.util.Locale.US, "%02d:%02d", safe / 60, safe % 60)
    }

    private fun preferredQualityId(qualities: List<BiliQuality>): Int {
        if (qualities.isEmpty()) return 80
        val target = when (prefs.getInt("quality", 0)) {
            1 -> 80
            2 -> 64
            3 -> 32
            4 -> 16
            else -> return qualities.first().id
        }
        return qualities.firstOrNull { it.id <= target }?.id ?: qualities.last().id
    }

    private fun qualityLabelForSelection(video: BiliVideo): String {
        return video.qualities.firstOrNull { it.id == selectedQualityId }?.label
            ?: video.qualities.firstOrNull()?.label
            ?: "自动"
    }

    private fun pageUrl(video: BiliVideo, page: BiliPage): String {
        val identity = video.bvid?.takeIf { it.isNotBlank() } ?: "av" + video.aid
        return "https://www.bilibili.com/video/" + identity + "?p=" + page.pageNumber
    }

    private fun showAvailablePages() {
        val video = parsedVideo ?: return
        val pages = video.pages.sortedBy { it.pageNumber }
        if (pages.size <= 1) return

        val labels = pages.map { page ->
            "P" + page.pageNumber + " · " + page.part + " · " + formatDuration(page.durationSeconds)
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("选择视频分P")
            .setItems(labels) { _, which ->
                onParseClicked(pageUrl(video, pages[which]))
            }
            .setNeutralButton("下载全部 " + pages.size + " P") { _, _ ->
                downloadAllPages(video, pages)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun downloadAllPages(video: BiliVideo, pages: List<BiliPage>) {
        if (pages.isEmpty()) return
        toast("正在逐P获取画质并准备下载任务…")
        val audioOnly = prefs.getBoolean("audio_only", false)
        backgroundExecutor.execute {
            val prepared = ArrayList<DownloadSpec>()
            val skipped = ArrayList<Int>()
            val repository = BiliRepository { SecureCookieStore.loadCookieHeader(this) ?: SecureCookieStore.load(this) }
            for (page in pages.sortedBy { it.pageNumber }) {
                try {
                    // Fetch each P independently so its available quality list is respected.
                    val partVideo = repository.inspect(pageUrl(video, page))
                    val quality = preferredQualityId(partVideo.qualities)
                    prepared += DownloadSpec(
                        partVideo,
                        quality,
                        audioOnly,
                        prefs.getBoolean("danmaku", false),
                        prefs.getBoolean("subtitles", false),
                        false
                    )
                } catch (_: Exception) {
                    skipped += page.pageNumber
                }
            }

            runOnUiThread {
                val batchId = System.currentTimeMillis()
                prepared.forEachIndexed { index, spec ->
                    val id = "bili_" + batchId + "_" + spec.video.page + "_" + index
                    downloadSpecs[id] = spec
                    persistDownloadSpec(id, spec)
                    addDownload(id, spec.video.title, spec.video.cover)
                    enqueueDownload(id, spec)
                }
                if (prepared.isNotEmpty()) {
                    closeVideoDetail()
                    selectTab(1)
                }
                val message = buildString {
                    append("已加入 ")
                    append(prepared.size)
                    append("/")
                    append(pages.size)
                    append(" 个分P任务")
                    if (skipped.isNotEmpty()) append("；跳过 P" + skipped.joinToString(", P"))
                }
                toast(message)
            }
        }
    }

    private fun showAvailableQualities() {
        val video = parsedVideo
        if (video == null || video.qualities.isEmpty()) {
            toast("请先解析视频，获取这条视频实际提供的画质")
            return
        }
        val labels = video.qualities.map { it.label }.toTypedArray()
        val selected = video.qualities.indexOfFirst { it.id == selectedQualityId }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("选择当前视频可用画质")
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                selectedQualityId = video.qualities[which].id
                homeQualityTv.text = video.qualities[which].label + "  ›"
                detailQualityLabel?.text = video.qualities[which].label + "  ▾"
                dialog.dismiss()
                val activePlayerView = detailPlayerView
                val activeStatus = detailPlaybackStatus
                if (activePlayerView != null && activeStatus != null) {
                    startPlayback(video, activePlayerView, activeStatus, true)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // =======================================================

    // ===================== 供后端调用的界面方法 =====================
    fun showVideo(title: String, uploader: String, cover: Bitmap?, meta: String = "") = runOnUiThread {
        setBusy(parseBtn, false); parseBtn.text = "搜索"
        titleTv.text = title
        upTv.text = "UP主 · $uploader"
        metaTv.text = meta
        metaTv.visibility = if (meta.isEmpty()) View.GONE else View.VISIBLE
        val hasMultiplePages = (parsedVideo?.pages?.size ?: 0) > 1
        metaTv.isClickable = hasMultiplePages
        metaTv.setTextColor(if (hasMultiplePages) pink else sub)
        metaTv.setOnClickListener { if (hasMultiplePages) showAvailablePages() }
        if (cover != null) coverIv.setImageBitmap(cover) else coverIv.setImageDrawable(null)
        resultCard.visibility = View.VISIBLE
        parsedVideo?.let { homeQualityTv.text = qualityLabelForSelection(it) + "  ›" }
        say("", sub)
    }

    fun showError(message: String) = runOnUiThread {
        setBusy(parseBtn, false); parseBtn.text = "搜索"
        parsedVideo = null
        resultCard.visibility = View.GONE
        say(message, err)
    }

    fun addDownload(id: String, title: String, cover: Bitmap?) = runOnUiThread {
        items[id] = DlItem(id, title, cover)
        refreshDownloadList()
    }

    fun updateDownload(id: String, progress: Int, info: String = "") = runOnUiThread {
        val item = items[id] ?: return@runOnUiThread
        item.progress = progress.coerceIn(0, 100)
        item.info = info
        if (item.state != ST_PAUSED) item.state = ST_DOWNLOADING
        bindRow(item)
    }

    fun setDownloadState(id: String, state: Int, info: String = "") = runOnUiThread {
        val item = items[id] ?: return@runOnUiThread
        item.state = state
        if (info.isNotEmpty()) item.info = info
        bindRow(item)
        refreshDownloadList()
    }

    fun finishDownload(id: String, sizeText: String) = runOnUiThread {
        val item = items[id] ?: return@runOnUiThread
        item.state = ST_DONE; item.progress = 100; item.sizeText = sizeText
        bindRow(item)
        refreshDownloadList()
    }

    fun failDownload(id: String, reason: String) = runOnUiThread {
        val item = items[id] ?: return@runOnUiThread
        item.state = ST_FAILED; item.info = reason
        bindRow(item)
        refreshDownloadList()
    }

    fun removeDownload(id: String) = runOnUiThread {
        items.remove(id); rows.remove(id)
        refreshDownloadList()
    }

    fun showQrCode(bmp: Bitmap) = runOnUiThread {
        qrIv.setImageBitmap(bmp)
        qrOverlay.visibility = View.GONE
        setQrStatus("请使用哔哩哔哩 App 扫码")
    }

    fun setQrStatus(msg: String, isError: Boolean = false) = runOnUiThread {
        qrStatusTv.text = msg
        qrStatusTv.setTextColor(if (isError) err else sub)
    }

    fun showQrExpired() = runOnUiThread {
        qrOverlay.text = "二维码已过期\n点击刷新"
        qrOverlay.visibility = View.VISIBLE
        setQrStatus("二维码已过期，请刷新", true)
    }

    fun loginSuccess(name: String, avatar: Bitmap?, vip: Boolean = false) = runOnUiThread {
        loggedIn = true; userName = name; userAvatar = avatar; userVip = vip
        refreshAccountUi()
        hideLogin()
        (supportFragmentManager.findFragmentByTag("bilivdl-profile") as? ProfileFragment)?.refreshAccount()
        toast("登录成功，欢迎 $name")
    }

    fun loginFailed(message: String) = runOnUiThread {
        setQrStatus(message, true)
    }

    fun logoutDone() = runOnUiThread {
        loggedIn = false; userName = ""; userAvatar = null; userVip = false
        refreshAccountUi()
        (supportFragmentManager.findFragmentByTag("bilivdl-profile") as? ProfileFragment)?.refreshAccount()
        toast("已退出登录")
    }

    fun setDownloadPathText(text: String) = runOnUiThread {
        if (::pathTv.isInitialized) pathTv.text = "$text  ›"
    }
    // ================================================================

    // ---------------- 颜色与常量 ----------------
    private val pink = Color.parseColor("#FB7299")
    private val pinkLight = Color.parseColor("#FF9DB6")
    private val ink = Color.parseColor("#1F2329")
    private val sub = Color.parseColor("#8B93A1")
    private val bg = Color.parseColor("#F5F6FA")
    private val field = Color.parseColor("#F1F2F6")
    private val ok = Color.parseColor("#2DB36B")
    private val err = Color.parseColor("#E5484D")
    private val match = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

    private val qualityOptions = arrayOf("自动（最高可用）", "1080P", "720P", "480P", "360P")
    private val concurrentOptions = arrayOf("1 个", "2 个", "3 个", "5 个")

    // ---------------- 状态 ----------------
    private lateinit var prefs: SharedPreferences

    private val installUpdatePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val pendingPath = prefs.getString(PREF_PENDING_UPDATE_FILE, null)
            prefs.edit().remove(PREF_PENDING_UPDATE_FILE).apply()
            if (pendingPath.isNullOrBlank()) return@registerForActivityResult

            val apk = File(pendingPath)
            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                packageManager.canRequestPackageInstalls() &&
                apk.isFile
            ) {
                installUpdateApk(apk)
            } else {
                Toast.makeText(this, "未获得安装授权，更新包仍保留在缓存中。", Toast.LENGTH_LONG).show()
            }
        }
    private lateinit var pages: List<View>
    private lateinit var rootFrame: FrameLayout
    private lateinit var mainContainer: LinearLayout
    private var detailPage: View? = null
    private var detailPlayerView: PlayerView? = null
    private var detailDanmakuOverlay: com.sxsbsxs.bilivdl.ui.DanmakuOverlayView? = null
    private var detailPlaybackStatus: TextView? = null
    private var detailQualityLabel: TextView? = null
    private var currentPlayer: ExoPlayer? = null
    private var playerRequestToken = 0
    private var danmakuRequestToken = 0
    private var detailLoadToken = 0
    private var commentsRequestToken = 0
    private var detailSearchItem: BiliSearchVideo? = null
    private var detailDanmakuVisible = true
    private var commentsLoading = false
    private var commentsPage = 1
    private var commentsHasMore = false
    private var commentsTotalCount = 0L
    private val detailComments = ArrayList<BiliComment>()
    private lateinit var detailCommentsContainer: LinearLayout
    private lateinit var detailCommentsTitle: TextView
    private lateinit var detailCommentInput: EditText
    private lateinit var detailCommentSend: TextView
    private lateinit var detailCommentsMore: TextView
    private var detailCommentsList: RecyclerView? = null
    private var detailCommentsStatus: TextView? = null
    private var detailCommentSortButton: MaterialButton? = null
    private var detailCommentsAdapter: CommentAdapter? = null
    private var commentsSort = 2
    private lateinit var detailRelatedContainer: LinearLayout
    private lateinit var detailLikeButton: TextView
    private lateinit var detailFavoriteButton: TextView
    private val imageExecutor = Executors.newFixedThreadPool(3)
    private val coverCache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024L / 8L).toInt().coerceAtLeast(1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)
    }
    private val loadingCovers = ConcurrentHashMap.newKeySet<String>()
    private val searchResults = ArrayList<BiliSearchVideo>()
    private var searchKeyword = ""
    private var searchPage = 1
    private var searchOrder = "totalrank"
    private var searchLoading = false
    private var searchRequestToken = 0
    private var canLoadMoreSearch = false
    private lateinit var resultGrid: LinearLayout
    private lateinit var loadMoreButton: TextView
    private lateinit var searchSortLabels: List<TextView>
    private lateinit var searchSortContainer: View
    private lateinit var feedSectionTitle: TextView
    private val tabIcon = ArrayList<TextView>()
    private val tabText = ArrayList<TextView>()

    private var loggedIn = false
    private var userName = ""
    private var userVip = false
    private var userAvatar: Bitmap? = null

    private val backgroundExecutor = Executors.newFixedThreadPool(2)
    private val workManager by lazy { WorkManager.getInstance(applicationContext) }
    private val taskPrefs by lazy {
        getSharedPreferences("download_tasks", Context.MODE_PRIVATE)
    }
    private var parsedVideo: BiliVideo? = null
    private var selectedQualityId = 0
    private data class DownloadSpec(
        val video: BiliVideo,
        val qualityId: Int,
        val audioOnly: Boolean,
        val downloadDanmaku: Boolean = false,
        val downloadSubtitles: Boolean = false,
        val downloadCover: Boolean = false
    )
    private val downloadSpecs = HashMap<String, DownloadSpec>()
    private val savedFileUris = HashMap<String, Uri>()


    // 首页
    private lateinit var input: EditText
    private lateinit var parseBtn: Button
    private lateinit var loginTip: View
    private lateinit var resultCard: LinearLayout
    private lateinit var coverIv: ImageView
    private lateinit var titleTv: TextView
    private lateinit var upTv: TextView
    private lateinit var metaTv: TextView
    private lateinit var homeQualityTv: TextView
    private lateinit var dlBtn: Button
    private lateinit var statusTv: TextView

    // 下载页
    private class DlItem(val id: String, var title: String, var cover: Bitmap?) {
        var state = ST_WAITING
        var progress = 0
        var info = ""
        var sizeText = ""
    }
    private class Row(
        val root: LinearLayout, val cover: ImageView, val title: TextView,
        val info: TextView, val bar: ProgressBar, val primary: TextView,
        val selection: TextView, val resources: TextView, val history: TextView
    )
    private val items = LinkedHashMap<String, DlItem>()
    private val rows = HashMap<String, Row>()
    private var dlFilter = 0
    private lateinit var dlSegs: List<TextView>
    private lateinit var listBox: LinearLayout
    private lateinit var clearTv: TextView
    private lateinit var batchTv: TextView
    private lateinit var batchBar: LinearLayout
    private lateinit var batchCountTv: TextView
    private lateinit var downloadSearch: EditText
    private var downloadQuery = ""
    private var batchMode = false
    private val selectedDownloadIds = LinkedHashSet<String>()

    // 设置页
    private lateinit var avatarIv: ImageView
    private lateinit var avatarTv: TextView
    private lateinit var nameTv: TextView
    private lateinit var subTv: TextView
    private lateinit var accountBtn: TextView
    private lateinit var setQualityTv: TextView
    private lateinit var pathTv: TextView

    // 登录页
    private lateinit var loginPage: View
    private lateinit var qrPanel: LinearLayout
    private lateinit var qrIv: ImageView
    private lateinit var qrOverlay: TextView
    private lateinit var qrStatusTv: TextView

    // ---------------- 通用小工具 ----------------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun shape(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun circle(color: Int) = GradientDrawable().apply {
        setShape(GradientDrawable.OVAL)
        setColor(color)
    }

    private fun rippleShape(fill: Int, radius: Int, stroke: Int? = null) = RippleDrawable(
        ColorStateList.valueOf(Color.parseColor("#22000000")),
        shape(fill, radius, stroke),
        shape(Color.WHITE, radius)
    )

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = shape(Color.WHITE, 20)
        setPadding(dp(18), dp(18), dp(18), dp(18))
        elevation = dp(2).toFloat()
    }

    private fun sideMargins(top: Int = 16) = LinearLayout.LayoutParams(match, wrap).apply {
        topMargin = dp(top); leftMargin = dp(16); rightMargin = dp(16)
    }

    private fun pillButton(label: String, filled: Boolean) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        stateListAnimator = null
        if (filled) {
            setTextColor(Color.WHITE)
            background = rippleShape(pink, 24)
        } else {
            setTextColor(pink)
            background = rippleShape(Color.WHITE, 24, pink)
        }
    }

    private fun chip(label: String, filled: Boolean) = TextView(this).apply {
        text = label
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(8), dp(16), dp(8))
        if (filled) {
            setTextColor(Color.WHITE)
            background = rippleShape(pink, 18)
        } else {
            setTextColor(sub)
            background = rippleShape(field, 18)
        }
    }

    private fun setBusy(v: View, busy: Boolean) {
        v.isEnabled = !busy
        v.alpha = if (busy) 0.6f else 1f
    }

    private fun bar(): ProgressBar =
        ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            val track = shape(field, 4)
            val fill = ClipDrawable(shape(pink, 4), Gravity.START, ClipDrawable.HORIZONTAL)
            progressDrawable = LayerDrawable(arrayOf<Drawable>(track, fill)).apply {
                setId(0, android.R.id.background)
                setId(1, android.R.id.progress)
            }
        }

    private fun header(title: String, subtitle: String, bottom: Int): LinearLayout {
        val h = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(26), dp(24), dp(bottom))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR, intArrayOf(pink, pinkLight)
            ).apply {
                val r = dp(32).toFloat()
                cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r)
            }
        }
        h.addView(TextView(this).apply {
            text = title
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        })
        if (subtitle.isNotEmpty()) {
            h.addView(TextView(this).apply {
                text = subtitle
                textSize = 13f
                setTextColor(Color.parseColor("#CCFFFFFF"))
                setPadding(0, dp(6), 0, 0)
            })
        }
        return h
    }

    private fun buildSegmented(
        labels: List<String>, onSelect: (Int) -> Unit
    ): Pair<LinearLayout, List<TextView>> {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = shape(Color.WHITE, 26)
            elevation = dp(4).toFloat()
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val tvs = labels.mapIndexed { i, l ->
            val t = TextView(this).apply {
                text = l
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setOnClickListener { onSelect(i) }
            }
            box.addView(t, LinearLayout.LayoutParams(0, dp(42), 1f))
            t
        }
        return Pair(box, tvs)
    }

    private fun styleSegs(tvs: List<TextView>, selected: Int) {
        tvs.forEachIndexed { i, t ->
            if (i == selected) {
                t.background = shape(pink, 22); t.setTextColor(Color.WHITE)
            } else {
                t.background = null; t.setTextColor(sub)
            }
        }
    }

    private fun say(msg: String, color: Int, onClick: (() -> Unit)? = null) {
        statusTv.text = msg
        statusTv.setTextColor(color)
        statusTv.visibility = if (msg.isEmpty()) View.GONE else View.VISIBLE
        statusTv.isClickable = onClick != null
        statusTv.setOnClickListener(if (onClick == null) null else View.OnClickListener { onClick() })
    }

    /**
     * 从 B站分享文案中提取可解析目标：
     * - bilibili.com / m.bilibili.com 完整链接
     * - b23.tv 短链接（由后端负责跟随重定向）
     * - BV、av、番剧 ep / ss 编号
     */
    private fun normalizeBiliInput(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty()) return null

        val urlPattern = Regex(
            """https?://(?:(?:www|m)\.)?(?:bilibili\.com|b23\.tv)/[^\s<>"']+""",
            RegexOption.IGNORE_CASE
        )
        val foundUrl = urlPattern.find(text)?.value
            ?.trimEnd('.', ',', ';', '，', '。', ')', '）', ']', '】')
        if (!foundUrl.isNullOrBlank()) return foundUrl

        val bvid = Regex("""\bBV[0-9A-Za-z]{10}\b""", RegexOption.IGNORE_CASE)
            .find(text)?.value
        if (bvid != null) return "https://www.bilibili.com/video/$bvid"

        val avid = Regex("""\bav\d+\b""", RegexOption.IGNORE_CASE).find(text)?.value
        if (avid != null) return "https://www.bilibili.com/video/$avid"

        val episode = Regex("""\bep\d+\b""", RegexOption.IGNORE_CASE).find(text)?.value
        if (episode != null) return "https://www.bilibili.com/bangumi/play/$episode"

        val season = Regex("""\bss\d+\b""", RegexOption.IGNORE_CASE).find(text)?.value
        if (season != null) return "https://www.bilibili.com/bangumi/play/$season"

        return null
    }

    override fun loadPopular(page: Int, callback: (Result<List<BiliVdlVideo>>) -> Unit) {
        backgroundExecutor.execute {
            val result = runCatching {
                repositoryWithSavedCookie().getPopularVideos(page, 20).map { item ->
                    BiliVdlVideo(
                        title = item.title,
                        cover = item.coverUrl.orEmpty(),
                        author = item.author,
                        play = formatUiCount(item.viewCount),
                        duration = item.duration,
                        bvid = item.bvid,
                        aid = item.aid,
                        description = item.description
                    )
                }
            }
            runOnUiThread { callback(result) }
        }
    }

    override fun searchVideos(
        query: String,
        page: Int,
        callback: (Result<List<BiliVdlVideo>>) -> Unit
    ) {
        backgroundExecutor.execute {
            val result = runCatching {
                val normalized = normalizeBiliInput(query)
                if (normalized != null) {
                    val video = repositoryWithSavedCookie().inspect(normalized)
                    listOf(
                        BiliVdlVideo(
                            title = video.title,
                            cover = video.coverUrl.orEmpty(),
                            author = video.uploader,
                            play = formatUiCount(video.viewCount),
                            duration = formatDuration(video.durationSeconds),
                            bvid = video.bvid.orEmpty(),
                            aid = video.aid,
                            description = video.description
                        )
                    )
                } else {
                    repositoryWithSavedCookie().searchVideos(query, page, "totalrank").map { item ->
                        BiliVdlVideo(
                            title = item.title,
                            cover = item.coverUrl.orEmpty(),
                            author = item.author,
                            play = formatUiCount(item.viewCount),
                            duration = item.duration,
                            bvid = item.bvid,
                            aid = item.aid,
                            description = item.description
                        )
                    }
                }
            }
            runOnUiThread { callback(result) }
        }
    }

    override fun openVideo(video: BiliVdlVideo) {
        val identity = video.bvid.takeIf { it.isNotBlank() } ?: ("av" + video.aid)
        onParseClicked("https://www.bilibili.com/video/" + identity)
    }

    override fun navigateToDiscover() {
        supportFragmentManager.beginTransaction()
            .setReorderingAllowed(true)
            .replace(
                com.sxsbsxs.bilivdl.ui.R.id.fragment_container,
                DiscoverFragment()
            )
            .addToBackStack("bilivdl-discover")
            .commit()
    }

    override fun loadDownloadTasks(callback: (List<BiliVdlDownloadTask>) -> Unit) {
        val tasks = items.values.map { item ->
            val state = when (item.state) {
                ST_DOWNLOADING -> "downloading"
                ST_DONE -> "completed"
                ST_FAILED -> "failed"
                ST_PAUSED -> "paused"
                else -> "queued"
            }
            val spec = downloadSpecs[item.id]
            val quality = spec?.video?.qualities?.firstOrNull { it.id == spec.qualityId }?.label
                ?: spec?.qualityId?.let { "画质 " + it }
                ?: "自动"
            BiliVdlDownloadTask(
                id = item.id,
                title = item.title,
                quality = quality,
                status = state,
                progress = item.progress,
                detail = item.info.ifBlank { item.sizeText }
            )
        }
        runOnUiThread { callback(tasks) }
    }

    override fun pauseDownload(taskId: String) {
        onPauseClicked(taskId)
    }

    override fun resumeDownload(taskId: String) {
        onResumeClicked(taskId)
    }

    override fun cancelDownload(taskId: String) {
        removeDownload(taskId)
        onDeleteClicked(taskId, false)
    }

    override fun deleteDownload(taskId: String, deleteSavedFile: Boolean) {
        removeDownload(taskId)
        onDeleteClicked(taskId, deleteSavedFile)
    }

    override fun clearCompletedDownloads() {
        val ids = items.values.filter { it.state == ST_DONE }.map { it.id }
        ids.forEach { id ->
            removeDownload(id)
            onDeleteClicked(id, false)
        }
    }

    override fun retryFailedDownloads() {
        items.values.filter { it.state == ST_FAILED || it.state == ST_PAUSED }
            .map { it.id }
            .forEach(::onResumeClicked)
    }

    override fun clearDownloadHistory() {
        val ids = items.values.filter { it.state == ST_DONE || it.state == ST_FAILED }
            .map { it.id }
        ids.forEach { id ->
            removeDownload(id)
            onDeleteClicked(id, false)
        }
    }

    override fun loadAccount(callback: (BiliVdlAccount) -> Unit) {
        val cookie = SecureCookieStore.loadCookieHeader(this) ?: SecureCookieStore.load(this)
        if (cookie.isNullOrBlank()) {
            loggedIn = false
            userName = ""
            userVip = false
            callback(BiliVdlAccount("未登录", "尚未连接 B 站账号", false))
            return
        }
        backgroundExecutor.execute {
            val account = runCatching {
                val (name, vip) = BiliRepository().verifyCookie(cookie)
                loggedIn = true
                userName = name
                userVip = vip
                BiliVdlAccount(name, if (vip) "账号已连接 · 大会员" else "账号已连接", true)
            }.getOrElse { error ->
                SecureCookieStore.clear(this)
                loggedIn = false
                BiliVdlAccount("未登录", error.message ?: "登录状态已失效", false)
            }
            runOnUiThread { callback(account) }
        }
    }

    override fun loadSettings(callback: (BiliVdlSettings) -> Unit) {
        val settings = BiliVdlSettings(
            qualityIndex = prefs.getInt("quality", 0).coerceIn(0, qualityOptions.lastIndex),
            concurrencyIndex = prefs.getInt("concurrent", 1).coerceIn(0, concurrentOptions.lastIndex),
            wifiOnly = prefs.getBoolean("wifi_only", true),
            downloadDanmaku = prefs.getBoolean("danmaku", false),
            downloadSubtitles = prefs.getBoolean("subtitles", false),
            audioOnly = prefs.getBoolean("audio_only", false),
            downloadPath = "Download/BiliDl"
        )
        runOnUiThread { callback(settings) }
    }

    override fun updateSetting(key: String, value: Any) {
        when (key) {
            "quality", "concurrent" -> {
                val index = (value as? Int) ?: return
                val last = if (key == "quality") qualityOptions.lastIndex else concurrentOptions.lastIndex
                prefs.edit().putInt(key, index.coerceIn(0, last)).apply()
                if (key == "quality") refreshQuality()
            }
            "wifi_only", "danmaku", "subtitles", "audio_only" -> {
                val enabled = value as? Boolean ?: return
                prefs.edit().putBoolean(key, enabled).apply()
            }
            else -> return
        }
        onSettingChanged(key, value)
    }

    override fun clearTemporaryCache() {
        onClearCacheClicked()
    }

    override fun requestLogin() {
        showLogin()
    }

    override fun requestLogout() {
        onLogoutClicked()
    }

    override fun openRepository() {
        openExternalPage(APP_REPOSITORY_URL)
    }

    override fun checkForUpdates() {
        checkLatestGitHubRelease()
    }

    private fun openExternalPage(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            Toast.makeText(this, "无法打开链接，请检查浏览器。", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkLatestGitHubRelease() {
        val checking = ProgressDialog(this).apply {
            setMessage("正在检查 GitHub Releases…")
            setCancelable(false)
            show()
        }
        backgroundExecutor.execute {
            val result = GitHubReleaseUpdater.checkLatest(this)
            runOnUiThread {
                if (checking.isShowing) checking.dismiss()
                if (isFinishing || isDestroyed) return@runOnUiThread

                when (result) {
                    is GitHubReleaseUpdater.CheckResult.UpdateAvailable -> {
                        val notes = result.release.notes
                            .take(1400)
                            .ifBlank { "仓库已发布新版本。" }
                        val message = buildString {
                            append("当前版本：")
                            append(result.currentVersion.ifBlank { "未知" })
                            append("\n最新版本：")
                            append(result.release.tagName)
                            append("\n安装包：")
                            append(result.release.assetName)
                            append("\n\n")
                            append(notes)
                        }
                        AlertDialog.Builder(this)
                            .setTitle("发现新版本")
                            .setMessage(message)
                            .setNegativeButton("稍后", null)
                            .setPositiveButton("下载并安装") { _, _ ->
                                downloadAndInstallRelease(result.release)
                            }
                            .show()
                    }
                    is GitHubReleaseUpdater.CheckResult.AlreadyLatest -> {
                        AlertDialog.Builder(this)
                            .setTitle("已经是最新版本")
                            .setMessage("当前版本 ${result.currentVersion} 已经是仓库发布的最新版本。")
                            .setPositiveButton("知道了", null)
                            .show()
                    }
                    GitHubReleaseUpdater.CheckResult.NoReleasePublished -> {
                        AlertDialog.Builder(this)
                            .setTitle("暂未发布正式安装包")
                            .setMessage(
                                "仓库目前还没有发布 GitHub Release，因此暂时没有可供应用内更新的正式 APK。发布带 APK 的正式版本后，这里会自动识别最新版本。"
                            )
                            .setNegativeButton("关闭", null)
                            .setPositiveButton("打开 Releases") { _, _ ->
                                openExternalPage(APP_RELEASES_URL)
                            }
                            .show()
                    }
                    is GitHubReleaseUpdater.CheckResult.NoApkAsset -> {
                        AlertDialog.Builder(this)
                            .setTitle("没有找到 APK")
                            .setMessage("Release ${result.releaseTag} 已发布，但没有附带可安装的 APK 文件。")
                            .setPositiveButton("打开 Releases") { _, _ ->
                                openExternalPage(APP_RELEASES_URL)
                            }
                            .setNegativeButton("关闭", null)
                            .show()
                    }
                    is GitHubReleaseUpdater.CheckResult.Failed -> {
                        AlertDialog.Builder(this)
                            .setTitle("检查更新失败")
                            .setMessage(result.message)
                            .setNegativeButton("关闭", null)
                            .setPositiveButton("重试") { _, _ ->
                                checkLatestGitHubRelease()
                            }
                            .show()
                    }
                }
            }
        }
    }

    private fun downloadAndInstallRelease(release: GitHubReleaseUpdater.ReleaseInfo) {
        val progress = ProgressDialog(this).apply {
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            max = 100
            progress = 0
            setMessage("正在下载更新包…")
            setCancelable(false)
            show()
        }

        backgroundExecutor.execute {
            try {
                var lastShown = -2
                val apk = GitHubReleaseUpdater.download(this, release) { percent ->
                    if (percent != lastShown) {
                        lastShown = percent
                        runOnUiThread {
                            if (progress.isShowing && percent >= 0) {
                                progress.progress = percent
                            } else if (progress.isShowing && percent < 0) {
                                progress.setMessage("正在下载更新包…")
                            }
                        }
                    }
                }
                runOnUiThread {
                    if (progress.isShowing) progress.dismiss()
                    if (!isFinishing && !isDestroyed) promptInstallUpdate(apk)
                }
            } catch (error: Exception) {
                runOnUiThread {
                    if (progress.isShowing) progress.dismiss()
                    if (!isFinishing && !isDestroyed) {
                        AlertDialog.Builder(this)
                            .setTitle("下载更新失败")
                            .setMessage(error.message ?: "网络异常，请稍后重试。")
                            .setPositiveButton("重试") { _, _ ->
                                downloadAndInstallRelease(release)
                            }
                            .setNegativeButton("关闭", null)
                            .show()
                    }
                }
            }
        }
    }

    private fun promptInstallUpdate(apk: File) {
        if (!apk.isFile) {
            Toast.makeText(this, "更新安装包不存在，请重新下载。", Toast.LENGTH_LONG).show()
            return
        }

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !packageManager.canRequestPackageInstalls()
        ) {
            prefs.edit().putString(PREF_PENDING_UPDATE_FILE, apk.absolutePath).apply()
            AlertDialog.Builder(this)
                .setTitle("需要安装授权")
                .setMessage("Android 需要你允许 BiliDl 安装从 GitHub 下载的更新包。")
                .setNegativeButton("取消") { _, _ ->
                    prefs.edit().remove(PREF_PENDING_UPDATE_FILE).apply()
                }
                .setPositiveButton("前往授权") { _, _ ->
                    val intent = Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:$packageName")
                    )
                    installUpdatePermissionLauncher.launch(intent)
                }
                .show()
            return
        }

        installUpdateApk(apk)
    }

    private fun installUpdateApk(apk: File) {
        try {
            val uri = FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                apk
            )
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(installIntent)
        } catch (error: Exception) {
            AlertDialog.Builder(this)
                .setTitle("无法打开安装程序")
                .setMessage(error.message ?: "请确认系统允许安装此来源的应用。")
                .setPositiveButton("知道了", null)
                .show()
        }
    }

    private fun formatUiCount(value: Long): String = when {
        value >= 100_000_000L -> String.format(java.util.Locale.US, "%.1f亿", value / 100_000_000.0)
        value >= 10_000L -> String.format(java.util.Locale.US, "%.1f万", value / 10_000.0)
        else -> value.toString()
    }

    // ---------------- 生命周期 ----------------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("ui", Context.MODE_PRIVATE)
        window.statusBarColor = Color.WHITE
        window.navigationBarColor = Color.WHITE
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

        // The UI shell and its resources are imported from the user's BiliVdl project.
        rootFrame = FrameLayout(this)
        mainContainer = layoutInflater.inflate(
            com.sxsbsxs.bilivdl.ui.R.layout.activity_main,
            rootFrame,
            false
        ) as LinearLayout
        rootFrame.addView(mainContainer, FrameLayout.LayoutParams(match, match))

        // Retain small invisible compatibility widgets for legacy download/detail
        // helpers; the visible home/search/download/profile screens belong to bilivdl-ui.
        input = EditText(this)
        parseBtn = Button(this)
        statusTv = TextView(this)
        resultCard = LinearLayout(this)
        coverIv = ImageView(this)
        titleTv = TextView(this)
        upTv = TextView(this)
        metaTv = TextView(this)
        homeQualityTv = TextView(this)
        dlBtn = Button(this)

        val bottomNavigation = mainContainer.findViewById<BottomNavigationView>(
            com.sxsbsxs.bilivdl.ui.R.id.bottom_navigation
        )
        bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                com.sxsbsxs.bilivdl.ui.R.id.nav_home -> selectTab(0)
                com.sxsbsxs.bilivdl.ui.R.id.nav_downloads -> selectTab(1)
                com.sxsbsxs.bilivdl.ui.R.id.nav_profile -> selectTab(2)
                else -> false
            }
        }

        loginPage = buildLogin()
        loginPage.visibility = View.GONE
        rootFrame.addView(loginPage, FrameLayout.LayoutParams(match, match))
        setContentView(rootFrame)

        // Initialize the first fragment through the same non-recursive path as later tab taps.
        selectTab(0)
        refreshAccountUi()
        restoreDownloadTasks()
        restoreRoomTasks()
        setDownloadPathText("Download/BiliDl")

        backgroundExecutor.execute {
            val cookie = SecureCookieStore.load(this)
            if (!cookie.isNullOrBlank()) {
                try {
                    val (name, vip) = BiliRepository().verifyCookie(cookie)
                    runOnUiThread {
                        if (!isFinishing) {
                            loggedIn = true
                            userName = name
                            userVip = vip
                        }
                    }
                } catch (_: Exception) {
                    SecureCookieStore.clear(this)
                }
            }
        }

    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        when {
            loginPage.visibility == View.VISIBLE -> hideLogin()
            detailPage != null -> closeVideoDetail()
            supportFragmentManager.backStackEntryCount > 0 -> supportFragmentManager.popBackStack()
            else -> super.onBackPressed()
        }
    }

    override fun onDestroy() {
        stopQrLogin()
        playerRequestToken++
        currentPlayer?.release()
        currentPlayer = null
        imageExecutor.shutdownNow()
        backgroundExecutor.shutdownNow()
        super.onDestroy()
    }

    // ---------------- 底部导航 ----------------
    private fun buildNavBar(): View {
        val navBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.WHITE)
            elevation = dp(8).toFloat()
        }
        val names = listOf("首页", "下载", "设置")
        val icons = listOf("🏠", "📥", "⚙️")
        for (i in 0..2) {
            val tab = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
            }
            val ic = TextView(this).apply { text = icons[i]; textSize = 20f; gravity = Gravity.CENTER }
            val tx = TextView(this).apply { text = names[i]; textSize = 11f; gravity = Gravity.CENTER }
            tab.addView(ic)
            tab.addView(tx)
            tab.setOnClickListener { selectTab(i) }
            tabIcon.add(ic); tabText.add(tx)
            navBar.addView(tab, LinearLayout.LayoutParams(0, match, 1f))
        }
        return navBar
    }

    private fun selectTab(i: Int): Boolean {
        val targetId = when (i) {
            0 -> com.sxsbsxs.bilivdl.ui.R.id.nav_home
            1 -> com.sxsbsxs.bilivdl.ui.R.id.nav_downloads
            else -> com.sxsbsxs.bilivdl.ui.R.id.nav_profile
        }

        // Check the menu item directly. Setting BottomNavigationView.selectedItemId here
        // dispatches OnItemSelectedListener again and recursively re-enters selectTab().
        val navigation = mainContainer.findViewById<BottomNavigationView>(
            com.sxsbsxs.bilivdl.ui.R.id.bottom_navigation
        )
        navigation.menu.findItem(targetId).isChecked = true

        val tag = when (i) {
            0 -> "bilivdl-home"
            1 -> "bilivdl-downloads"
            else -> "bilivdl-profile"
        }
        val current = supportFragmentManager.findFragmentById(
            com.sxsbsxs.bilivdl.ui.R.id.fragment_container
        )
        if (current?.tag == tag) return true
        val fragment = when (i) {
            0 -> HomeFragment()
            1 -> DownloadsFragment()
            else -> ProfileFragment()
        }
        supportFragmentManager.beginTransaction()
            .setReorderingAllowed(true)
            .replace(com.sxsbsxs.bilivdl.ui.R.id.fragment_container, fragment, tag)
            .commit()
        return true
    }

    // ================= 首页 =================
    private fun qualityText() =
        qualityOptions[prefs.getInt("quality", 0).coerceIn(0, qualityOptions.lastIndex)] + "  ›"

    private fun refreshQuality() {
        val label = qualityText()
        if (::homeQualityTv.isInitialized) homeQualityTv.text = label
        if (::setQualityTv.isInitialized) setQualityTv.text = label
    }

    private fun pickOption(title: String, key: String, options: Array<String>, def: Int, after: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(options, prefs.getInt(key, def)) { d, which ->
                prefs.edit().putInt(key, which).apply()
                onSettingChanged(key, which)
                after()
                d.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun buildHome(): View {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(bg)
            isVerticalScrollBarEnabled = false
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(col, match, wrap)
        col.addView(header("BiliDl", "搜索 B 站视频，点开即可查看详情与播放", 34), match, wrap)

        // Keep the old result widgets initialized for compatibility with existing UI callbacks.
        resultCard = card()
        coverIv = ImageView(this)
        titleTv = TextView(this)
        upTv = TextView(this)
        metaTv = TextView(this)
        homeQualityTv = TextView(this)
        dlBtn = pillButton("下载视频", true)

        val searchCard = card()
        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        input = EditText(this).apply {
            hint = "搜索视频标题、UP主或关键词"
            setHintTextColor(sub)
            setTextColor(ink)
            textSize = 14f
            isSingleLine = true
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            background = shape(field, 14)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val pasteButton = chip("粘贴", false)
        parseBtn = pillButton("搜索", true)
        searchRow.addView(input, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
            rightMargin = dp(6)
        })
        searchRow.addView(pasteButton, LinearLayout.LayoutParams(wrap, dp(44)).apply {
            rightMargin = dp(6)
        })
        searchRow.addView(parseBtn, LinearLayout.LayoutParams(dp(70), dp(44)))
        searchCard.addView(searchRow, LinearLayout.LayoutParams(match, wrap))
        col.addView(searchCard, sideMargins().apply { topMargin = -dp(20) })

        loginTip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(3), dp(4), dp(3))
        }
        (loginTip as LinearLayout).addView(TextView(this).apply {
            text = "登录后可按账号权限获取更多画质"
            textSize = 11f
            setTextColor(sub)
        }, LinearLayout.LayoutParams(0, wrap, 1f))
        (loginTip as LinearLayout).addView(chip("账号登录", true).apply {
            setOnClickListener { showLogin() }
        })
        col.addView(loginTip, sideMargins(12))

        feedSectionTitle = TextView(this).apply {
            text = "热门推荐"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ink)
            setPadding(dp(2), dp(16), dp(2), dp(8))
        }
        col.addView(feedSectionTitle, sideMargins(14))

        val (sortBox, sortLabels) = buildSegmented(listOf("综合", "最多播放", "最新发布")) { index ->
            searchOrder = when (index) {
                1 -> "click"
                2 -> "pubdate"
                else -> "totalrank"
            }
            styleSegs(searchSortLabels, index)
            if (searchKeyword.isNotBlank()) startVideoSearch(searchKeyword, true)
            else loadHomeFeed(true)
        }
        searchSortLabels = sortLabels
        searchSortContainer = sortBox
        searchSortContainer.visibility = View.GONE
        styleSegs(searchSortLabels, 0)
        col.addView(sortBox, sideMargins(12))

        statusTv = TextView(this).apply {
            textSize = 12f
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        col.addView(statusTv, sideMargins(12))

        resultGrid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        col.addView(resultGrid, sideMargins(12))
        loadMoreButton = TextView(this).apply {
            text = "继续上滑，自动加载更多"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(pink)
            background = shape(Color.WHITE, 14)
            setPadding(dp(16), dp(13), dp(16), dp(13))
            visibility = View.GONE
            isClickable = false
        }
        col.addView(loadMoreButton, sideMargins(12).apply {
            topMargin = dp(2)
            bottomMargin = dp(24)
        })
        renderSearchResults()
        scroll.setOnScrollChangeListener { view, _, scrollY, _, _ ->
            val contentView = (view as? ScrollView)?.getChildAt(0) ?: return@setOnScrollChangeListener
            val nearBottom = scrollY + view.height >= contentView.height - dp(420)
            if (nearBottom && searchResults.isNotEmpty() && canLoadMoreSearch && !searchLoading) {
                if (searchKeyword.isBlank()) loadHomeFeed(false)
                else startVideoSearch(searchKeyword, false)
            }
        }

        pasteButton.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val value = clipboard.primaryClip?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)?.coerceToText(this)?.toString()
            if (value.isNullOrBlank()) {
                say("剪贴板是空的", sub)
            } else {
                input.setText(value)
                input.setSelection(input.text.length)
                runHomeSearch()
            }
        }
        parseBtn.setOnClickListener { runHomeSearch() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                runHomeSearch()
                true
            } else {
                false
            }
        }
        return scroll
    }

    // ================= 下载管理页 =================
    private fun buildDownloads(): View {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }

        val hdr = FrameLayout(this)
        hdr.addView(header("下载管理", "任务进度会实时显示在这里", 52), FrameLayout.LayoutParams(match, wrap))
        clearTv = TextView(this).apply {
            text = "清空已完成"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = shape(Color.parseColor("#33FFFFFF"), 16)
            visibility = View.GONE
            setOnClickListener { confirmClear() }
        }
        hdr.addView(clearTv, FrameLayout.LayoutParams(wrap, wrap, Gravity.TOP or Gravity.END).apply {
            topMargin = dp(28); rightMargin = dp(20)
        })
        batchTv = TextView(this).apply {
            text = "批量选择"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = shape(Color.parseColor("#33FFFFFF"), 16)
            setOnClickListener { setBatchMode(!batchMode) }
        }
        hdr.addView(batchTv, FrameLayout.LayoutParams(wrap, wrap, Gravity.TOP or Gravity.END).apply {
            topMargin = dp(28); rightMargin = dp(112)
        })
        page.addView(hdr, match, wrap)

        val (segBox, segs) = buildSegmented(listOf("进行中", "已完成", "失败")) { i ->
            dlFilter = i
            refreshDownloadList()
        }
        dlSegs = segs
        page.addView(segBox, sideMargins().apply { topMargin = -dp(26) })

        batchBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = shape(Color.WHITE, 14)
        }
        batchCountTv = TextView(this).apply {
            text = "已选 0 项"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ink)
        }
        batchBar.addView(batchCountTv, LinearLayout.LayoutParams(0, wrap, 1f))
        val selectAllButton = chip("全选", false).apply { setOnClickListener { selectVisibleDownloads() } }
        val pauseButton = chip("暂停", false).apply { setOnClickListener { pauseSelectedDownloads() } }
        val retryButton = chip("重试", true).apply { setOnClickListener { retrySelectedDownloads() } }
        val deleteButton = chip("删除", false).apply { setOnClickListener { confirmDeleteSelected() } }
        listOf(selectAllButton, pauseButton, retryButton, deleteButton).forEach { button ->
            batchBar.addView(button, LinearLayout.LayoutParams(wrap, wrap).apply { leftMargin = dp(5) })
        }
        page.addView(batchBar, sideMargins(8))

        downloadSearch = EditText(this).apply {
            hint = "搜索任务名称或状态信息"
            setHintTextColor(sub)
            setTextColor(ink)
            textSize = 14f
            isSingleLine = true
            background = shape(Color.WHITE, 14, Color.parseColor("#E8EAF0"))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    downloadQuery = s?.toString()?.trim().orEmpty()
                    refreshDownloadList()
                }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
        }
        page.addView(downloadSearch, sideMargins(12))

        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        listBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        scroll.addView(listBox, match, wrap)
        page.addView(scroll, LinearLayout.LayoutParams(match, 0, 1f))
        return page
    }

    private fun emptyView(): View = TextView(this).apply {
        text = when {
            downloadQuery.isNotBlank() -> "🔎\n\n没有找到匹配的任务\n试试其他关键词"
            dlFilter == 0 -> "📭\n\n暂无进行中的任务\n去首页粘贴 B站链接开始下载"
            dlFilter == 1 -> "📂\n\n还没有已完成的下载"
            else -> "✨\n\n目前没有失败任务"
        }
        textSize = 14f
        setTextColor(sub)
        gravity = Gravity.CENTER
        setLineSpacing(0f, 1.2f)
        setPadding(0, dp(64), 0, 0)
    }

    private fun buildRow(item: DlItem): Row {
        val root = card().apply { setPadding(dp(14), dp(14), dp(14), dp(12)) }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val cover = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = shape(Color.parseColor("#E8EAF0"), 12)
            clipToOutline = true
        }
        val selection = TextView(this).apply {
            text = "○"
            textSize = 21f
            gravity = Gravity.CENTER
            setTextColor(pink)
            visibility = View.GONE
            minWidth = dp(28)
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val title = TextView(this).apply {
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ink)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        val info = TextView(this).apply { textSize = 12f; setTextColor(sub) }
        col.addView(title, match, wrap)
        col.addView(info, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(6) })
        top.addView(selection, LinearLayout.LayoutParams(dp(28), dp(70)))
        top.addView(cover, LinearLayout.LayoutParams(dp(112), dp(70)))
        top.addView(col, LinearLayout.LayoutParams(0, wrap, 1f).apply { leftMargin = dp(12) })

        val progress = bar()
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        val primary = chip("暂停", true)
        val resources = chip("资源", false)
        val history = chip("记录", false)
        val delete = chip("删除", false)
        actions.addView(delete, LinearLayout.LayoutParams(wrap, wrap).apply { rightMargin = dp(8) })
        actions.addView(history, LinearLayout.LayoutParams(wrap, wrap).apply { rightMargin = dp(8) })
        actions.addView(resources, LinearLayout.LayoutParams(wrap, wrap).apply { rightMargin = dp(8) })
        actions.addView(primary)

        root.addView(top, match, wrap)
        root.addView(progress, LinearLayout.LayoutParams(match, dp(6)).apply { topMargin = dp(12) })
        root.addView(actions, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(10) })

        primary.setOnClickListener { onPrimary(item.id) }
        delete.setOnClickListener { confirmDelete(item.id) }
        selection.setOnClickListener { toggleDownloadSelection(item.id) }
        resources.setOnClickListener { showTaskResources(item.id) }
        history.setOnClickListener { showTaskHistory(item.id) }
        root.setOnLongClickListener {
            if (!batchMode) setBatchMode(true)
            if (item.id !in selectedDownloadIds) toggleDownloadSelection(item.id)
            true
        }
        return Row(root, cover, title, info, progress, primary, selection, resources, history)
    }

    private fun bindRow(item: DlItem) {
        val r = rows[item.id] ?: return
        r.title.text = item.title
        r.selection.visibility = if (batchMode) View.VISIBLE else View.GONE
        val selected = item.id in selectedDownloadIds
        r.selection.text = if (selected) "✓" else "○"
        r.selection.setTextColor(if (selected) Color.WHITE else pink)
        r.selection.background = if (selected) shape(pink, 24) else null
        val c = item.cover
        if (c != null) r.cover.setImageBitmap(c) else r.cover.setImageDrawable(null)
        r.bar.progress = item.progress
        r.resources.visibility = if (item.state == ST_DONE) View.VISIBLE else View.GONE
        when (item.state) {
            ST_DOWNLOADING -> {
                r.bar.visibility = View.VISIBLE
                r.info.text = if (item.info.isEmpty()) "${item.progress}%" else "${item.progress}%  ·  ${item.info}"
                r.info.setTextColor(sub)
                r.primary.text = "取消"
            }
            ST_WAITING -> {
                r.bar.visibility = View.VISIBLE
                r.info.text = "等待中…"
                r.info.setTextColor(sub)
                r.primary.text = "暂停"
            }
            ST_PAUSED -> {
                r.bar.visibility = View.VISIBLE
                r.info.text = "已取消 · 可从头重新下载"
                r.info.setTextColor(sub)
                r.primary.text = "重新下载"
            }
            ST_FAILED -> {
                r.bar.visibility = View.GONE
                r.info.text = "失败：${item.info}"
                r.info.setTextColor(err)
                r.primary.text = "重试"
            }
            else -> {
                r.bar.visibility = View.GONE
                r.info.text = item.sizeText
                r.info.setTextColor(ok)
                r.primary.text = "打开"
                r.resources.visibility = View.VISIBLE
            }
        }
    }

    private fun visibleDownloadItems(): List<DlItem> = items.values.filter { item ->
        val matchesState = when (dlFilter) {
            0 -> item.state == ST_DOWNLOADING || item.state == ST_WAITING || item.state == ST_PAUSED
            1 -> item.state == ST_DONE
            else -> item.state == ST_FAILED
        }
        val matchesQuery = downloadQuery.isBlank() ||
            item.title.contains(downloadQuery, ignoreCase = true) ||
            item.info.contains(downloadQuery, ignoreCase = true) ||
            item.sizeText.contains(downloadQuery, ignoreCase = true)
        matchesState && matchesQuery
    }.sortedByDescending { item ->
        item.id.substringAfterLast('_').toLongOrNull() ?: 0L
    }

    private fun setBatchMode(enabled: Boolean) {
        batchMode = enabled
        if (!enabled) selectedDownloadIds.clear()
        batchTv.text = if (enabled) "退出选择" else "批量选择"
        batchBar.visibility = if (enabled) View.VISIBLE else View.GONE
        refreshDownloadList()
    }

    private fun toggleDownloadSelection(id: String) {
        if (!batchMode) return
        if (!selectedDownloadIds.add(id)) selectedDownloadIds.remove(id)
        refreshDownloadList()
    }

    private fun selectVisibleDownloads() {
        val visible = visibleDownloadItems().map { it.id }
        if (visible.isNotEmpty() && visible.all { it in selectedDownloadIds }) {
            selectedDownloadIds.removeAll(visible.toSet())
        } else {
            selectedDownloadIds.addAll(visible)
        }
        refreshDownloadList()
    }

    private fun pauseSelectedDownloads() {
        val targets = selectedDownloadIds.mapNotNull { items[it] }
            .filter { it.state == ST_DOWNLOADING || it.state == ST_WAITING }
        targets.forEach { onPauseClicked(it.id) }
        toast("已暂停 " + targets.size + " 个任务")
    }

    private fun retrySelectedDownloads() {
        val targets = selectedDownloadIds.mapNotNull { id ->
            val item = items[id] ?: return@mapNotNull null
            val spec = downloadSpecs[id] ?: return@mapNotNull null
            if (item.state == ST_FAILED || item.state == ST_PAUSED) id to spec else null
        }
        if (targets.isEmpty()) {
            toast("所选任务中没有可重试的项目")
            return
        }
        backgroundExecutor.execute {
            val repository = BiliDownloadTaskRepository.getInstance(applicationContext)
            var submitted = 0
            targets.forEach { (id, spec) ->
                val eligible = runCatching {
                    runBlocking(Dispatchers.IO) {
                        val task = repository.getTask(id) ?: return@runBlocking false
                        if (task.status == DownloadTaskStatus.FAILED ||
                            task.status == DownloadTaskStatus.PAUSED
                        ) {
                            repository.retry(id)
                            true
                        } else {
                            false
                        }
                    }
                }.getOrDefault(false)
                if (eligible) {
                    submitted++
                    runOnUiThread {
                        if (!isFinishing) enqueueDownload(id, spec)
                    }
                }
            }
            runOnUiThread {
                toast("已提交 " + submitted + " 个重试任务")
            }
        }
    }

    private fun confirmDeleteSelected() {
        val ids = selectedDownloadIds.filter { items.containsKey(it) }
        if (ids.isEmpty()) {
            toast("请先选择任务")
            return
        }
        val deleteFiles = booleanArrayOf(false)
        AlertDialog.Builder(this)
            .setTitle("删除 " + ids.size + " 个任务？")
            .setMultiChoiceItems(arrayOf("同时删除已保存的视频、弹幕和字幕文件"), deleteFiles) { _, index, checked ->
                deleteFiles[index] = checked
            }
            .setPositiveButton("删除") { _, _ ->
                ids.forEach { id ->
                    removeDownload(id)
                    onDeleteClicked(id, deleteFiles[0])
                }
                selectedDownloadIds.clear()
                setBatchMode(false)
                toast("已删除 " + ids.size + " 个任务记录")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun refreshDownloadList() {
        if (!::dlSegs.isInitialized || !::listBox.isInitialized) return
        val done = items.values.count { it.state == ST_DONE }
        val failed = items.values.count { it.state == ST_FAILED }
        val active = items.values.count { it.state == ST_DOWNLOADING || it.state == ST_WAITING || it.state == ST_PAUSED }
        dlSegs[0].text = "进行中 ($active)"
        dlSegs[1].text = "已完成 ($done)"
        dlSegs[2].text = "失败 ($failed)"
        styleSegs(dlSegs, dlFilter)
        clearTv.visibility = if (dlFilter == 1 && done > 0) View.VISIBLE else View.GONE

        selectedDownloadIds.retainAll(items.keys)
        batchCountTv.text = "已选 " + selectedDownloadIds.size + " 项"
        listBox.removeAllViews()
        val shown = visibleDownloadItems()

        if (shown.isEmpty()) {
            listBox.addView(emptyView(), match, wrap)
            return
        }
        for (item in shown) {
            val row = rows.getOrPut(item.id) { buildRow(item) }
            bindRow(item)
            listBox.addView(row.root, LinearLayout.LayoutParams(match, wrap).apply { bottomMargin = dp(12) })
        }
    }

    private fun onPrimary(id: String) {
        val item = items[id] ?: return
        when (item.state) {
            ST_DOWNLOADING, ST_WAITING -> onPauseClicked(id)
            ST_PAUSED, ST_FAILED -> onResumeClicked(id)
            else -> onOpenClicked(id)
        }
    }

    private fun confirmDelete(id: String) {
        val checked = booleanArrayOf(false)
        AlertDialog.Builder(this)
            .setTitle("删除这个任务？")
            .setMultiChoiceItems(arrayOf("同时删除本地文件"), checked) { _, i, c -> checked[i] = c }
            .setPositiveButton("删除") { _, _ ->
                removeDownload(id)
                onDeleteClicked(id, checked[0])
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("清空已完成的记录？")
            .setMessage("只清除列表，不会删除已保存的视频文件。")
            .setPositiveButton("清空") { _, _ ->
                items.values.filter { it.state == ST_DONE }.map { it.id }.forEach {
                    items.remove(it); rows.remove(it)
                }
                refreshDownloadList()
                onClearFinishedClicked()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ================= 设置页 =================
    private fun settingRow(title: String, desc: String?, right: View?, onClick: (() -> Unit)?): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply { text = title; textSize = 15f; setTextColor(ink) })
        if (desc != null) {
            col.addView(TextView(this).apply {
                text = desc; textSize = 12f; setTextColor(sub); setPadding(0, dp(2), 0, 0)
            })
        }
        row.addView(col, LinearLayout.LayoutParams(0, wrap, 1f))
        if (right != null) row.addView(right, LinearLayout.LayoutParams(wrap, wrap))
        if (onClick != null) {
            row.background = RippleDrawable(
                ColorStateList.valueOf(Color.parseColor("#14000000")), null, ColorDrawable(Color.WHITE)
            )
            row.setOnClickListener { onClick() }
        }
        return row
    }

    private fun valueText(text: String) = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(sub)
    }

    private fun choiceRow(title: String, key: String, options: Array<String>, def: Int): View {
        val value = valueText("")
        fun refresh() {
            value.text = options[prefs.getInt(key, def).coerceIn(0, options.lastIndex)] + "  ›"
        }
        refresh()
        return settingRow(title, null, value) { pickOption(title, key, options, def) { refresh() } }
    }

    private fun switchRow(title: String, key: String, def: Boolean, desc: String? = null): View {
        val sw = Switch(this)
        sw.isChecked = prefs.getBoolean(key, def)
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        sw.thumbTintList = ColorStateList(states, intArrayOf(pink, Color.parseColor("#BFC5D0")))
        sw.trackTintList = ColorStateList(states, intArrayOf(Color.parseColor("#FDB9CC"), Color.parseColor("#E3E6EC")))
        sw.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(key, checked).apply()
            onSettingChanged(key, checked)
        }
        return settingRow(title, desc, sw) { sw.toggle() }
    }

    private fun group(title: String, vararg rowViews: View): View {
        val wrapper = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wrapper.addView(TextView(this).apply {
            text = title
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(sub)
            setPadding(dp(8), 0, 0, dp(8))
        })
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(Color.WHITE, 20)
            clipToOutline = true
            elevation = dp(2).toFloat()
        }
        rowViews.forEachIndexed { i, r ->
            if (i > 0) {
                val line = View(this).apply { setBackgroundColor(Color.parseColor("#F0F1F4")) }
                box.addView(line, LinearLayout.LayoutParams(match, dp(1)).apply { leftMargin = dp(18) })
            }
            box.addView(r, match, wrap)
        }
        wrapper.addView(box, match, wrap)
        return wrapper
    }

    private fun buildSettings(): View {
        val scroll = ScrollView(this).apply { setBackgroundColor(bg); isVerticalScrollBarEnabled = false }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(col, match, wrap)
        col.addView(header("设置", "", 56), match, wrap)

        // 账号卡片
        avatarIv = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = circle(pinkLight)
            clipToOutline = true
        }
        avatarTv = TextView(this).apply {
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        val avatar = FrameLayout(this)
        avatar.addView(avatarIv, FrameLayout.LayoutParams(match, match))
        avatar.addView(avatarTv, FrameLayout.LayoutParams(match, match))
        nameTv = TextView(this).apply { textSize = 17f; typeface = Typeface.DEFAULT_BOLD; setTextColor(ink) }
        subTv = TextView(this).apply { textSize = 12f; setTextColor(sub) }
        accountBtn = chip("登录", true)
        val acct = card().apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val acol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        acol.addView(nameTv)
        acol.addView(subTv, LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = dp(4) })
        acct.addView(avatar, LinearLayout.LayoutParams(dp(60), dp(60)))
        acct.addView(acol, LinearLayout.LayoutParams(0, wrap, 1f).apply { leftMargin = dp(14) })
        acct.addView(accountBtn)
        acct.setOnClickListener { onAccountClick() }
        accountBtn.setOnClickListener { onAccountClick() }
        col.addView(acct, sideMargins().apply { topMargin = -dp(32) })

        // 下载
        setQualityTv = valueText(qualityText())
        val qualityRow = settingRow("优先画质", "最终选项取决于视频源和账号权限", setQualityTv) {
            pickOption("优先画质", "quality", qualityOptions, 0) { refreshQuality() }
        }
        pathTv = valueText("系统下载目录  ›")
        val pathRow = settingRow("下载位置", "Download/BiliDl", pathTv) { onChoosePathClicked() }
        col.addView(
            group(
                "下载",
                qualityRow,
                choiceRow("同时下载数", "concurrent", concurrentOptions, 1),
                switchRow("仅 Wi-Fi 下载", "wifi_only", true, "使用移动数据时不自动下载"),
                pathRow
            ), sideMargins(20)
        )

        // 内容
        col.addView(
            group(
                "内容",
                switchRow("同时下载弹幕", "danmaku", false, "保存为 B站兼容 XML 弹幕文件"),
                switchRow("同时下载字幕", "subtitles", false, "下载视频提供的字幕并保存为 SRT"),
                switchRow("仅保存音频", "audio_only", false, "只提取音频，不保留画面")
            ), sideMargins(20)
        )

        // 通用
        col.addView(
            group(
                "通用",
                switchRow("下载完成通知", "notify", true),
                settingRow("清除缓存", null, valueText("›")) { onClearCacheClicked() },
                settingRow("关于", null, valueText("v1.0  ›")) {
                    AlertDialog.Builder(this)
                        .setTitle("B站下载器 v1.0")
                        .setMessage("本工具仅供个人学习与备份使用。\n请尊重 UP 主的版权，不要二次上传或用于商业用途。")
                        .setPositiveButton("知道了", null)
                        .show()
                }
            ), sideMargins(20)
        )

        col.addView(TextView(this).apply {
            text = "仅供个人学习使用 · 请尊重 UP 主版权"
            textSize = 12f
            setTextColor(sub)
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, dp(32))
        }, match, wrap)
        return scroll
    }

    private fun onAccountClick() {
        if (!loggedIn) { showLogin(); return }
        AlertDialog.Builder(this)
            .setTitle("退出登录？")
            .setPositiveButton("退出") { _, _ -> onLogoutClicked() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun refreshAccountUi() {
        // The account card now lives in the BiliVdl ProfileFragment.
        // Keep compatibility with legacy settings widgets if they are instantiated.
        if (!::nameTv.isInitialized || !::loginTip.isInitialized) return
        if (loggedIn) {
            nameTv.text = userName
            subTv.text = if (userVip) "已登录 · 大会员" else "已登录"
            accountBtn.text = "退出"
            avatarTv.text = userName.take(1)
            val a = userAvatar
            if (a != null) {
                avatarIv.setImageBitmap(a); avatarTv.visibility = View.GONE
            } else {
                avatarIv.setImageDrawable(null); avatarTv.visibility = View.VISIBLE
            }
        } else {
            nameTv.text = "未登录"
            subTv.text = "登录后可下载高清画质"
            accountBtn.text = "登录"
            avatarTv.text = "?"
            avatarIv.setImageDrawable(null)
            avatarTv.visibility = View.VISIBLE
        }
        loginTip.visibility = if (loggedIn) View.GONE else View.VISIBLE
    }

    // ================= 登录页 =================
    private fun buildLogin(): View {
        val page = ScrollView(this).apply {
            setBackgroundColor(bg)
            isClickable = true
            isVerticalScrollBarEnabled = false
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(col, match, wrap)

        val h = header("登录哔哩哔哩", "授权后尝试获取账号可访问的画质", 56)
        h.addView(TextView(this).apply {
            text = "←  返回"
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(0, 0, dp(16), dp(14))
            setOnClickListener { hideLogin() }
        }, 0)
        col.addView(h, match, wrap)

        // QR authorization is the only sign-in route; the session is stored internally after approval.
        qrPanel = card()
        val qrBox = FrameLayout(this).apply {
            background = shape(Color.WHITE, 16, Color.parseColor("#E6E8EE"))
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        qrIv = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        qrOverlay = TextView(this).apply {
            text = "加载中…"
            textSize = 14f
            setTextColor(sub)
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#F5FFFFFF"))
            setOnClickListener { refreshQr() }
        }
        qrBox.addView(qrIv, FrameLayout.LayoutParams(match, match))
        qrBox.addView(qrOverlay, FrameLayout.LayoutParams(match, match))
        qrPanel.addView(qrBox, LinearLayout.LayoutParams(dp(220), dp(220)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })
        qrStatusTv = TextView(this).apply {
            textSize = 14f
            setTextColor(sub)
            gravity = Gravity.CENTER
        }
        qrPanel.addView(qrStatusTv, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(16) })
        val refreshBtn = pillButton("刷新二维码", false)
        refreshBtn.setOnClickListener { refreshQr() }
        qrPanel.addView(refreshBtn, LinearLayout.LayoutParams(match, dp(46)).apply { topMargin = dp(14) })
        qrPanel.addView(TextView(this).apply {
            text = "只有一台手机？可截图保存二维码，再在哔哩哔哩 App 的扫一扫中从相册选取。"
            textSize = 12f
            setTextColor(sub)
            setLineSpacing(0f, 1.2f)
            setPadding(0, dp(14), 0, 0)
        }, match, wrap)
        col.addView(qrPanel, sideMargins())

        col.addView(TextView(this).apply {
            text = "使用哔哩哔哩 App 扫描并确认授权，无需手动复制 Cookie。授权信息仅保存在本机，用于访问你账号允许的内容。"
            textSize = 12f
            setTextColor(sub)
            setPadding(dp(4), dp(12), dp(4), dp(28))
        }, match, wrap)
        return page
    }

    private fun showLogin() {
        loginPage.visibility = View.VISIBLE
        loginPage.bringToFront()
        refreshQr()
    }

    private fun hideLogin() {
        stopQrLogin()
        loginPage.visibility = View.GONE
    }

    private fun refreshQr() {
        qrIv.setImageDrawable(null)
        qrOverlay.text = "加载中…"
        qrOverlay.visibility = View.VISIBLE
        setQrStatus("正在获取二维码…")
        onRequestQrCode()
    }

}
