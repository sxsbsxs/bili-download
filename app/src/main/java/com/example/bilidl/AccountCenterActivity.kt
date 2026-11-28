package com.example.bilidl

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** BVD-inspired personal content center: uploads, history, watch-later, likes and favorites. */
class AccountCenterActivity : ComponentActivity() {
    private val dataExecutor = Executors.newSingleThreadExecutor()
    private val imageExecutor = Executors.newFixedThreadPool(3)
    private val imageSequence = AtomicInteger()
    private val repository by lazy { BiliRepository { SecureCookieStore.load(applicationContext) } }

    private lateinit var profileName: TextView
    private lateinit var profileMeta: TextView
    private lateinit var profileAvatar: ImageView
    private lateinit var statusText: TextView
    private lateinit var contentList: LinearLayout
    private lateinit var loadMoreButton: TextView
    private lateinit var folderBackButton: TextView
    private lateinit var sectionButtons: List<TextView>
    private lateinit var submissionControls: LinearLayout
    private lateinit var submissionKeywordInput: EditText
    private lateinit var submissionPartitionButton: TextView
    private lateinit var submissionOrderButtons: List<TextView>
    private lateinit var selectionFooter: LinearLayout
    private lateinit var selectionCountText: TextView
    private val selectedUrls = LinkedHashSet<String>()

    private var profile: BiliAccountProfile? = null
    private var section = BiliAccountSection.WATCH_LATER
    private var page = 1
    private var historyCursor: BiliHistoryCursorState? = null
    private var favoriteFolderId = 0L
    private var favoriteFolderTitle = ""
    private var submissionOrder = BiliSubmissionOrder.LATEST
    private var submissionTid = 0L
    private var submissionKeyword = ""
    private var submissionPartitions: List<BiliSubmissionPartition> = emptyList()
    private var isLoading = false
    private var reloadAfterCurrent = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(247, 248, 250)
        window.navigationBarColor = Color.WHITE
        window.decorView.systemUiVisibility =
            android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        setContentView(buildContent())
        loadProfile()
    }

    private fun buildContent(): android.view.View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(247, 248, 250))
        }
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        topBar.addView(TextView(this).apply {
            text = "‹"
            textSize = 32f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(39, 44, 53))
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(42), dp(46)))
        topBar.addView(TextView(this).apply {
            text = "我的 B站"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(39, 44, 53))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        topBar.addView(TextView(this).apply {
            text = "退出登录"
            textSize = 12f
            setTextColor(Color.rgb(215, 62, 105))
            setPadding(dp(8), dp(10), dp(4), dp(10))
            setOnClickListener { confirmLogout() }
        })
        root.addView(topBar)

        val profileCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(Color.WHITE, 16, Color.rgb(231, 234, 239))
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        profileAvatar = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(Color.rgb(238, 240, 244), 32, null)
            clipToOutline = true
        }
        profileCard.addView(profileAvatar, LinearLayout.LayoutParams(dp(56), dp(56)))
        val profileCopy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        profileName = TextView(this).apply {
            text = "正在读取账号…"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(39, 44, 53))
        }
        profileMeta = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.rgb(121, 130, 144))
            setPadding(0, dp(4), 0, 0)
        }
        profileCopy.addView(profileName)
        profileCopy.addView(profileMeta)
        profileCard.addView(profileCopy, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(profileCard, LinearLayout.LayoutParams(-1, -2).apply {
            leftMargin = dp(14); rightMargin = dp(14); bottomMargin = dp(12)
        })

        val sectionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), 0, dp(12), dp(8))
        }
        sectionButtons = BiliAccountSection.values().mapIndexed { index, value ->
            TextView(this).apply {
                text = value.title
                textSize = 11f
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(10), dp(4), dp(10))
                setOnClickListener {
                    if (section != value) {
                        section = value
                        favoriteFolderId = 0L
                        favoriteFolderTitle = ""
                        historyCursor = null
                        clearSelection()
                        page = 1
                        updateSubmissionControlsVisibility()
                        styleSectionButtons()
                        loadContent(reset = true)
                    }
                }
            }.also { button ->
                sectionRow.addView(button, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                    if (index > 0) leftMargin = dp(4)
                })
            }
        }
        root.addView(sectionRow)

        submissionControls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, dp(14), dp(8))
            visibility = android.view.View.GONE
        }
        val submissionSearchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        submissionKeywordInput = EditText(this).apply {
            hint = "搜索我的投稿"
            textSize = 13f
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setTextColor(Color.rgb(40, 45, 54))
            setHintTextColor(Color.rgb(135, 142, 154))
            background = rounded(Color.WHITE, 10, Color.rgb(231, 234, 239))
            setPadding(dp(12), 0, dp(12), 0)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    runSubmissionSearch()
                    true
                } else false
            }
        }
        submissionSearchRow.addView(
            submissionKeywordInput,
            LinearLayout.LayoutParams(0, dp(42), 1f)
        )
        submissionSearchRow.addView(
            actionButton("搜索", false).apply {
                setOnClickListener { runSubmissionSearch() }
            },
            LinearLayout.LayoutParams(dp(66), dp(42)).apply { leftMargin = dp(7) }
        )
        submissionControls.addView(submissionSearchRow)

        val sortRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(7), 0, dp(6))
        }
        submissionOrderButtons = BiliSubmissionOrder.values().mapIndexed { index, value ->
            TextView(this).apply {
                text = value.title
                textSize = 11f
                gravity = Gravity.CENTER
                setPadding(dp(5), dp(8), dp(5), dp(8))
                setOnClickListener {
                    if (submissionOrder != value) {
                        submissionOrder = value
                        styleSubmissionControls()
                        loadContent(reset = true)
                    }
                }
            }.also { button ->
                sortRow.addView(button, LinearLayout.LayoutParams(0, dp(34), 1f).apply {
                    if (index > 0) leftMargin = dp(6)
                })
            }
        }
        submissionControls.addView(sortRow)

        submissionPartitionButton = TextView(this).apply {
            text = "分区：全部  ▾"
            textSize = 11f
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(Color.rgb(90, 99, 113))
            background = rounded(Color.WHITE, 9, Color.rgb(231, 234, 239))
            setPadding(dp(11), 0, dp(11), 0)
            setOnClickListener { showSubmissionPartitionPicker() }
        }
        submissionControls.addView(
            submissionPartitionButton,
            LinearLayout.LayoutParams(-1, dp(36))
        )
        root.addView(submissionControls)

        folderBackButton = TextView(this).apply {
            text = "‹  返回收藏夹"
            textSize = 12f
            setTextColor(Color.rgb(218, 63, 106))
            setPadding(dp(18), dp(4), dp(18), dp(8))
            visibility = android.view.View.GONE
            setOnClickListener {
                favoriteFolderId = 0L
                favoriteFolderTitle = ""
                clearSelection()
                page = 1
                loadContent(reset = true)
            }
        }
        root.addView(folderBackButton)

        statusText = TextView(this).apply {
            text = "正在连接 B站…"
            textSize = 12f
            setTextColor(Color.rgb(122, 131, 145))
            setPadding(dp(18), dp(4), dp(18), dp(8))
        }
        root.addView(statusText)

        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        contentList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(2), dp(12), dp(12))
        }
        scroll.addView(contentList, android.view.ViewGroup.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        selectionFooter = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = rounded(Color.WHITE, 12, Color.rgb(231, 234, 239))
            visibility = android.view.View.GONE
        }
        selectionCountText = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.rgb(76, 85, 99))
        }
        selectionFooter.addView(selectionCountText, LinearLayout.LayoutParams(0, -2, 1f))
        selectionFooter.addView(actionButton("批量下载", false).apply {
            setOnClickListener { submitBatchSelection() }
        }, LinearLayout.LayoutParams(dp(108), dp(40)))
        root.addView(selectionFooter, LinearLayout.LayoutParams(-1, dp(56)).apply {
            leftMargin = dp(16); rightMargin = dp(16); bottomMargin = dp(8)
        })

        loadMoreButton = actionButton("加载更多", false).apply {
            visibility = android.view.View.GONE
            setOnClickListener { loadContent(reset = false) }
        }
        root.addView(loadMoreButton, LinearLayout.LayoutParams(-1, dp(46)).apply {
            leftMargin = dp(16); rightMargin = dp(16)
            topMargin = dp(4); bottomMargin = dp(12)
        })
        styleSectionButtons()
        styleSubmissionControls()
        updateSubmissionControlsVisibility()
        return root
    }

    private fun runSubmissionSearch() {
        submissionKeyword = submissionKeywordInput.text?.toString()?.trim().orEmpty()
        page = 1
        loadContent(reset = true)
    }

    private fun showSubmissionPartitionPicker() {
        val names = listOf("全部分区") + submissionPartitions.map { it.name }
        android.app.AlertDialog.Builder(this)
            .setTitle("选择投稿分区")
            .setItems(names.toTypedArray()) { _, which ->
                submissionTid = if (which == 0) 0L else submissionPartitions[which - 1].tid
                submissionPartitionButton.text =
                    if (which == 0) "分区：全部  ▾" else "分区：${submissionPartitions[which - 1].name}  ▾"
                loadContent(reset = true)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun styleSubmissionControls() {
        if (!::submissionOrderButtons.isInitialized) return
        submissionOrderButtons.forEachIndexed { index, button ->
            val selected = BiliSubmissionOrder.values()[index] == submissionOrder
            button.background = rounded(
                if (selected) Color.rgb(255, 235, 242) else Color.WHITE,
                8,
                if (selected) Color.rgb(255, 205, 221) else Color.rgb(231, 234, 239)
            )
            button.setTextColor(
                if (selected) Color.rgb(218, 62, 106) else Color.rgb(96, 104, 117)
            )
            button.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
    }

    private fun updateSubmissionControlsVisibility() {
        if (::submissionControls.isInitialized) {
            submissionControls.visibility =
                if (section == BiliAccountSection.SUBMISSIONS) android.view.View.VISIBLE
                else android.view.View.GONE
        }
    }

    private fun loadProfile() {
        statusText.text = "正在读取 B站账号…"
        dataExecutor.execute {
            try {
                val account = repository.getMyAccountProfile()
                runOnUiThread {
                    profile = account
                    profileName.text = account.username
                    profileMeta.text = "UID " + account.mid
                    if (!account.faceUrl.isNullOrBlank()) loadCover(profileAvatar, account.faceUrl)
                    loadContent(reset = true)
                }
            } catch (error: Exception) {
                runOnUiThread {
                    profileName.text = "账号暂不可用"
                    profileMeta.text = "请检查登录状态后重试"
                    statusText.text = error.message ?: "读取账号失败"
                }
            }
        }
    }

    private fun loadContent(reset: Boolean) {
        val account = profile ?: return
        if (isLoading) {
            if (reset) reloadAfterCurrent = true
            return
        }
        val requestedSection = section
        val requestedPage = if (reset) 1 else page + 1
        val requestedCursor = if (reset) null else historyCursor
        val requestedFolderId = favoriteFolderId
        val requestedSubmissionOrder = submissionOrder
        val requestedSubmissionTid = submissionTid
        val requestedSubmissionKeyword = submissionKeyword
        isLoading = true
        loadMoreButton.isEnabled = false
        statusText.text = when {
            requestedSection == BiliAccountSection.FAVORITES && requestedFolderId == 0L -> "正在读取收藏夹…"
            requestedSection == BiliAccountSection.FAVORITES -> "正在读取「$favoriteFolderTitle」…"
            else -> "正在读取" + requestedSection.title + "…"
        }
        dataExecutor.execute {
            try {
                val data = repository.fetchAccountPage(
                    section = requestedSection,
                    mid = account.mid,
                    page = requestedPage,
                    historyCursor = requestedCursor,
                    favoriteFolderId = requestedFolderId,
                    submissionOrder = requestedSubmissionOrder,
                    submissionTid = requestedSubmissionTid,
                    submissionKeyword = requestedSubmissionKeyword
                )
                runOnUiThread {
                    isLoading = false
                    loadMoreButton.isEnabled = true
                    if (reloadAfterCurrent) {
                        reloadAfterCurrent = false
                        loadContent(reset = true)
                        return@runOnUiThread
                    }
                    page = requestedPage
                    historyCursor = data.nextHistoryCursor
                    if (reset) contentList.removeAllViews()
                    if (requestedSection == BiliAccountSection.SUBMISSIONS && data.partitions.isNotEmpty()) {
                        submissionPartitions = data.partitions
                    }
                    if (requestedSection == BiliAccountSection.FAVORITES && requestedFolderId == 0L) {
                        data.folders.forEach(::addFavoriteFolder)
                        statusText.text = "收藏夹 · " + data.folders.size + " 个"
                    } else {
                        data.items.forEach(::addContentItem)
                        val total = contentList.childCount
                        statusText.text = if (total == 0) "这里还没有内容"
                            else requestedSection.title + " · 已加载 " + total + " 条"
                    }
                    loadMoreButton.visibility =
                        if (data.hasMore) android.view.View.VISIBLE else android.view.View.GONE
                    if (data.items.isEmpty() && data.folders.isEmpty() && reset) {
                        statusText.text = "暂无内容"
                    }
                    folderBackButton.visibility =
                        if (requestedSection == BiliAccountSection.FAVORITES && requestedFolderId > 0L) {
                            android.view.View.VISIBLE
                        } else android.view.View.GONE
                }
            } catch (error: Exception) {
                runOnUiThread {
                    isLoading = false
                    if (reloadAfterCurrent) {
                        reloadAfterCurrent = false
                        loadMoreButton.isEnabled = true
                        loadContent(reset = true)
                        return@runOnUiThread
                    }
                    page = (requestedPage - 1).coerceAtLeast(0)
                    loadMoreButton.isEnabled = true
                    statusText.text = error.message ?: "读取" + requestedSection.title + "失败"
                    loadMoreButton.visibility =
                        if (contentList.childCount > 0) android.view.View.VISIBLE else android.view.View.GONE
                }
            }
        }
    }

    private fun addFavoriteFolder(folder: BiliFavoriteFolder) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(Color.WHITE, 13, Color.rgb(231, 234, 239))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                favoriteFolderId = folder.id
                favoriteFolderTitle = folder.title
                page = 1
                loadContent(reset = true)
            }
        }
        val cover = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(Color.rgb(237, 239, 243), 8, null)
        }
        row.addView(cover, LinearLayout.LayoutParams(dp(68), dp(60)))
        val copy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        copy.addView(TextView(this).apply {
            text = folder.title
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(42, 47, 56))
        })
        copy.addView(TextView(this).apply {
            text = folder.mediaCount.toString() + " 个内容"
            textSize = 11f
            setTextColor(Color.rgb(123, 131, 144))
            setPadding(0, dp(4), 0, 0)
        })
        row.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(TextView(this).apply {
            text = "›"
            textSize = 24f
            setTextColor(Color.rgb(140, 147, 158))
        })
        contentList.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(8)
        })
        loadCover(cover, folder.coverUrl)
    }

    private fun addContentItem(item: BiliAccountLibraryItem) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(Color.WHITE, 13, Color.rgb(231, 234, 239))
            setPadding(dp(10), dp(10), dp(10), dp(10))
            isClickable = true
            isFocusable = true
            tag = item.url
            setOnClickListener {
                if (selectedUrls.isNotEmpty()) {
                    toggleSelection(item.url)
                } else {
                    setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_SELECTED_URL, item.url))
                    finish()
                }
            }
            setOnLongClickListener {
                toggleSelection(item.url)
                true
            }
        }
        val cover = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(Color.rgb(237, 239, 243), 8, null)
        }
        row.addView(cover, LinearLayout.LayoutParams(dp(104), dp(74)))
        val copy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(11), 0, 0, 0)
        }
        copy.addView(TextView(this).apply {
            text = item.title
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(42, 47, 56))
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        copy.addView(TextView(this).apply {
            text = item.author
            textSize = 11f
            setTextColor(Color.rgb(116, 125, 139))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(4), 0, 0)
        })
        if (item.detail.isNotBlank()) {
            copy.addView(TextView(this).apply {
                text = item.detail
                textSize = 10f
                setTextColor(Color.rgb(135, 142, 154))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(2), 0, 0)
            })
        }
        row.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(TextView(this).apply {
            text = if (item.url in selectedUrls) "✓" else "○"
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(if (item.url in selectedUrls) Color.WHITE else Color.rgb(218, 69, 113))
            background = if (item.url in selectedUrls) rounded(Color.rgb(226, 69, 117), 16, null) else null
            tag = "selection"
        }, LinearLayout.LayoutParams(dp(28), dp(34)))
        contentList.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(8)
        })
        loadCover(cover, item.coverUrl)
    }

    private fun loadCover(view: ImageView, url: String?) {
        if (url.isNullOrBlank()) return
        val tag = imageSequence.incrementAndGet()
        view.tag = tag
        imageExecutor.execute {
            val bitmap = runCatching { repository.fetchCover(url) }.getOrNull()
            if (bitmap != null) runOnUiThread {
                if (view.tag == tag && !isFinishing && !isDestroyed) view.setImageBitmap(bitmap)
            }
        }
    }

    private fun toggleSelection(url: String) {
        val updated = BatchSelectionPolicy.toggle(selectedUrls.toList(), url)
        if (updated == null) {
            android.widget.Toast.makeText(this, "一次最多选择 50 项", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        selectedUrls.clear()
        selectedUrls.addAll(updated)
        updateSelectionUi()
    }

    private fun clearSelection() {
        selectedUrls.clear()
        updateSelectionUi()
    }

    private fun updateSelectionUi() {
        if (!::selectionFooter.isInitialized) return
        selectionFooter.visibility = if (selectedUrls.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        selectionCountText.text = "已选 " + selectedUrls.size + " / 50 项"
        for (index in 0 until contentList.childCount) {
            val row = contentList.getChildAt(index) as? LinearLayout ?: continue
            val url = row.tag as? String ?: continue
            val indicator = row.getChildAt(row.childCount - 1) as? TextView ?: continue
            val selected = url in selectedUrls
            indicator.text = if (selected) "✓" else "○"
            indicator.setTextColor(if (selected) Color.WHITE else Color.rgb(218, 69, 113))
            indicator.background = if (selected) rounded(Color.rgb(226, 69, 117), 16, null) else null
            row.background = rounded(
                if (selected) Color.rgb(255, 246, 249) else Color.WHITE,
                13,
                if (selected) Color.rgb(255, 204, 220) else Color.rgb(231, 234, 239)
            )
        }
    }

    private fun submitBatchSelection() {
        if (selectedUrls.isEmpty()) return
        setResult(
            Activity.RESULT_OK,
            Intent().putStringArrayListExtra(EXTRA_SELECTED_URLS, ArrayList(selectedUrls))
        )
        finish()
    }

    private fun styleSectionButtons() {
        if (!::sectionButtons.isInitialized) return
        sectionButtons.forEachIndexed { index, button ->
            val selected = BiliAccountSection.values()[index] == section
            button.background = rounded(
                if (selected) Color.rgb(255, 235, 242) else Color.WHITE,
                9,
                if (selected) Color.rgb(255, 205, 221) else Color.rgb(231, 234, 239)
            )
            button.setTextColor(if (selected) Color.rgb(218, 62, 106) else Color.rgb(96, 104, 117))
            button.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
    }

    private fun confirmLogout() {
        android.app.AlertDialog.Builder(this)
            .setTitle("退出 B站账号？")
            .setMessage("退出后，受账号权限影响的画质和个人内容将不可用。")
            .setNegativeButton("取消", null)
            .setPositiveButton("退出登录") { _, _ ->
                setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_LOGOUT, true))
                finish()
            }
            .show()
    }

    private fun actionButton(label: String, primary: Boolean): TextView =
        TextView(this).apply {
            text = label
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(if (primary) Color.WHITE else Color.rgb(92, 101, 115))
            background = rounded(
                if (primary) Color.rgb(226, 69, 117) else Color.WHITE,
                12,
                if (primary) null else Color.rgb(224, 228, 235)
            )
            isClickable = true
            isFocusable = true
        }

    private fun rounded(color: Int, radius: Int, stroke: Int?): android.graphics.drawable.Drawable =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            stroke?.let { setStroke(dp(1), it) }
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        dataExecutor.shutdownNow()
        imageExecutor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SELECTED_URL = "com.example.bilidl.extra.ACCOUNT_CONTENT_URL"
        const val EXTRA_SELECTED_URLS = "com.example.bilidl.extra.ACCOUNT_CONTENT_URLS"
        const val EXTRA_LOGOUT = "com.example.bilidl.extra.ACCOUNT_LOGOUT"
    }
}
