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

/**
 * BVD-style Bilibili site search for videos, anime and film/TV.
 * Selecting a result returns its canonical URL to MainActivity's normal parser.
 */
class SearchActivity : ComponentActivity() {

    private val searchExecutor = Executors.newSingleThreadExecutor()
    private val coverExecutor = Executors.newFixedThreadPool(3)
    private val coverSequence = AtomicInteger()

    private lateinit var keywordInput: EditText
    private lateinit var statusText: TextView
    private lateinit var resultList: LinearLayout
    private lateinit var loadMoreButton: TextView
    private lateinit var categoryButtons: List<TextView>
    private lateinit var selectionFooter: LinearLayout
    private lateinit var selectionCountText: TextView
    private val selectedUrls = LinkedHashSet<String>()

    private var category = BiliSearchCategory.VIDEO
    private var page = 1
    private var isLoading = false
    private var currentKeyword = ""
    private val repository by lazy {
        BiliRepository { SecureCookieStore.load(applicationContext) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(247, 248, 250)
        window.navigationBarColor = Color.WHITE
        window.decorView.systemUiVisibility =
            android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        setContentView(buildContent())
    }

    private fun buildContent(): android.view.View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(247, 248, 250))
        }

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val back = TextView(this).apply {
            text = "‹"
            textSize = 32f
            setTextColor(Color.rgb(38, 42, 50))
            gravity = Gravity.CENTER
            setOnClickListener { finish() }
        }
        toolbar.addView(back, LinearLayout.LayoutParams(dp(42), dp(46)))
        toolbar.addView(TextView(this).apply {
            text = "搜索哔哩哔哩"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(38, 42, 50))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(toolbar)

        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(6), dp(16), dp(10))
        }
        keywordInput = EditText(this).apply {
            hint = "搜索视频、番剧或影视"
            textSize = 14f
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setTextColor(Color.rgb(37, 42, 51))
            setHintTextColor(Color.rgb(135, 143, 155))
            background = rounded(Color.WHITE, 12, Color.rgb(226, 230, 236))
            setPadding(dp(14), 0, dp(14), 0)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    startSearch(clear = true)
                    true
                } else false
            }
        }
        searchRow.addView(keywordInput, LinearLayout.LayoutParams(0, dp(46), 1f))
        val searchButton = actionButton("搜索", true)
        searchButton.setOnClickListener { startSearch(clear = true) }
        searchRow.addView(searchButton, LinearLayout.LayoutParams(dp(68), dp(46)).apply {
            leftMargin = dp(8)
        })
        root.addView(searchRow)

        val categoryRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), 0, dp(16), dp(8))
        }
        categoryButtons = BiliSearchCategory.values().mapIndexed { index, value ->
            TextView(this).apply {
                text = value.displayName
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(dp(17), dp(9), dp(17), dp(9))
                setOnClickListener {
                    if (category != value) {
                        category = value
                        clearSelection()
                        styleCategoryButtons()
                        if (keywordInput.text.toString().isNotBlank()) startSearch(clear = true)
                    }
                }
            }.also { button ->
                categoryRow.addView(button, LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                    if (index > 0) leftMargin = dp(6)
                })
            }
        }
        root.addView(categoryRow)

        statusText = TextView(this).apply {
            text = "搜索视频、番剧和影视；长按结果或点圆圈可多选"
            textSize = 12f
            setTextColor(Color.rgb(125, 133, 146))
            setPadding(dp(18), dp(5), dp(18), dp(10))
        }
        root.addView(statusText)

        val scroll = ScrollView(this).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = false
        }
        resultList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(2), dp(12), dp(12))
        }
        scroll.addView(resultList, android.view.ViewGroup.LayoutParams(-1, -2))
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
        selectionFooter.addView(actionButton("批量下载", true).apply {
            setOnClickListener { submitBatchSelection() }
        }, LinearLayout.LayoutParams(dp(108), dp(40)))
        root.addView(selectionFooter, LinearLayout.LayoutParams(-1, dp(56)).apply {
            leftMargin = dp(16); rightMargin = dp(16); bottomMargin = dp(8)
        })

        loadMoreButton = actionButton("加载更多", false).apply {
            visibility = android.view.View.GONE
            setOnClickListener { startSearch(clear = false) }
        }
        root.addView(loadMoreButton, LinearLayout.LayoutParams(-1, dp(46)).apply {
            leftMargin = dp(16)
            rightMargin = dp(16)
            bottomMargin = dp(12)
        })

        styleCategoryButtons()
        return root
    }

    private fun styleCategoryButtons() {
        categoryButtons.forEachIndexed { index, button ->
            val selected = BiliSearchCategory.values()[index] == category
            button.background = rounded(
                if (selected) Color.rgb(255, 235, 242) else Color.WHITE,
                10,
                if (selected) Color.rgb(255, 205, 221) else Color.rgb(231, 234, 239)
            )
            button.setTextColor(if (selected) Color.rgb(222, 54, 108) else Color.rgb(93, 102, 116))
            button.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
    }

    private fun startSearch(clear: Boolean) {
        val keyword = keywordInput.text?.toString()?.trim().orEmpty()
        if (keyword.isBlank()) {
            keywordInput.error = "请输入搜索关键词"
            return
        }
        if (isLoading) return

        val requestedPage = if (clear || keyword != currentKeyword) {
            currentKeyword = keyword
            page = 1
            resultList.removeAllViews()
            loadMoreButton.visibility = android.view.View.GONE
            1
        } else {
            (page + 1).also { page = it }
        }

        isLoading = true
        statusText.text = "正在搜索「$keyword」…"
        loadMoreButton.isEnabled = false
        searchExecutor.execute {
            try {
                val results = repository.searchBilibili(currentKeyword, requestedPage, category)
                runOnUiThread {
                    isLoading = false
                    loadMoreButton.isEnabled = true
                    if (results.isEmpty() && page == 1) {
                        statusText.text = "没有找到相关内容，试试更换关键词"
                        loadMoreButton.visibility = android.view.View.GONE
                    } else {
                        statusText.text = "「$currentKeyword」 · 已加载 " + (resultList.childCount + results.size) + " 条结果"
                        results.forEach(::addResultRow)
                        loadMoreButton.visibility =
                            if (results.size >= PAGE_SIZE_HINT) android.view.View.VISIBLE
                            else android.view.View.GONE
                    }
                }
            } catch (error: Exception) {
                runOnUiThread {
                    isLoading = false
                    page = (requestedPage - 1).coerceAtLeast(0)
                    loadMoreButton.isEnabled = true
                    statusText.text = error.message ?: "搜索失败，请稍后重试"
                    loadMoreButton.visibility = if (resultList.childCount > 0) android.view.View.VISIBLE else android.view.View.GONE
                }
            }
        }
    }

    private fun addResultRow(item: BiliSearchResult) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(
                if (item.url in selectedUrls) Color.rgb(255, 246, 249) else Color.WHITE,
                14,
                if (item.url in selectedUrls) Color.rgb(255, 204, 220) else Color.rgb(232, 235, 240)
            )
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

        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(Color.rgb(238, 240, 244), 9, null)
            contentDescription = "视频封面"
        }
        row.addView(image, LinearLayout.LayoutParams(dp(112), dp(78)))

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(11), 0, 0, 0)
        }
        info.addView(TextView(this).apply {
            text = item.title
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(40, 45, 54))
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(-1, -2))
        info.addView(TextView(this).apply {
            text = item.author
            textSize = 11f
            setTextColor(Color.rgb(112, 120, 133))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(5)
        })
        if (item.detail.isNotBlank()) {
            info.addView(TextView(this).apply {
                text = item.detail
                textSize = 10f
                setTextColor(Color.rgb(135, 142, 153))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(3)
            })
        }
        row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(TextView(this).apply {
            text = if (item.url in selectedUrls) "✓" else "○"
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(if (item.url in selectedUrls) Color.WHITE else Color.rgb(218, 69, 113))
            background = if (item.url in selectedUrls) rounded(Color.rgb(226, 69, 117), 16, null) else null
            tag = "selection"
            setOnClickListener { toggleSelection(item.url) }
        }, LinearLayout.LayoutParams(dp(28), dp(34)))
        resultList.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(8)
        })

        val tag = coverSequence.incrementAndGet()
        image.tag = tag
        if (!item.coverUrl.isNullOrBlank()) {
            coverExecutor.execute {
                val bitmap = runCatching { repository.fetchCover(item.coverUrl) }.getOrNull()
                if (bitmap != null) runOnUiThread {
                    if (image.tag == tag && !isFinishing && !isDestroyed) image.setImageBitmap(bitmap)
                }
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
        for (index in 0 until resultList.childCount) {
            val row = resultList.getChildAt(index) as? LinearLayout ?: continue
            val url = row.tag as? String ?: continue
            val indicator = row.getChildAt(row.childCount - 1) as? TextView ?: continue
            val selected = url in selectedUrls
            indicator.text = if (selected) "✓" else "○"
            indicator.setTextColor(if (selected) Color.WHITE else Color.rgb(218, 69, 113))
            indicator.background = if (selected) rounded(Color.rgb(226, 69, 117), 16, null) else null
            row.background = rounded(
                if (selected) Color.rgb(255, 246, 249) else Color.WHITE,
                14,
                if (selected) Color.rgb(255, 204, 220) else Color.rgb(232, 235, 240)
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
        searchExecutor.shutdownNow()
        coverExecutor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SELECTED_URL = "com.example.bilidl.extra.SEARCH_RESULT_URL"
        const val EXTRA_SELECTED_URLS = "com.example.bilidl.extra.SEARCH_RESULT_URLS"
        private const val PAGE_SIZE_HINT = 20
    }
}
