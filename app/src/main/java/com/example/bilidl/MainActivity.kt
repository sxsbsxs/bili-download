package com.example.bilidl

import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
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
import android.content.Intent
import android.net.Uri
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import org.json.JSONObject
import com.example.bilidl.data.local.entity.DownloadTaskEntity
import com.example.bilidl.data.local.entity.DownloadTaskStatus
import com.example.bilidl.data.repository.DownloadTaskRecoveryAction
import com.example.bilidl.data.repository.DownloadTaskRecoveryPolicy
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Activity entry point and legacy View-based screen host.
 * Parsing and Room-backed task history are delegated to lifecycle ViewModels;
 * WorkManager remains the durable execution scheduler for actual transfers.
 *
 * 首页：   showVideo(title, uploader, cover, meta) / showError(msg)
 * 下载页： addDownload(id,title,cover) / updateDownload(id,progress,info)
 *          setDownloadState(id,state,info) / finishDownload(id,sizeText)
 *          failDownload(id,reason) / removeDownload(id)
 *          状态常量 ST_DOWNLOADING / ST_WAITING / ST_PAUSED / ST_FAILED / ST_DONE
 * 登录页： showQrCode(bitmap) / setQrStatus(msg,isError) / showQrExpired()
 *          loginSuccess(name,avatar,vip) / loginFailed(msg) / logoutDone()
 * 设置状态与持久化由 SettingsViewModel 统一管理；本文件只绑定控件和交互回调。
 */
class MainActivity : ComponentActivity() {

    private val mainViewModel: MainViewModel by viewModels()
    private val downloadsViewModel: DownloadsViewModel by viewModels()
    private val loginViewModel: LoginViewModel by viewModels()
    private val settingsViewModel: SettingsViewModel by viewModels()
    private val webLoginLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val cookieHeader = result.data?.getStringExtra(BiliWebLoginActivity.EXTRA_COOKIE_HEADER)
        if (result.resultCode == Activity.RESULT_OK && !cookieHeader.isNullOrBlank()) {
            onCookieLogin(cookieHeader)
        } else if (loginPage.visibility == View.VISIBLE && activeLoginSegment == 0) {
            refreshQr()
        }
    }
    private var activeQrLoginUrl: String? = null
    private var downloadSourceHostValue: TextView? = null
    private val downloadSourceModeIndicators = mutableMapOf<Int, TextView>()
    private var downloadSourceCustomContent: View? = null

    companion object {
        private const val DOWNLOAD_TAG = "bilidl-download"
        const val ST_DOWNLOADING = DownloadTaskStatus.RUNNING
        const val ST_WAITING = DownloadTaskStatus.QUEUED
        const val ST_PAUSED = DownloadTaskStatus.PAUSED
        const val ST_FAILED = DownloadTaskStatus.FAILED
        const val ST_DONE = DownloadTaskStatus.COMPLETED
        private const val MAX_BATCH_LINKS = 50
    }

    // ===================== 真实业务接入 =====================
    private fun observeParseState() {
        lifecycleScope.launch {
            mainViewModel.parseState.collect { state ->
                when (state) {
                    VideoParseState.Idle -> Unit
                    VideoParseState.Loading -> {
                        setBusy(parseBtn, true)
                        parseBtn.text = "正在解析…"
                    }
                    is VideoParseState.Success -> {
                        val video = state.video
                        setBusy(parseBtn, false)
                        parseBtn.text = "解析视频"
                        parsedVideo = video
                        selectedQualityId = preferredQualityId(video.qualities)
                        val pageHint = if (video.pages.size > 1) {
                            " · 共 " + video.pages.size + " P，点击选择或全部下载"
                        } else {
                            ""
                        }
                        val qualityHint = if (video.qualities.isEmpty()) {
                            " · 画质将在下载时自动确认（无需先登录）"
                        } else {
                            " · " + video.qualities.size + " 种可用画质"
                        }
                        showVideo(
                            video.title,
                            video.uploader,
                            video.cover,
                            "第 " + video.page + " P · " + formatDuration(video.durationSeconds) +
                                qualityHint + pageHint
                        )
                        homeQualityTv.text = qualityLabelForSelection(video) + "  ›"
                    }
                    is VideoParseState.Error -> {
                        setBusy(parseBtn, false)
                        parseBtn.text = "解析视频"
                        showError(state.message)
                    }
                }
            }
        }
    }

    private fun onParseClicked(text: String) {
        mainViewModel.parse(text)
    }

    /** Resolve links independently so one bad URL cannot block the batch. */
    private fun downloadBatchLinks(targets: List<String>) {
        val audioOnly = settingsViewModel.getBoolean("audio_only", false)
        val downloadDanmaku = settingsViewModel.getBoolean("danmaku", false)
        val downloadSubtitles = settingsViewModel.getBoolean("subtitles", false)

        backgroundExecutor.execute {
            val futures = targets.map { target ->
                batchExecutor.submit<Pair<DownloadSpec?, String?>> {
                    try {
                        val repository = BiliRepository {
                            SecureCookieStore.load(applicationContext)
                        }
                        val video = repository.inspect(target)
                        DownloadSpec(
                            video = video,
                            qualityId = preferredQualityId(video.qualities),
                            audioOnly = audioOnly,
                            downloadDanmaku = downloadDanmaku,
                            downloadSubtitles = downloadSubtitles
                        ) to null
                    } catch (error: Exception) {
                        null to (
                            target.take(100) + "\n" +
                                (error.message ?: "解析失败").take(120)
                            )
                    }
                }
            }
            val outcomes = futures.map { future ->
                runCatching { future.get() }.getOrElse { error ->
                    null to ("批量解析任务异常：" + (error.message ?: "未知错误"))
                }
            }
            val prepared = outcomes.mapNotNull { it.first }
            val failures = outcomes.mapNotNull { it.second }

            runOnUiThread {
                setBusy(parseBtn, false)
                parseBtn.text = "解析视频"

                prepared.forEach { spec ->
                    val id = "bili_batch_" + java.util.UUID.randomUUID()
                    downloadSpecs[id] = spec
                    persistDownloadSpec(id, spec)
                    addDownload(id, spec.video.title, spec.video.cover)
                    enqueueDownload(id, spec)
                }
                if (prepared.isNotEmpty()) selectTab(1)

                if (failures.isEmpty()) {
                    toast("已加入 " + prepared.size + " 个下载任务")
                } else {
                    val details = failures.take(6).joinToString("\n\n")
                    val more = if (failures.size > 6) "\n\n另有 " + (failures.size - 6) + " 项失败" else ""
                    AlertDialog.Builder(this)
                        .setTitle("批量导入完成")
                        .setMessage(
                            "成功加入 " + prepared.size + "/" + targets.size + " 个任务。" +
                                "\n失败项已跳过，不影响其他下载。\n\n" + details + more
                        )
                        .setPositiveButton(if (prepared.isNotEmpty()) "查看下载" else "知道了") { _, _ ->
                            if (prepared.isNotEmpty()) selectTab(1)
                        }
                        .setNegativeButton("关闭", null)
                        .show()
                }
            }
        }
    }

    private fun onDownloadClicked() {
        val video = parsedVideo
        if (video == null) {
            toast("请先解析一个 B站视频")
            return
        }
        val spec = DownloadSpec(
            video = video,
            qualityId = selectedQualityId.takeIf { it > 0 } ?: video.qualities.firstOrNull()?.id ?: 80,
            audioOnly = settingsViewModel.getBoolean("audio_only", false),
            downloadDanmaku = settingsViewModel.getBoolean("danmaku", false),
            downloadSubtitles = settingsViewModel.getBoolean("subtitles", false)
        )
        val id = "bili_" + java.util.UUID.randomUUID()
        downloadSpecs[id] = spec
        persistDownloadSpec(id, spec)
        addDownload(id, video.title, video.cover)
        enqueueDownload(id, spec)
        selectTab(1)
        toast("已加入下载任务")
    }

    private fun enqueueDownload(id: String, spec: DownloadSpec) {
        val networkType = if (settingsViewModel.getBoolean("wifi_only", true)) {
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
                    BiliDownloadWorker.KEY_QUALITY to spec.qualityId,
                    BiliDownloadWorker.KEY_AUDIO_ONLY to spec.audioOnly,
                    BiliDownloadWorker.KEY_DOWNLOAD_DANMAKU to spec.downloadDanmaku,
                    BiliDownloadWorker.KEY_DOWNLOAD_SUBTITLES to spec.downloadSubtitles,
                    BiliDownloadWorker.KEY_CONCURRENT to intArrayOf(1, 2, 3, 5)[settingsViewModel.getInt("concurrent", 1).coerceIn(0, 3)]
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
        // A duplicate UI action must not cancel an in-flight transfer for the same task.
        workManager.enqueueUniqueWork("bilidl_" + id, ExistingWorkPolicy.KEEP, request)
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
        taskPrefs.edit().putString(id, value.toString()).apply()

        // Room writes are owned by DownloadsViewModel; SharedPreferences remains
        // temporarily readable only for installations upgrading from the legacy store.
        downloadsViewModel.registerTask(
            DownloadTaskEntity(
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
                downloadSubtitles = spec.downloadSubtitles
            )
        )
    }

    private fun persistTaskPause(id: String, reason: String) {
        downloadsViewModel.markPaused(id, reason)
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
                json.optBoolean("downloadSubtitles", false)
            )
            downloadSpecs[id] = spec
            if (!items.containsKey(id)) addDownload(id, video.title, null)
            persistDownloadSpec(id, spec) // one-time migration for installations using the old task store
            observeUniqueWork(id)
        }
    }

    private fun restoreRoomTasks() {
        lifecycleScope.launch {
            val records = runCatching { downloadsViewModel.loadTasks() }
                .getOrElse { error ->
                    toast(error.message ?: "读取下载历史失败")
                    emptyList()
                }

            // WorkManager owns execution; the ViewModel supplies durable task metadata.
            // Resolve scheduler state off the main thread before updating the screen.
            val recoveryPlan = withContext(Dispatchers.IO) {
                records.map { task ->
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
            }

            if (isFinishing || isDestroyed) return@launch
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
                    downloadSubtitles = task.downloadSubtitles
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
        downloadsViewModel.deleteTask(id, deleteFile, savedUri)
    }

    private fun showTaskHistory(id: String) {
        lifecycleScope.launch {
            val events = runCatching { downloadsViewModel.loadRecentEvents(id, 50) }
                .getOrElse { emptyList() }
            if (isFinishing || isDestroyed) return@launch
            if (events.isEmpty()) {
                toast("暂时没有任务状态记录")
                return@launch
            }
            val lines = events.map { event ->
                val time = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault())
                    .format(java.util.Date(event.createdAt))
                time + "  ·  " + event.stage + "\n" + event.message
            }.toTypedArray()
            AlertDialog.Builder(this@MainActivity)
                .setTitle("任务执行记录")
                .setItems(lines, null)
                .setPositiveButton("关闭", null)
                .show()
        }
    }

    private fun showTaskResources(id: String) {
        lifecycleScope.launch {
            val resources = runCatching { downloadsViewModel.loadResources(id) }
                .getOrElse { emptyList() }
            if (isFinishing || isDestroyed) return@launch
            if (resources.isEmpty()) {
                toast("没有找到已保存的资源记录")
                return@launch
            }
            val labels = resources.map { resource ->
                val size = BiliDownloadEngine.formatSize(resource.sizeBytes)
                resource.displayName + "  ·  " + size
            }.toTypedArray()
            AlertDialog.Builder(this@MainActivity)
                .setTitle("已保存的资源")
                .setItems(labels) { _, index ->
                    val resource = resources[index]
                    openSavedResource(Uri.parse(resource.contentUri), resource.mimeType)
                }
                .setNegativeButton("关闭", null)
                .show()
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
        downloadsViewModel.deleteCompletedTasks()
    }

    private fun observeLoginState() {
        lifecycleScope.launch {
            loginViewModel.state.collect { state ->
                when (state) {
                    LoginUiState.Idle,
                    LoginUiState.CheckingSession -> Unit
                    LoginUiState.RequestingQr -> {
                        activeQrLoginUrl = null
                        qrLoginLinkTv.visibility = View.GONE
                        qrIv.setImageDrawable(null)
                        qrOverlay.text = "正在申请二维码…"
                        qrOverlay.visibility = View.VISIBLE
                        setQrStatus("正在连接 B站登录服务…")
                    }
                    is LoginUiState.QrReady -> {
                        showQrCode(state.bitmap, state.url)
                        setQrStatus("请使用哔哩哔哩 App 扫描二维码")
                    }
                    LoginUiState.Scanned ->
                        setQrStatus("已扫描，请在哔哩哔哩 App 中确认登录")
                    LoginUiState.Expired -> showQrExpired()
                    LoginUiState.VerifyingCookie -> {
                        setBusy(cookieBtn, true)
                        cookieBtn.text = "登录中…"
                        setQrStatus("已取得登录凭据，正在向 B站验证账号…")
                    }
                    is LoginUiState.Success -> {
                        setBusy(cookieBtn, false)
                        loginSuccess(state.username, null, state.vip)
                    }
                    is LoginUiState.Error -> {
                        setBusy(cookieBtn, false)
                        if (state.source == LoginFailureSource.QR_CODE) {
                            qrOverlay.text = "登录失败，点击刷新"
                            qrOverlay.visibility = View.VISIBLE
                        }
                        loginFailed(state.message)
                    }
                    LoginUiState.LoggedOut -> logoutDone()
                }
            }
        }
    }


    private var activeLoginSegment = -1

    private fun onRequestQrCode() {
        loginViewModel.requestQrCode()
    }

    private fun stopQrLogin() {
        loginViewModel.stopQrLogin()
    }

    private fun onCookieLogin(cookieHeader: String) {
        loginViewModel.loginWithCookie(cookieHeader)
    }

    private fun onLogoutClicked() {
        loginViewModel.logout()
    }

    private fun onChoosePathClicked() {
        toast("视频保存位置：Download/BiliDl")
    }

    private fun onClearCacheClicked() {
        java.io.File(cacheDir, "bilidl").deleteRecursively()
        java.io.File(filesDir, "bilidl_transfers").deleteRecursively()
        toast("临时下载缓存已清理")
    }

    private fun formatDuration(seconds: Int): String {
        val safe = seconds.coerceAtLeast(0)
        return String.format(java.util.Locale.US, "%02d:%02d", safe / 60, safe % 60)
    }

    private fun preferredQualityId(qualities: List<BiliQuality>): Int {
        if (qualities.isEmpty()) return 80
        val target = when (settingsViewModel.getInt("quality", 0)) {
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
            ?: if (video.qualities.isEmpty()) "自动（下载时确认）" else "自动"
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
        val audioOnly = settingsViewModel.getBoolean("audio_only", false)
        backgroundExecutor.execute {
            val prepared = ArrayList<DownloadSpec>()
            val skipped = ArrayList<Int>()
            val repository = BiliRepository { SecureCookieStore.load(this) }
            for (page in pages.sortedBy { it.pageNumber }) {
                try {
                    // Fetch each P independently so its available quality list is respected.
                    val partVideo = repository.inspect(pageUrl(video, page))
                    val quality = preferredQualityId(partVideo.qualities)
                    prepared += DownloadSpec(
                        partVideo,
                        quality,
                        audioOnly,
                        settingsViewModel.getBoolean("danmaku", false),
                        settingsViewModel.getBoolean("subtitles", false)
                    )
                } catch (_: Exception) {
                    skipped += page.pageNumber
                }
            }

            runOnUiThread {
                prepared.forEach { spec ->
                    val id = "bili_batch_" + java.util.UUID.randomUUID()
                    downloadSpecs[id] = spec
                    persistDownloadSpec(id, spec)
                    addDownload(id, spec.video.title, spec.video.cover)
                    enqueueDownload(id, spec)
                }
                if (prepared.isNotEmpty()) selectTab(1)
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
        if (video == null) {
            toast("请先解析视频")
            return
        }
        if (video.qualities.isEmpty()) {
            toast("B站暂未返回画质列表；仍可直接下载，应用会在下载时重新获取，不要求先登录")
            return
        }
        val labels = video.qualities.map { it.label }.toTypedArray()
        val selected = video.qualities.indexOfFirst { it.id == selectedQualityId }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("选择当前视频可用画质")
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                selectedQualityId = video.qualities[which].id
                homeQualityTv.text = video.qualities[which].label + "  ›"
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // =======================================================

    // ===================== 供后端调用的界面方法 =====================
    fun showVideo(title: String, uploader: String, cover: Bitmap?, meta: String = "") = runOnUiThread {
        setBusy(parseBtn, false); parseBtn.text = "解析视频"
        titleTv.text = title
        upTv.text = "UP主 · $uploader"
        metaTv.text = meta
        metaTv.visibility = if (meta.isEmpty()) View.GONE else View.VISIBLE
        val pageCount = parsedVideo?.pages?.size ?: 0
        val hasMultiplePages = pageCount > 1
        dlAllPagesBtn.visibility = if (hasMultiplePages) View.VISIBLE else View.GONE
        dlAllPagesBtn.text = "下载全部 " + pageCount + " P"
        metaTv.isClickable = hasMultiplePages
        metaTv.setTextColor(if (hasMultiplePages) pink else sub)
        metaTv.setOnClickListener { if (hasMultiplePages) showAvailablePages() }
        if (cover != null) coverIv.setImageBitmap(cover)
        else coverIv.setImageResource(R.drawable.ic_video_placeholder)
        resultCard.visibility = View.VISIBLE
        parsedVideo?.let { homeQualityTv.text = qualityLabelForSelection(it) + "  ›" }
        say("", sub)
    }

    fun showError(message: String) = runOnUiThread {
        setBusy(parseBtn, false); parseBtn.text = "解析视频"
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

    fun showQrCode(bmp: Bitmap, qrUrl: String) = runOnUiThread {
        qrIv.setImageBitmap(bmp)
        qrOverlay.visibility = View.GONE
        activeQrLoginUrl = qrUrl
        qrLoginLinkTv.visibility = View.VISIBLE
        setQrStatus("请使用哔哩哔哩 App 扫码")
    }

    fun setQrStatus(msg: String, isError: Boolean = false) = runOnUiThread {
        qrStatusTv.text = msg
        qrStatusTv.setTextColor(if (isError) err else sub)
    }

    fun showQrExpired() = runOnUiThread {
        activeQrLoginUrl = null
        qrLoginLinkTv.visibility = View.GONE
        qrOverlay.text = "二维码已过期\n点击刷新"
        qrOverlay.visibility = View.VISIBLE
        setQrStatus("二维码已过期，请刷新", true)
    }

    fun loginSuccess(name: String, avatar: Bitmap?, vip: Boolean = false) = runOnUiThread {
        loggedIn = true; userName = name; userAvatar = avatar; userVip = vip
        setBusy(cookieBtn, false); cookieBtn.text = "登录"; cookieInput.setText("")
        refreshAccountUi()
        hideLogin()
        toast("登录成功，欢迎 $name")
    }

    fun loginFailed(message: String) = runOnUiThread {
        setBusy(cookieBtn, false); cookieBtn.text = "登录"
        cookieErrTv.text = message
        setQrStatus(message, true)
    }

    fun logoutDone() = runOnUiThread {
        loggedIn = false; userName = ""; userAvatar = null; userVip = false
        refreshAccountUi()
        toast("已退出登录")
    }

    fun setDownloadPathText(text: String) = runOnUiThread { pathTv.text = "$text  ›" }
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
    private lateinit var pages: List<View>
    private val tabIcon = ArrayList<ImageView>()
    private val tabText = ArrayList<TextView>()

    private var loggedIn = false
    private var userName = ""
    private var userVip = false
    private var userAvatar: Bitmap? = null

    private val backgroundExecutor = Executors.newFixedThreadPool(2)
    private val batchExecutor = Executors.newFixedThreadPool(3)
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
        val downloadSubtitles: Boolean = false
    )
    private val downloadSpecs = HashMap<String, DownloadSpec>()
    private val savedFileUris = HashMap<String, Uri>()


    // 首页
    private lateinit var input: EditText
    private lateinit var parseBtn: Button
    private lateinit var resultCard: LinearLayout
    private lateinit var coverIv: ImageView
    private lateinit var titleTv: TextView
    private lateinit var upTv: TextView
    private lateinit var metaTv: TextView
    private lateinit var homeQualityTv: TextView
    private lateinit var dlBtn: Button
    private lateinit var dlAllPagesBtn: Button
    private lateinit var statusTv: TextView

    // 下载页
    private class DlItem(val id: String, var title: String, var cover: Bitmap?) {
        var state = ST_WAITING
        var progress = 0
        var info = ""
        var sizeText = ""
    }
    private class Row(
        val root: LinearLayout,
        val cover: ImageView,
        val title: TextView,
        val info: TextView,
        val bar: ProgressBar,
        val primary: TextView,
        val selection: TextView,
        val more: TextView
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
    private lateinit var loginTabsContainer: View
    private lateinit var loginSegs: List<TextView>
    private lateinit var qrPanel: LinearLayout
    private lateinit var cookiePanel: LinearLayout
    private lateinit var qrIv: ImageView
    private lateinit var qrOverlay: TextView
    private lateinit var qrStatusTv: TextView
    private lateinit var qrLoginLinkTv: TextView
    private lateinit var cookieInput: EditText
    private lateinit var cookieBtn: Button
    private lateinit var cookieErrTv: TextView

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
        background = shape(Color.WHITE, 18, Color.parseColor("#E8EBF0"))
        setPadding(dp(16), dp(16), dp(16), dp(16))
        elevation = 0f
    }

    private fun sideMargins(top: Int = 16) = LinearLayout.LayoutParams(match, wrap).apply {
        topMargin = dp(top); leftMargin = dp(16); rightMargin = dp(16)
    }

    private fun pillButton(label: String, filled: Boolean) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        stateListAnimator = null
        minHeight = dp(48)
        minimumHeight = dp(48)
        setPadding(dp(18), 0, dp(18), 0)
        backgroundTintList = null
        if (filled) {
            setTextColor(Color.WHITE)
            background = rippleShape(pink, 14)
        } else {
            setTextColor(ink)
            background = rippleShape(Color.WHITE, 14, Color.parseColor("#E1E5EB"))
        }
    }

    private fun chip(label: String, filled: Boolean) = TextView(this).apply {
        text = label
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        minHeight = dp(36)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        if (filled) {
            setTextColor(Color.WHITE)
            background = rippleShape(pink, 12)
        } else {
            setTextColor(ink)
            background = rippleShape(Color.WHITE, 12, Color.parseColor("#E5E8ED"))
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
            setPadding(dp(22), dp(22), dp(22), dp(bottom.coerceAtMost(22)))
            setBackgroundColor(Color.TRANSPARENT)
        }
        h.addView(TextView(this).apply {
            text = title
            textSize = 23f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ink)
            includeFontPadding = false
        })
        if (subtitle.isNotBlank()) {
            h.addView(TextView(this).apply {
                text = subtitle
                textSize = 12f
                setTextColor(sub)
                setPadding(0, dp(5), 0, 0)
            })
        }
        return h
    }

    private fun buildSegmented(
        labels: List<String>, onSelect: (Int) -> Unit
    ): Pair<LinearLayout, List<TextView>> {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = shape(field, 12)
            setPadding(dp(3), dp(3), dp(3), dp(3))
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
                t.background = shape(Color.WHITE, 10, Color.parseColor("#E7EAF0")); t.setTextColor(ink)
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

    // ---------------- 生命周期 ----------------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = Color.WHITE
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

        val rootFrame = FrameLayout(this)
        val main = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val content = FrameLayout(this)
        pages = listOf(buildHome(), buildDownloads(), buildSettings())
        pages.forEach { content.addView(it, FrameLayout.LayoutParams(match, match)) }
        main.addView(content, LinearLayout.LayoutParams(match, 0, 1f))
        main.addView(buildNavBar(), LinearLayout.LayoutParams(match, dp(62)))
        rootFrame.addView(main, FrameLayout.LayoutParams(match, match))

        loginPage = buildLogin()
        loginPage.visibility = View.GONE
        rootFrame.addView(loginPage, FrameLayout.LayoutParams(match, match))

        setContentView(rootFrame)
        observeParseState()
        observeLoginState()
        selectTab(0)
        refreshAccountUi()
        refreshDownloadList()
        restoreDownloadTasks()
        restoreRoomTasks()
        setDownloadPathText("Download/BiliDl")
        loginViewModel.restoreSession()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (loginPage.visibility == View.VISIBLE) hideLogin() else super.onBackPressed()
    }

    override fun onDestroy() {
        stopQrLogin()
        backgroundExecutor.shutdownNow()
        batchExecutor.shutdownNow()
        super.onDestroy()
    }

    // ---------------- 底部导航 ----------------
    private fun buildNavBar(): View {
        val navBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.WHITE)
            setPadding(0, dp(5), 0, dp(3))
            elevation = dp(2).toFloat()
        }
        val names = listOf("首页", "下载", "设置")
        val icons = listOf(
            R.drawable.ic_nav_home,
            R.drawable.ic_nav_download,
            R.drawable.ic_nav_settings
        )
        for (i in names.indices) {
            val tab = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                setOnClickListener { selectTab(i) }
            }
            val icon = ImageView(this).apply {
                setImageResource(icons[i])
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = names[i]
            }
            val label = TextView(this).apply {
                text = names[i]
                textSize = 11f
                gravity = Gravity.CENTER
                setPadding(0, dp(3), 0, 0)
            }
            tab.addView(icon, LinearLayout.LayoutParams(dp(22), dp(22)))
            tab.addView(label)
            tabIcon.add(icon)
            tabText.add(label)
            navBar.addView(tab, LinearLayout.LayoutParams(0, match, 1f))
        }
        return navBar
    }

    private fun selectTab(i: Int) {
        pages.forEachIndexed { idx, page ->
            page.visibility = if (idx == i) View.VISIBLE else View.GONE
        }
        for (index in 0 until 3) {
            val selected = index == i
            val tint = if (selected) pink else sub
            tabIcon[index].imageTintList = ColorStateList.valueOf(tint)
            tabText[index].setTextColor(tint)
            tabText[index].typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
    }

    // ================= 首页 =================
    private fun qualityText() =
        qualityOptions[settingsViewModel.getInt("quality", 0).coerceIn(0, qualityOptions.lastIndex)] + "  ›"

    private fun refreshQuality() {
        homeQualityTv.text = qualityText()
        setQualityTv.text = qualityText()
    }

    private fun pickOption(title: String, key: String, options: Array<String>, def: Int, after: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(options, settingsViewModel.getInt(key, def)) { d, which ->
                settingsViewModel.setInt(key, which)
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
            clipToPadding = false
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(col, match, wrap)

        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(22), dp(22), dp(22), dp(18))
        }
        val brandIcon = ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        brand.addView(brandIcon, LinearLayout.LayoutParams(dp(40), dp(40)))
        val brandCopy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brandCopy.addView(TextView(this).apply {
            text = "BiliDl"
            textSize = 21f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ink)
            includeFontPadding = false
        })
        brandCopy.addView(TextView(this).apply {
            text = "B站视频下载"
            textSize = 12f
            setTextColor(sub)
            setPadding(0, dp(3), 0, 0)
        })
        brand.addView(brandCopy, LinearLayout.LayoutParams(wrap, wrap).apply {
            leftMargin = dp(11)
        })
        col.addView(brand, match, wrap)

        val inputCard = card()
        input = EditText(this).apply {
            hint = "粘贴 B 站视频或番剧链接"
            setHintTextColor(sub)
            setTextColor(ink)
            textSize = 15f
            minLines = 2
            maxLines = 4
            gravity = Gravity.TOP or Gravity.START
            background = shape(Color.WHITE, 13, Color.parseColor("#E1E5EB"))
            setPadding(dp(14), dp(13), dp(14), dp(13))
            setSelectAllOnFocus(false)
            includeFontPadding = false
        }
        inputCard.addView(input, LinearLayout.LayoutParams(match, wrap))

        val inputActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val helper = TextView(this).apply {
            text = "支持多条链接批量导入"
            textSize = 12f
            setTextColor(sub)
        }
        inputActions.addView(helper, LinearLayout.LayoutParams(0, wrap, 1f))
        val pasteBtn = chip("粘贴", false)
        inputActions.addView(pasteBtn, LinearLayout.LayoutParams(wrap, dp(38)))
        inputCard.addView(inputActions, LinearLayout.LayoutParams(match, wrap).apply {
            topMargin = dp(10)
        })

        parseBtn = pillButton("解析视频", true)
        inputCard.addView(parseBtn, LinearLayout.LayoutParams(match, dp(48)).apply {
            topMargin = dp(12)
        })
        col.addView(inputCard, sideMargins(4))

        resultCard = card().apply { visibility = View.GONE }
        val previewRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        coverIv = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = shape(Color.parseColor("#E8EAF0"), 12)
            clipToOutline = true
        }
        previewRow.addView(coverIv, LinearLayout.LayoutParams(dp(112), dp(76)))
        val videoCopy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleTv = TextView(this).apply {
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ink)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.12f)
            includeFontPadding = false
        }
        upTv = TextView(this).apply {
            textSize = 12f
            setTextColor(sub)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        metaTv = TextView(this).apply {
            textSize = 11f
            setTextColor(sub)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        videoCopy.addView(titleTv, match, wrap)
        videoCopy.addView(upTv, LinearLayout.LayoutParams(match, wrap).apply {
            topMargin = dp(5)
        })
        videoCopy.addView(metaTv, LinearLayout.LayoutParams(match, wrap).apply {
            topMargin = dp(3)
        })
        previewRow.addView(videoCopy, LinearLayout.LayoutParams(0, wrap, 1f).apply {
            leftMargin = dp(12)
        })
        resultCard.addView(previewRow, match, wrap)

        val qualityRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = shape(field, 12)
            setPadding(dp(14), 0, dp(14), 0)
        }
        qualityRow.addView(TextView(this).apply {
            text = "下载画质"
            textSize = 13f
            setTextColor(ink)
        }, LinearLayout.LayoutParams(0, wrap, 1f))
        homeQualityTv = TextView(this).apply {
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(pink)
            text = qualityText()
        }
        qualityRow.addView(homeQualityTv)
        qualityRow.setOnClickListener { showAvailableQualities() }

        dlBtn = pillButton("下载当前视频", true)
        dlBtn.setOnClickListener { onDownloadClicked() }
        dlAllPagesBtn = pillButton("下载全部分P", false).apply {
            visibility = View.GONE
            setOnClickListener {
                val current = parsedVideo
                if (current == null) toast("请先解析视频")
                else downloadAllPages(current, current.pages)
            }
        }
        resultCard.addView(qualityRow, LinearLayout.LayoutParams(match, dp(44)).apply {
            topMargin = dp(14)
        })
        resultCard.addView(dlBtn, LinearLayout.LayoutParams(match, dp(48)).apply {
            topMargin = dp(10)
        })
        resultCard.addView(dlAllPagesBtn, LinearLayout.LayoutParams(match, dp(44)).apply {
            topMargin = dp(8)
        })
        col.addView(resultCard, sideMargins(12))

        statusTv = TextView(this).apply {
            textSize = 13f
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setLineSpacing(0f, 1.15f)
        }
        col.addView(statusTv, LinearLayout.LayoutParams(match, wrap).apply {
            topMargin = dp(8)
            leftMargin = dp(20)
            rightMargin = dp(20)
            bottomMargin = dp(28)
        })

        pasteBtn.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)?.coerceToText(this)?.toString()
            if (text.isNullOrBlank()) {
                say("剪贴板是空的", sub)
            } else {
                input.setText(text)
                input.setSelection(input.text.length)
                say(
                    if (parsedVideo != null && resultCard.visibility == View.VISIBLE) {
                        "已粘贴链接；保留上次解析结果，点击“解析视频”更新"
                    } else {
                        "已粘贴链接；点击“解析视频”开始解析"
                    },
                    sub
                )
            }
        }

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                // IME candidate selection also changes text. Keep the last successful
                // preview visible; a new result is requested only by tapping Parse.
                if (parsedVideo != null && resultCard.visibility == View.VISIBLE) {
                    say("输入已修改；当前仍显示上次解析结果，点击“解析视频”更新", sub)
                } else if (!s.isNullOrBlank()) {
                    say("", sub)
                }
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        parseBtn.setOnClickListener {
            val rawInput = input.text.toString()
            val batchTargets = BiliLinkParser.extractAll(rawInput)
            if (batchTargets.size > 1) {
                if (batchTargets.size > MAX_BATCH_LINKS) {
                    say("一次最多批量添加 " + MAX_BATCH_LINKS + " 个链接", err)
                    return@setOnClickListener
                }
                resultCard.visibility = View.GONE
                setBusy(parseBtn, true)
                parseBtn.text = "正在准备批量任务…"
                say("正在逐条解析，失败项不会影响其他任务", sub)
                downloadBatchLinks(batchTargets)
                return@setOnClickListener
            }

            val target = normalizeBiliInput(rawInput)
            if (target == null) {
                say("请粘贴有效的 B 站链接，或输入 BV / av / ep / ss 编号", err)
                return@setOnClickListener
            }
            input.setText(target)
            input.setSelection(input.text.length)
            setBusy(parseBtn, true)
            parseBtn.text = "正在解析…"
            // Keep the last successful result on screen while the new URL is loading.
            // Success replaces it; failure is reported by the normal parse error state.
            say("", sub)
            onParseClicked(target)
        }
        return scroll
    }

    // ================= 下载管理页 =================
    private fun buildDownloads(): View {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }

        val hdr = FrameLayout(this)
        hdr.addView(header("下载管理", "管理当前下载与已保存的视频", 18), FrameLayout.LayoutParams(match, wrap))
        clearTv = TextView(this).apply {
            text = "清空已完成"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(pink)
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = shape(Color.WHITE, 12, Color.parseColor("#E5E8ED"))
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
            setTextColor(pink)
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = shape(Color.WHITE, 12, Color.parseColor("#E5E8ED"))
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
        page.addView(segBox, sideMargins(8))

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
            background = shape(Color.WHITE, 12, Color.parseColor("#E1E5EB"))
            setPadding(dp(14), dp(10), dp(14), dp(10))
            minHeight = dp(46)
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
            downloadQuery.isNotBlank() -> "没有找到匹配的任务\n试试其他关键词"
            dlFilter == 0 -> "暂无下载任务\n粘贴 B 站链接即可开始"
            dlFilter == 1 -> "这里会显示已完成的视频"
            else -> "暂无失败任务"
        }
        textSize = 14f
        setTextColor(sub)
        gravity = Gravity.CENTER
        setLineSpacing(0f, 1.25f)
        setPadding(dp(16), dp(64), dp(16), dp(24))
    }

    private fun buildRow(item: DlItem): Row {
        val root = card()
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val cover = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = shape(Color.parseColor("#E8EAF0"), 10)
            clipToOutline = true
        }
        val selection = TextView(this).apply {
            text = "○"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(pink)
            visibility = View.GONE
            minWidth = dp(28)
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val title = TextView(this).apply {
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ink)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
        }
        val info = TextView(this).apply {
            textSize = 12f
            setTextColor(sub)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        col.addView(title, match, wrap)
        col.addView(info, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(5) })
        top.addView(selection, LinearLayout.LayoutParams(dp(28), dp(68)))
        top.addView(cover, LinearLayout.LayoutParams(dp(104), dp(68)))
        top.addView(col, LinearLayout.LayoutParams(0, wrap, 1f).apply { leftMargin = dp(11) })

        val progress = bar()
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val primary = chip("暂停", true)
        val more = chip("更多", false)
        actions.addView(primary, LinearLayout.LayoutParams(0, dp(38), 1f))
        actions.addView(more, LinearLayout.LayoutParams(wrap, dp(38)).apply { leftMargin = dp(8) })

        root.addView(top, match, wrap)
        root.addView(progress, LinearLayout.LayoutParams(match, dp(5)).apply { topMargin = dp(12) })
        root.addView(actions, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(10) })

        primary.setOnClickListener { onPrimary(item.id) }
        more.setOnClickListener { showTaskActions(item.id) }
        selection.setOnClickListener { toggleDownloadSelection(item.id) }
        root.setOnLongClickListener {
            if (!batchMode) setBatchMode(true)
            if (item.id !in selectedDownloadIds) toggleDownloadSelection(item.id)
            true
        }
        return Row(root, cover, title, info, progress, primary, selection, more)
    }



    private fun bindRow(item: DlItem) {
        val r = rows[item.id] ?: return
        r.title.text = item.title
        r.selection.visibility = if (batchMode) View.VISIBLE else View.GONE
        val selected = item.id in selectedDownloadIds
        r.selection.text = if (selected) "✓" else "○"
        r.selection.setTextColor(if (selected) Color.WHITE else pink)
        r.selection.background = if (selected) shape(pink, 24) else null
        r.more.visibility = if (batchMode) View.GONE else View.VISIBLE
        val c = item.cover
        if (c != null) r.cover.setImageBitmap(c)
        else r.cover.setImageResource(R.drawable.ic_video_placeholder)
        r.bar.progress = item.progress
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
        lifecycleScope.launch {
            var submitted = 0
            targets.forEach { (id, spec) ->
                val eligible = runCatching { downloadsViewModel.retryTask(id) }.getOrDefault(false)
                if (eligible) {
                    enqueueDownload(id, spec)
                    submitted++
                }
            }
            toast("已提交 " + submitted + " 个重试任务")
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

    private fun showTaskActions(id: String) {
        val item = items[id] ?: return
        val actions = mutableListOf("查看任务记录")
        if (item.state == ST_DONE) actions += "打开已保存资源"
        actions += "删除任务"
        AlertDialog.Builder(this)
            .setTitle("任务操作")
            .setItems(actions.toTypedArray()) { _, index ->
                when (actions[index]) {
                    "查看任务记录" -> showTaskHistory(id)
                    "打开已保存资源" -> showTaskResources(id)
                    "删除任务" -> confirmDelete(id)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun refreshDownloadList() {
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
            value.text = options[settingsViewModel.getInt(key, def).coerceIn(0, options.lastIndex)] + "  ›"
        }
        refresh()
        return settingRow(title, null, value) { pickOption(title, key, options, def) { refresh() } }
    }

    private fun switchRow(title: String, key: String, def: Boolean, desc: String? = null): View {
        val sw = Switch(this)
        sw.isChecked = settingsViewModel.getBoolean(key, def)
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        sw.thumbTintList = ColorStateList(states, intArrayOf(pink, Color.parseColor("#BFC5D0")))
        sw.trackTintList = ColorStateList(states, intArrayOf(Color.parseColor("#FDB9CC"), Color.parseColor("#E3E6EC")))
        sw.setOnCheckedChangeListener { _, checked ->
            settingsViewModel.setBoolean(key, checked)
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
            background = shape(Color.WHITE, 18, Color.parseColor("#E8EBF0"))
            clipToOutline = true
            elevation = 0f
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
        col.addView(header("设置", "下载偏好与账号", 18), match, wrap)

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

        // 下载源：沿用 BVD 的内嵌策略单选布局。
        col.addView(buildDownloadSourceSettingsCard(), sideMargins(20))

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

    private fun buildDownloadSourceSettingsCard(): View {
        downloadSourceModeIndicators.clear()
        fun indicator(mode: BiliDownloadSourceMode): TextView =
            TextView(this).apply {
                textSize = 18f
                text = "○"
                setTextColor(sub)
                gravity = Gravity.CENTER
                downloadSourceModeIndicators[mode.code] = this
            }

        val defaultRow = settingRow(
            "默认",
            "优先使用 B站返回的主地址和备用地址",
            indicator(BiliDownloadSourceMode.DEFAULT)
        ) {
            selectDownloadSourceMode(BiliDownloadSourceMode.DEFAULT)
        }
        val autoProbeRow = settingRow(
            "自动探测",
            "下载前并发读取最多 64 KB 样本，按实际速度排序；失败源仍保留回退",
            indicator(BiliDownloadSourceMode.AUTO_PROBE)
        ) {
            selectDownloadSourceMode(BiliDownloadSourceMode.AUTO_PROBE)
        }
        val customHostRow = settingRow(
            "自定义 Host",
            "优先尝试指定 CDN 节点，失败后回退 B站原始地址",
            indicator(BiliDownloadSourceMode.CUSTOM_HOST)
        ) {
            selectDownloadSourceMode(BiliDownloadSourceMode.CUSTOM_HOST)
        }

        val customContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(downloadSourceHostRow(), match, wrap)
            addView(
                settingRow(
                    "可用 Host",
                    "选择 BVD 常用的 B站 CDN 镜像节点",
                    valueText("选择  ›")
                ) { showDownloadSourcePresetPicker() },
                match,
                wrap
            )
        }
        downloadSourceCustomContent = customContent

        val card = group(
            "下载源",
            defaultRow,
            autoProbeRow,
            customHostRow,
            customContent
        )
        refreshDownloadSourceModeUi()
        return card
    }

    private fun selectDownloadSourceMode(mode: BiliDownloadSourceMode) {
        settingsViewModel.setInt(BiliDownloadSourcePolicy.KEY_MODE, mode.code)
        refreshDownloadSourceModeUi()
    }

    private fun refreshDownloadSourceModeUi() {
        val selected = BiliDownloadSourceMode.fromCode(
            settingsViewModel.getInt(
                BiliDownloadSourcePolicy.KEY_MODE,
                BiliDownloadSourceMode.DEFAULT.code
            )
        )
        downloadSourceModeIndicators.forEach { (code, view) ->
            val isSelected = code == selected.code
            view.text = if (isSelected) "●" else "○"
            view.setTextColor(if (isSelected) pink else sub)
        }
        downloadSourceCustomContent?.visibility =
            if (selected == BiliDownloadSourceMode.CUSTOM_HOST) View.VISIBLE else View.GONE
    }

    private fun downloadSourceHostRow(): View {
        val current = settingsViewModel.getString(BiliDownloadSourcePolicy.KEY_CUSTOM_HOST)
        downloadSourceHostValue = valueText(current.ifBlank { "选择 Host  ›" })
        return settingRow(
            "自定义 CDN Host",
            "优先尝试替换后的地址，失败后自动回退 B站原始地址",
            downloadSourceHostValue
        ) {
            showDownloadSourceHostDialog()
        }
    }

    private fun showDownloadSourceHostDialog() {
        val input = EditText(this).apply {
            setSingleLine(true)
            hint = "upos-sz-mirrorali.bilivideo.com"
            setText(settingsViewModel.getString(BiliDownloadSourcePolicy.KEY_CUSTOM_HOST))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        AlertDialog.Builder(this)
            .setTitle("自定义 CDN Host")
            .setMessage("只允许 bilivideo.com 或 akamaized.net 的 B站 CDN 主机名，不要填写其他网站、https://、路径或查询参数。")
            .setView(input)
            .setNeutralButton("选择预设") { _, _ -> showDownloadSourcePresetPicker() }
            .setPositiveButton("保存") { _, _ -> saveDownloadSourceHost(input.text.toString()) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showDownloadSourcePresetPicker() {
        val hosts = BiliDownloadSourcePreset.entries.map { it.host }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("选择 CDN Host")
            .setItems(hosts) { _, which -> saveDownloadSourceHost(hosts[which]) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun saveDownloadSourceHost(value: String) {
        val host = BiliDownloadSourcePolicy.normalizeHost(value)
        if (host == null) {
            toast("Host 格式不正确，请只填写域名")
            return
        }
        settingsViewModel.setString(BiliDownloadSourcePolicy.KEY_CUSTOM_HOST, host)
        downloadSourceHostValue?.text = host
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
            subTv.text = "未登录 · 可解析并下载公开内容"
            accountBtn.text = "登录"
            avatarTv.text = "?"
            avatarIv.setImageDrawable(null)
            avatarTv.visibility = View.VISIBLE
        }
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

        val h = header("登录哔哩哔哩", "登录是可选的，只影响账号可访问的内容", 18)
        h.addView(TextView(this).apply {
            text = "←  返回"
            textSize = 15f
            setTextColor(pink)
            setPadding(0, 0, dp(16), dp(14))
            setOnClickListener { hideLogin() }
        }, 0)
        col.addView(h, match, wrap)

        val (segBox, segs) = buildSegmented(listOf("扫码登录", "Cookie 登录")) { i -> selectLoginSeg(i) }
        loginSegs = segs
        loginTabsContainer = segBox
        col.addView(loginTabsContainer, sideMargins(8))

        // 扫码面板
        qrPanel = card()
        val passwordLoginEntry = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rippleShape(field, 12)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            val labels = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            labels.addView(TextView(this@MainActivity).apply {
                text = "账号密码登录"
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(ink)
            })
            labels.addView(TextView(this@MainActivity).apply {
                text = "进入 B站官方登录页，完成后自动返回并验证授权"
                textSize = 12f
                setTextColor(sub)
                setPadding(0, dp(3), 0, 0)
            })
            addView(labels, LinearLayout.LayoutParams(0, wrap, 1f))
            addView(TextView(this@MainActivity).apply {
                text = "›"
                textSize = 24f
                setTextColor(sub)
            })
            setOnClickListener { openBilibiliPasswordLogin() }
        }
        qrPanel.addView(passwordLoginEntry, LinearLayout.LayoutParams(match, wrap).apply {
            bottomMargin = dp(16)
        })
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
        qrLoginLinkTv = TextView(this).apply {
            text = "或者点击此处，跳转哔哩哔哩 App 登录"
            textSize = 13f
            setTextColor(pink)
            gravity = Gravity.CENTER
            paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
            visibility = View.GONE
            contentDescription = "使用哔哩哔哩 App 完成二维码登录"
            setPadding(0, dp(8), 0, dp(4))
            setOnClickListener { openQrLoginInBilibiliApp() }
        }
        qrPanel.addView(qrLoginLinkTv, LinearLayout.LayoutParams(match, wrap).apply {
            topMargin = dp(4)
        })
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

        // Cookie 面板
        cookiePanel = card().apply { visibility = View.GONE }
        cookiePanel.addView(TextView(this).apply {
            text = "使用 Cookie 登录"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ink)
        })
        cookiePanel.addView(TextView(this).apply {
            text = "粘贴 B站 SESSDATA 进行本机授权。凭据使用 Android Keystore 加密保存，不会上传到本应用服务器；请勿分享给他人。"
            textSize = 12f
            setTextColor(sub)
            setLineSpacing(0f, 1.2f)
            setPadding(0, dp(6), 0, dp(12))
        })
        cookieInput = EditText(this).apply {
            hint = "粘贴 SESSDATA 的值"
            setHintTextColor(sub)
            setTextColor(ink)
            textSize = 14f
            minLines = 3
            gravity = Gravity.TOP
            background = shape(field, 14)
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        cookiePanel.addView(cookieInput, match, wrap)
        cookieErrTv = TextView(this).apply { textSize = 13f; setTextColor(err) }
        cookiePanel.addView(cookieErrTv, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(8) })
        cookieBtn = pillButton("登录", true)
        cookieBtn.setOnClickListener {
            val v = cookieInput.text.toString().trim()
            if (v.isEmpty()) { cookieErrTv.text = "请先粘贴 SESSDATA"; return@setOnClickListener }
            cookieErrTv.text = ""
            setBusy(cookieBtn, true)
            cookieBtn.text = "登录中…"
            onCookieLogin(v)
        }
        cookiePanel.addView(cookieBtn, LinearLayout.LayoutParams(match, dp(48)).apply { topMargin = dp(12) })
        col.addView(cookiePanel, sideMargins())

        col.addView(View(this), match, dp(32))
        styleSegs(loginSegs, 0)
        return page
    }

    private fun selectLoginSeg(i: Int) {
        val changed = activeLoginSegment != i
        activeLoginSegment = i
        if (i != 0) stopQrLogin()
        loginTabsContainer.visibility = View.VISIBLE
        styleSegs(loginSegs, i)
        qrPanel.visibility = if (i == 0) View.VISIBLE else View.GONE
        cookiePanel.visibility = if (i == 1) View.VISIBLE else View.GONE
        if (i == 0 && changed) refreshQr()
    }

    private fun showLogin() {
        cookieErrTv.text = ""
        loginPage.visibility = View.VISIBLE
        selectLoginSeg(0)
    }

    private fun hideLogin() {
        stopQrLogin()
        activeLoginSegment = -1
        loginTabsContainer.visibility = View.VISIBLE
        loginPage.visibility = View.GONE
    }

    /**
     * Open the same official Passport H5 page used by BVD. The WebView returns
     * the full cookie header; LoginViewModel verifies it before saving.
     */
    private fun openBilibiliPasswordLogin() {
        stopQrLogin()
        webLoginLauncher.launch(Intent(this, BiliWebLoginActivity::class.java))
    }

    /**
     * BVD opens the generated QR URL through Android's normal URL handler.
     * If the installed Bilibili App claims this URL, it handles authorization.
     * Keep polling active while that app is in the foreground.
     */
    private fun openQrLoginInBilibiliApp() {
        val url = activeQrLoginUrl
        if (url.isNullOrBlank() || !BiliQrAppLoginPolicy.isTrustedQrUrl(url)) {
            toast("二维码登录链接无效，请刷新二维码")
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: android.content.ActivityNotFoundException) {
            toast("未找到可以打开 B站登录链接的应用")
        }
    }

    private fun refreshQr() {
        qrIv.setImageDrawable(null)
        qrOverlay.text = "加载中…"
        qrOverlay.visibility = View.VISIBLE
        setQrStatus("正在获取二维码…")
        onRequestQrCode()
    }

}