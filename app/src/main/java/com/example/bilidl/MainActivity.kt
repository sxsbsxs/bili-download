package com.example.bilidl

import androidx.activity.ComponentActivity
import android.app.AlertDialog
import android.content.ClipboardManager
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
import android.content.Intent
import android.net.Uri
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.Executors
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*

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
class MainActivity : ComponentActivity() {

    companion object {
        const val ST_DOWNLOADING = 0
        const val ST_WAITING = 1
        const val ST_PAUSED = 2
        const val ST_FAILED = 3
        const val ST_DONE = 4
    }

    // ===================== 真实业务接入 =====================
    private fun onParseClicked(text: String) {
        setBusy(parseBtn, true)
        parseBtn.text = "解析中…"
        backgroundExecutor.execute {
            try {
                val video = BiliRepository { SecureCookieStore.load(this) }.inspect(text)
                runOnUiThread {
                    parsedVideo = video
                    selectedQualityId = video.qualities.firstOrNull()?.id ?: 80
                    showVideo(
                        video.title,
                        video.uploader,
                        video.cover,
                        "第 " + video.page + " P · " + formatDuration(video.durationSeconds) +
                            " · " + video.qualities.size + " 种可用画质"
                    )
                    homeQualityTv.text = qualityLabelForSelection(video) + "  ›"
                }
            } catch (error: Exception) {
                showError(error.message ?: "解析失败，请检查网络后重试")
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
            audioOnly = prefs.getBoolean("audio_only", false)
        )
        val id = "bili_" + System.currentTimeMillis()
        downloadSpecs[id] = spec
        addDownload(id, video.title, video.cover)
        enqueueDownload(id, spec)
        selectTab(1)
        toast("已加入下载任务")
    }

    private fun enqueueDownload(id: String, spec: DownloadSpec) {
        val request = OneTimeWorkRequestBuilder<BiliDownloadWorker>()
            .setInputData(
                workDataOf(
                    BiliDownloadWorker.KEY_BVID to spec.video.bvid.orEmpty(),
                    BiliDownloadWorker.KEY_AID to spec.video.aid,
                    BiliDownloadWorker.KEY_CID to spec.video.cid,
                    BiliDownloadWorker.KEY_TITLE to spec.video.title,
                    BiliDownloadWorker.KEY_QUALITY to spec.qualityId,
                    BiliDownloadWorker.KEY_AUDIO_ONLY to spec.audioOnly
                )
            )
            .addTag(DOWNLOAD_TAG)
            .build()

        setDownloadState(id, ST_WAITING, "等待下载")
        workManager.enqueueUniqueWork("bilidl_" + id, ExistingWorkPolicy.REPLACE, request)
        workManager.getWorkInfoByIdLiveData(request.id).observe(this) { info ->
            if (info == null || !items.containsKey(id)) return@observe
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
                WorkInfo.State.CANCELLED -> setDownloadState(id, ST_PAUSED, "已取消，可重新下载")
            }
        }
    }

    private fun onPauseClicked(id: String) {
        workManager.cancelUniqueWork("bilidl_" + id)
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
        downloadSpecs.remove(id)
        if (deleteFile) {
            savedFileUris.remove(id)?.let { contentResolver.delete(it, null, null) }
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

    private fun onClearFinishedClicked() {}

    private fun onRequestQrCode() {
        setQrStatus("扫码登录暂未接入；当前可使用本机 SESSDATA 授权", true)
        qrOverlay.text = "请切换到 Cookie 授权"
        qrOverlay.visibility = View.VISIBLE
    }

    private fun onCookieLogin(sessdata: String) {
        backgroundExecutor.execute {
            try {
                val (name, vip) = BiliRepository().verifyCookie(sessdata)
                SecureCookieStore.save(this, sessdata)
                runOnUiThread { loginSuccess(name, null, vip) }
            } catch (error: Exception) {
                loginFailed(error.message ?: "授权验证失败")
            }
        }
    }

    private fun onLogoutClicked() {
        SecureCookieStore.clear(this)
        logoutDone()
    }

    private fun onSettingChanged(key: String, value: Any) {}
    private fun onChoosePathClicked() {
        toast("视频保存位置：Download/BiliDl")
    }

    private fun onClearCacheClicked() {
        val cache = java.io.File(cacheDir, "bilidl")
        cache.deleteRecursively()
        toast("临时下载缓存已清理")
    }

    private fun formatDuration(seconds: Int): String {
        val safe = seconds.coerceAtLeast(0)
        return String.format(java.util.Locale.US, "%02d:%02d", safe / 60, safe % 60)
    }

    private fun qualityLabelForSelection(video: BiliVideo): String {
        return video.qualities.firstOrNull { it.id == selectedQualityId }?.label
            ?: video.qualities.firstOrNull()?.label
            ?: "自动"
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
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    companion object {
        private const val DOWNLOAD_TAG = "bilidl-download"
    }
    // =======================================================

    // ===================== 供后端调用的界面方法 =====================
    fun showVideo(title: String, uploader: String, cover: Bitmap?, meta: String = "") = runOnUiThread {
        setBusy(parseBtn, false); parseBtn.text = "解析"
        titleTv.text = title
        upTv.text = "UP主 · $uploader"
        metaTv.text = meta
        metaTv.visibility = if (meta.isEmpty()) View.GONE else View.VISIBLE
        if (cover != null) coverIv.setImageBitmap(cover) else coverIv.setImageDrawable(null)
        resultCard.visibility = View.VISIBLE
        parsedVideo?.let { homeQualityTv.text = qualityLabelForSelection(it) + "  ›" }
        say("", sub)
    }

    fun showError(message: String) = runOnUiThread {
        setBusy(parseBtn, false); parseBtn.text = "解析"
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
    private lateinit var prefs: SharedPreferences
    private lateinit var pages: List<View>
    private val tabIcon = ArrayList<TextView>()
    private val tabText = ArrayList<TextView>()

    private var loggedIn = false
    private var userName = ""
    private var userVip = false
    private var userAvatar: Bitmap? = null

    private val backgroundExecutor = Executors.newFixedThreadPool(2)
    private val workManager by lazy { WorkManager.getInstance(applicationContext) }
    private var parsedVideo: BiliVideo? = null
    private var selectedQualityId = 0
    private data class DownloadSpec(val video: BiliVideo, val qualityId: Int, val audioOnly: Boolean)
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
        val info: TextView, val bar: ProgressBar, val primary: TextView
    )
    private val items = LinkedHashMap<String, DlItem>()
    private val rows = HashMap<String, Row>()
    private var dlFilter = 0
    private lateinit var dlSegs: List<TextView>
    private lateinit var listBox: LinearLayout
    private lateinit var clearTv: TextView
    private lateinit var downloadSearch: EditText
    private var downloadQuery = ""

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
    private lateinit var loginSegs: List<TextView>
    private lateinit var qrPanel: LinearLayout
    private lateinit var cookiePanel: LinearLayout
    private lateinit var qrIv: ImageView
    private lateinit var qrOverlay: TextView
    private lateinit var qrStatusTv: TextView
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

    // ---------------- 生命周期 ----------------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("ui", Context.MODE_PRIVATE)
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
        selectTab(0)
        refreshAccountUi()
        refreshDownloadList()
        setDownloadPathText("Download/BiliDl")
        backgroundExecutor.execute {
            val cookie = SecureCookieStore.load(this)
            if (!cookie.isNullOrBlank()) {
                try {
                    val (name, vip) = BiliRepository().verifyCookie(cookie)
                    runOnUiThread { if (!isFinishing) loginSuccess(name, null, vip) }
                } catch (_: Exception) {
                    SecureCookieStore.clear(this)
                }
            }
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (loginPage.visibility == View.VISIBLE) hideLogin() else super.onBackPressed()
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

    private fun selectTab(i: Int) {
        pages.forEachIndexed { idx, p -> p.visibility = if (idx == i) View.VISIBLE else View.GONE }
        for (k in 0 until 3) {
            val on = k == i
            tabIcon[k].alpha = if (on) 1f else 0.4f
            tabText[k].setTextColor(if (on) pink else sub)
            tabText[k].typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
    }

    // ================= 首页 =================
    private fun qualityText() =
        qualityOptions[prefs.getInt("quality", 0).coerceIn(0, qualityOptions.lastIndex)] + "  ›"

    private fun refreshQuality() {
        homeQualityTv.text = qualityText()
        setQualityTv.text = qualityText()
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
        val scroll = ScrollView(this).apply { setBackgroundColor(bg); isVerticalScrollBarEnabled = false }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(col, match, wrap)
        col.addView(header("B站下载器", "简单、清晰的 Bilibili 视频保存工具", 56), match, wrap)

        val inputCard = card()
        input = EditText(this).apply {
            hint = "粘贴 B站链接、BV / av / ep / ss 号"
            setHintTextColor(sub)
            setTextColor(ink)
            textSize = 15f
            minLines = 3
            maxLines = 5
            gravity = Gravity.TOP
            background = shape(field, 14)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setSelectAllOnFocus(false)
        }

        val pasteBtn = pillButton("粘贴", false)
        val clearBtn = pillButton("清空", false)
        parseBtn = pillButton("解析", true)
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnRow.addView(pasteBtn, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(8) })
        btnRow.addView(clearBtn, LinearLayout.LayoutParams(0, dp(48), 0.9f).apply { rightMargin = dp(8) })
        btnRow.addView(parseBtn, LinearLayout.LayoutParams(0, dp(48), 1.2f))
        inputCard.addView(input, match, wrap)
        inputCard.addView(btnRow, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(14) })
        inputCard.addView(TextView(this).apply {
            text = "支持视频 BV / av、番剧 ep / ss，以及 b23.tv 分享短链接"
            textSize = 12f
            setTextColor(sub)
            setPadding(dp(2), dp(10), dp(2), 0)
        }, match, wrap)
        col.addView(inputCard, sideMargins().apply { topMargin = -dp(32) })

        val tip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = shape(Color.parseColor("#FFF3F6"), 16)
            setPadding(dp(16), dp(10), dp(10), dp(10))
        }
        tip.addView(TextView(this).apply {
            text = "登录可能影响可选画质；最终以视频实际提供的清晰度为准"
            textSize = 12f
            setTextColor(Color.parseColor("#A83F62"))
        }, LinearLayout.LayoutParams(0, wrap, 1f))
        val goLogin = chip("账号登录", true)
        goLogin.setOnClickListener { showLogin() }
        tip.addView(goLogin)
        loginTip = tip
        col.addView(tip, sideMargins(16))

        resultCard = card().apply { visibility = View.GONE }
        coverIv = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = shape(Color.parseColor("#E8EAF0"), 14)
            clipToOutline = true
        }
        titleTv = TextView(this).apply {
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ink)
            setLineSpacing(0f, 1.15f)
        }
        upTv = TextView(this).apply { textSize = 13f; setTextColor(sub) }
        metaTv = TextView(this).apply { textSize = 12f; setTextColor(sub) }

        val qualityRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = shape(field, 12)
            setPadding(dp(14), 0, dp(14), 0)
        }
        qualityRow.addView(TextView(this).apply {
            text = "优先画质"; textSize = 14f; setTextColor(ink)
        }, LinearLayout.LayoutParams(0, wrap, 1f))
        homeQualityTv = TextView(this).apply {
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(pink)
            text = qualityText()
        }
        qualityRow.addView(homeQualityTv)
        qualityRow.setOnClickListener { showAvailableQualities() }

        dlBtn = pillButton("加入下载任务", true)
        dlBtn.setOnClickListener {
            onDownloadClicked()
        }

        resultCard.addView(coverIv, LinearLayout.LayoutParams(match, dp(190)))
        resultCard.addView(titleTv, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(14) })
        resultCard.addView(upTv, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(4) })
        resultCard.addView(metaTv, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(2) })
        resultCard.addView(qualityRow, LinearLayout.LayoutParams(match, dp(46)).apply { topMargin = dp(14) })
        resultCard.addView(dlBtn, LinearLayout.LayoutParams(match, dp(50)).apply { topMargin = dp(12) })
        col.addView(resultCard, sideMargins())

        statusTv = TextView(this).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        col.addView(statusTv, LinearLayout.LayoutParams(match, wrap).apply {
            topMargin = dp(16); leftMargin = dp(24); rightMargin = dp(24); bottomMargin = dp(32)
        })

        pasteBtn.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val t = cm.primaryClip?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)?.coerceToText(this)?.toString()
            if (t.isNullOrBlank()) {
                say("剪贴板是空的", sub)
            } else {
                input.setText(t)
                input.setSelection(input.text.length)
                resultCard.visibility = View.GONE
                say("已粘贴剪贴板内容，点击解析继续", sub)
            }
        }
        clearBtn.setOnClickListener {
            input.setText("")
            resultCard.visibility = View.GONE
            say("", sub)
            input.requestFocus()
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (resultCard.visibility == View.VISIBLE) resultCard.visibility = View.GONE
                if (!s.isNullOrBlank()) say("", sub)
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        parseBtn.setOnClickListener {
            val target = normalizeBiliInput(input.text.toString())
            if (target == null) {
                say("请粘贴有效的 B站链接，或输入 BV / av / ep / ss 编号", err)
                return@setOnClickListener
            }
            input.setText(target)
            input.setSelection(input.text.length)
            setBusy(parseBtn, true)
            parseBtn.text = "解析中…"
            resultCard.visibility = View.GONE
            say("", sub)
            onParseClicked(target)
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
        page.addView(hdr, match, wrap)

        val (segBox, segs) = buildSegmented(listOf("进行中", "已完成", "失败")) { i ->
            dlFilter = i
            refreshDownloadList()
        }
        dlSegs = segs
        page.addView(segBox, sideMargins().apply { topMargin = -dp(26) })

        downloadSearch = EditText(this).apply {
            hint = "搜索任务名称或状态信息"
            setHintTextColor(sub)
            setTextColor(ink)
            textSize = 14f
            singleLine = true
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
        top.addView(cover, LinearLayout.LayoutParams(dp(112), dp(70)))
        top.addView(col, LinearLayout.LayoutParams(0, wrap, 1f).apply { leftMargin = dp(12) })

        val progress = bar()
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        val primary = chip("暂停", true)
        val delete = chip("删除", false)
        actions.addView(delete, LinearLayout.LayoutParams(wrap, wrap).apply { rightMargin = dp(8) })
        actions.addView(primary)

        root.addView(top, match, wrap)
        root.addView(progress, LinearLayout.LayoutParams(match, dp(6)).apply { topMargin = dp(12) })
        root.addView(actions, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(10) })

        primary.setOnClickListener { onPrimary(item.id) }
        delete.setOnClickListener { confirmDelete(item.id) }
        return Row(root, cover, title, info, progress, primary)
    }

    private fun bindRow(item: DlItem) {
        val r = rows[item.id] ?: return
        r.title.text = item.title
        val c = item.cover
        if (c != null) r.cover.setImageBitmap(c) else r.cover.setImageDrawable(null)
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

    private fun refreshDownloadList() {
        val done = items.values.count { it.state == ST_DONE }
        val failed = items.values.count { it.state == ST_FAILED }
        val active = items.values.count { it.state == ST_DOWNLOADING || it.state == ST_WAITING || it.state == ST_PAUSED }
        dlSegs[0].text = "进行中 ($active)"
        dlSegs[1].text = "已完成 ($done)"
        dlSegs[2].text = "失败 ($failed)"
        styleSegs(dlSegs, dlFilter)
        clearTv.visibility = if (dlFilter == 1 && done > 0) View.VISIBLE else View.GONE

        listBox.removeAllViews()
        val shown = items.values.filter { item ->
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
        }.reversed()

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
                switchRow("同时下载弹幕", "danmaku", false),
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

        val (segBox, segs) = buildSegmented(listOf("扫码登录", "Cookie 登录")) { i -> selectLoginSeg(i) }
        loginSegs = segs
        col.addView(segBox, sideMargins().apply { topMargin = -dp(26) })

        // 扫码面板
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
        styleSegs(loginSegs, i)
        qrPanel.visibility = if (i == 0) View.VISIBLE else View.GONE
        cookiePanel.visibility = if (i == 1) View.VISIBLE else View.GONE
    }

    private fun showLogin() {
        selectLoginSeg(0)
        cookieErrTv.text = ""
        loginPage.visibility = View.VISIBLE
        refreshQr()
    }

    private fun hideLogin() {
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
