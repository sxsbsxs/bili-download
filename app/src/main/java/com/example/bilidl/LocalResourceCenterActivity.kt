package com.example.bilidl

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.example.bilidl.data.local.entity.DownloadResourceEntity
import com.example.bilidl.data.local.entity.DownloadResourceType
import com.example.bilidl.data.repository.BiliDownloadTaskRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Independent resource library for downloaded videos, audio, danmaku and subtitles. */
class LocalResourceCenterActivity : ComponentActivity() {
    private val repository by lazy { BiliDownloadTaskRepository.getInstance(applicationContext) }
    private lateinit var status: TextView
    private lateinit var list: LinearLayout
    private lateinit var filters: List<TextView>
    private var filter = 0
    private var pendingExport: DownloadResourceEntity? = null

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val resource = pendingExport
        pendingExport = null
        val target = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && resource != null && target != null) {
            copyToTarget(resource, target)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(247, 248, 250)
        window.navigationBarColor = Color.WHITE
        window.decorView.systemUiVisibility =
            android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        setContentView(buildPage())
        refresh()
    }

    private fun buildPage(): android.view.View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(247, 248, 250))
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        bar.addView(TextView(this).apply {
            text = "‹"
            textSize = 32f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(38, 43, 52))
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(42), dp(46)))
        bar.addView(TextView(this).apply {
            text = "本地资源"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(38, 43, 52))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(TextView(this).apply {
            text = "刷新"
            textSize = 13f
            setTextColor(Color.rgb(218, 62, 106))
            setOnClickListener { refresh() }
        })
        root.addView(bar)

        val filterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), 0, dp(12), dp(8))
        }
        val labels = listOf("全部", "媒体", "弹幕", "字幕")
        filters = labels.mapIndexed { index, label ->
            TextView(this).apply {
                text = label
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(9), dp(8), dp(9))
                setOnClickListener {
                    filter = index
                    styleFilters()
                    refresh()
                }
            }.also { view ->
                filterRow.addView(view, LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                    if (index > 0) leftMargin = dp(5)
                })
            }
        }
        root.addView(filterRow)
        status = TextView(this).apply {
            text = "正在读取资源…"
            textSize = 12f
            setTextColor(Color.rgb(123, 131, 145))
            setPadding(dp(18), dp(4), dp(18), dp(10))
        }
        root.addView(status)

        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(2), dp(12), dp(12))
        }
        scroll.addView(list, android.view.ViewGroup.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        styleFilters()
        return root
    }

    private fun styleFilters() {
        if (!::filters.isInitialized) return
        filters.forEachIndexed { index, view ->
            val selected = filter == index
            view.background = rounded(
                if (selected) Color.rgb(255, 235, 242) else Color.WHITE,
                9,
                if (selected) Color.rgb(255, 205, 221) else Color.rgb(231, 234, 239)
            )
            view.setTextColor(if (selected) Color.rgb(218, 62, 106) else Color.rgb(96, 104, 117))
            view.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
    }

    private fun refresh() {
        status.text = "正在读取已下载资源…"
        lifecycleScope.launch {
            val all = runCatching {
                withContext(Dispatchers.IO) { repository.getAllResources() }
            }.getOrElse { error ->
                status.text = error.message ?: "读取资源失败"
                emptyList()
            }
            if (isFinishing || isDestroyed) return@launch
            val filtered = all.filter { resource ->
                when (filter) {
                    1 -> resource.resourceType == DownloadResourceType.MEDIA
                    2 -> resource.resourceType == DownloadResourceType.DANMAKU_XML
                    3 -> resource.resourceType == DownloadResourceType.SUBTITLE_SRT
                    else -> true
                }
            }
            list.removeAllViews()
            status.text = if (filtered.isEmpty()) "暂无资源" else "共 ${filtered.size} 个资源"
            filtered.forEach(::addResource)
        }
    }

    private fun addResource(resource: DownloadResourceEntity) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.WHITE, 14, Color.rgb(231, 234, 239))
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val icon = TextView(this).apply {
            text = when (resource.resourceType) {
                DownloadResourceType.DANMAKU_XML -> "弹"
                DownloadResourceType.SUBTITLE_SRT -> "字"
                else -> if (resource.mimeType.startsWith("audio/")) "音" else "影"
            }
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(218, 62, 106))
            background = rounded(Color.rgb(255, 239, 245), 10, null)
        }
        top.addView(icon, LinearLayout.LayoutParams(dp(38), dp(38)))
        val copy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, 0, 0)
        }
        copy.addView(TextView(this).apply {
            text = resource.displayName
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(40, 45, 54))
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        copy.addView(TextView(this).apply {
            text = resource.mimeType + " · " + BiliDownloadEngine.formatSize(resource.sizeBytes)
            textSize = 10f
            setTextColor(Color.rgb(125, 133, 146))
            setPadding(0, dp(3), 0, 0)
        })
        top.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(top)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        if (resource.resourceType == DownloadResourceType.MEDIA) {
            actions.addView(action("信息") { showMediaInfo(resource) }, LinearLayout.LayoutParams(0, dp(38), 1f))
            actions.addView(action("播放") { playResource(resource) }, LinearLayout.LayoutParams(0, dp(38), 1f).apply {
                leftMargin = dp(6)
            })
            actions.addView(action("转换") { openConversion(resource) }, LinearLayout.LayoutParams(0, dp(38), 1f).apply {
                leftMargin = dp(6)
            })
        }
        actions.addView(action("导出") { exportResource(resource) }, LinearLayout.LayoutParams(0, dp(38), 1f).apply {
            leftMargin = dp(6)
        })
        actions.addView(action("删除", true) { confirmDelete(resource) }, LinearLayout.LayoutParams(0, dp(38), 1f).apply {
            leftMargin = dp(6)
        })
        card.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        list.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
    }

    private fun openConversion(resource: DownloadResourceEntity) {
        startActivity(Intent(this, MediaConversionActivity::class.java).apply {
            putExtra(MediaConversionActivity.EXTRA_URI, resource.contentUri)
            putExtra(MediaConversionActivity.EXTRA_TITLE, resource.displayName)
            putExtra(MediaConversionActivity.EXTRA_MIME, resource.mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }

    private fun showMediaInfo(resource: DownloadResourceEntity) {
        startActivity(Intent(this, MediaInfoActivity::class.java).apply {
            putExtra(MediaInfoActivity.EXTRA_URI, resource.contentUri)
            putExtra(MediaInfoActivity.EXTRA_TITLE, resource.displayName)
            putExtra(MediaInfoActivity.EXTRA_MIME, resource.mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }

    private fun playResource(resource: DownloadResourceEntity) {
        try {
            startActivity(
                LocalPlaybackActivity.intent(
                    Uri.parse(resource.contentUri), resource.displayName, resource.mimeType
                ).setClass(this, LocalPlaybackActivity::class.java)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (_: Exception) {
            Toast.makeText(this, "无法打开此资源", Toast.LENGTH_SHORT).show()
        }
    }

    private fun exportResource(resource: DownloadResourceEntity) {
        pendingExport = resource
        val create = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = resource.mimeType.ifBlank { "*/*" }
            putExtra(Intent.EXTRA_TITLE, resource.displayName)
        }
        exportLauncher.launch(create)
    }

    private fun copyToTarget(resource: DownloadResourceEntity, target: Uri) {
        lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openInputStream(Uri.parse(resource.contentUri)).use { input ->
                        requireNotNull(input) { "无法读取源文件" }
                        contentResolver.openOutputStream(target, "w").use { output ->
                            requireNotNull(output) { "无法写入目标文件" }
                            input.copyTo(output)
                        }
                    }
                    true
                }.getOrDefault(false)
            }
            Toast.makeText(this@LocalResourceCenterActivity, if (success) "导出成功" else "导出失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDelete(resource: DownloadResourceEntity) {
        android.app.AlertDialog.Builder(this)
            .setTitle("删除资源？")
            .setMessage("这会删除本地文件，但保留原下载任务记录。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    val success = withContext(Dispatchers.IO) {
                        runCatching {
                            contentResolver.delete(Uri.parse(resource.contentUri), null, null)
                            repository.deleteResource(resource.resourceId)
                            true
                        }.getOrDefault(false)
                    }
                    Toast.makeText(this@LocalResourceCenterActivity, if (success) "已删除" else "删除失败", Toast.LENGTH_SHORT).show()
                    refresh()
                }
            }
            .show()
    }

    private fun action(label: String, destructive: Boolean = false, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(if (destructive) Color.rgb(202, 61, 84) else Color.rgb(78, 88, 103))
            background = rounded(
                if (destructive) Color.rgb(255, 242, 244) else Color.rgb(245, 247, 249),
                9,
                null
            )
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    private fun rounded(color: Int, radius: Int, stroke: Int?): android.graphics.drawable.Drawable =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            stroke?.let { setStroke(dp(1), it) }
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
