package com.example.bilidl

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
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

    private var profile: BiliAccountProfile? = null
    private var section = BiliAccountSection.WATCH_LATER
    private var page = 1
    private var historyCursor = 0L
    private var favoriteFolderId = 0L
    private var favoriteFolderTitle = ""
    private var isLoading = false

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
                        historyCursor = 0L
                        page = 1
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

        folderBackButton = TextView(this).apply {
            text = "‹  返回收藏夹"
            textSize = 12f
            setTextColor(Color.rgb(218, 63, 106))
            setPadding(dp(18), dp(4), dp(18), dp(8))
            visibility = android.view.View.GONE
            setOnClickListener {
                favoriteFolderId = 0L
                favoriteFolderTitle = ""
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

        loadMoreButton = actionButton("加载更多", false).apply {
            visibility = android.view.View.GONE
            setOnClickListener { loadContent(reset = false) }
        }
        root.addView(loadMoreButton, LinearLayout.LayoutParams(-1, dp(46)).apply {
            leftMargin = dp(16); rightMargin = dp(16)
            topMargin = dp(4); bottomMargin = dp(12)
        })
        styleSectionButtons()
        return root
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
        if (isLoading) return
        val requestedPage = if (reset) 1 else page + 1
        val requestedCursor = if (reset) 0L else historyCursor
        isLoading = true
        loadMoreButton.isEnabled = false
        statusText.text = when {
            section == BiliAccountSection.FAVORITES && favoriteFolderId == 0L -> "正在读取收藏夹…"
            section == BiliAccountSection.FAVORITES -> "正在读取「$favoriteFolderTitle」…"
            else -> "正在读取" + section.title + "…"
        }
        dataExecutor.execute {
            try {
                val data = repository.fetchAccountPage(
                    section, account.mid, requestedPage, requestedCursor, favoriteFolderId
                )
                runOnUiThread {
                    isLoading = false
                    page = requestedPage
                    historyCursor = data.nextCursor
                    loadMoreButton.isEnabled = true
                    if (reset) contentList.removeAllViews()
                    if (section == BiliAccountSection.FAVORITES && favoriteFolderId == 0L) {
                        data.folders.forEach(::addFavoriteFolder)
                        statusText.text = "收藏夹 · " + data.folders.size + " 个"
                    } else {
                        data.items.forEach(::addContentItem)
                        val total = contentList.childCount
                        statusText.text = if (total == 0) "这里还没有内容"
                            else section.title + " · 已加载 " + total + " 条"
                    }
                    loadMoreButton.visibility =
                        if (data.hasMore) android.view.View.VISIBLE else android.view.View.GONE
                    if (data.items.isEmpty() && data.folders.isEmpty() && reset) {
                        statusText.text = "暂无内容"
                    }
                    folderBackButton.visibility =
                        if (section == BiliAccountSection.FAVORITES && favoriteFolderId > 0L) {
                            android.view.View.VISIBLE
                        } else android.view.View.GONE
                }
            } catch (error: Exception) {
                runOnUiThread {
                    isLoading = false
                    page = (requestedPage - 1).coerceAtLeast(0)
                    loadMoreButton.isEnabled = true
                    statusText.text = error.message ?: "读取" + section.title + "失败"
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
            setOnClickListener {
                setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_SELECTED_URL, item.url))
                finish()
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
        const val EXTRA_LOGOUT = "com.example.bilidl.extra.ACCOUNT_LOGOUT"
    }
}
