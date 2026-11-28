package com.sxsbsxs.bilivdl.ui

import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.sxsbsxs.bilivdl.ui.databinding.FragmentDownloadBinding

/** BiliVdl download-management presentation backed by the host WorkManager/Room queue. */
class DownloadsFragment : Fragment(R.layout.fragment_download) {
    private var binding: FragmentDownloadBinding? = null
    private val handler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshTasks()
            if (binding != null) handler.postDelayed(this, 1000L)
        }
    }

    private val host: BiliVdlUiHost
        get() = requireActivity() as? BiliVdlUiHost
            ?: error("Host Activity must implement BiliVdlUiHost")

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentDownloadBinding.bind(view)
        binding?.clearCompleted?.setOnClickListener { host.clearCompletedDownloads() }
        binding?.retryFailed?.setOnClickListener { host.retryFailedDownloads() }
        binding?.clearHistory?.setOnClickListener { host.clearDownloadHistory() }
        refreshTasks()
        handler.postDelayed(refreshRunnable, 1000L)
    }

    private fun refreshTasks() {
        val b = binding ?: return
        host.loadDownloadTasks { tasks ->
            val current = binding ?: return@loadDownloadTasks
            current.downloadTaskControls.removeAllViews()
            if (tasks.isEmpty()) {
                current.downloadSummary.text = "共 0 个任务"
                current.downloadTasks.visibility = View.VISIBLE
                current.downloadTasks.text =
                    "还没有下载任务\n打开首页视频，在播放页点击“下载”即可添加任务"
                return@loadDownloadTasks
            }

            val downloading = tasks.count { it.status == "downloading" }
            val completed = tasks.count { it.status == "completed" }
            val failed = tasks.count { it.status == "failed" }
            current.downloadSummary.text = "共 " + tasks.size +
                " 个任务   ·   下载中 " + downloading +
                "   ·   已完成 " + completed +
                "   ·   失败 " + failed
            current.downloadTasks.visibility = View.GONE
            tasks.asReversed().forEach { task -> addTaskControls(task) }
        }
    }

    private fun addTaskControls(task: BiliVdlDownloadTask) {
        val b = binding ?: return
        val context = requireContext()
        val card = MaterialCardView(context).apply {
            radius = dp(15).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(context.getColor(R.color.surface_dark))
            strokeWidth = dp(1)
            setStrokeColor(context.getColor(R.color.stroke_dark))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(13), dp(14), dp(12))
        }

        val heading = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        heading.addView(TextView(context).apply {
            text = task.title
            textSize = 14f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(context.getColor(R.color.text_primary))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        heading.addView(TextView(context).apply {
            text = task.quality
            textSize = 10f
            setTextColor(context.getColor(R.color.brand))
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = context.getDrawable(R.drawable.bg_search_button)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8) }
        })
        content.addView(heading)

        val statusLabel = when (task.status) {
            "downloading" -> "正在下载 · ${task.progress.coerceIn(0, 100)}%"
            "completed" -> "已完成"
            "failed" -> "下载失败 · " + task.detail.ifBlank { "可重试" }
            "paused" -> "已暂停"
            "queued" -> "等待下载"
            "cancelled" -> "已取消"
            else -> task.detail.ifBlank { "正在等待处理" }
        }
        content.addView(TextView(context).apply {
            text = statusLabel
            textSize = 11f
            setTextColor(
                context.getColor(
                    if (task.status == "failed") R.color.brand else R.color.text_secondary
                )
            )
            setPadding(0, dp(7), 0, dp(5))
        })

        if (task.status in setOf("downloading", "queued", "paused")) {
            content.addView(ProgressBar(
                context,
                null,
                android.R.attr.progressBarStyleHorizontal
            ).apply {
                max = 100
                progress = task.progress.coerceIn(0, 100)
                progressTintList = ColorStateList.valueOf(context.getColor(R.color.brand))
                progressBackgroundTintList =
                    ColorStateList.valueOf(context.getColor(R.color.surface_elevated))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(5)
                ).apply { bottomMargin = dp(8) }
            })
        }

        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        fun action(label: String, click: () -> Unit) {
            actions.addView(MaterialButton(context).apply {
                text = label
                isAllCaps = false
                textSize = 11f
                insetTop = 0
                insetBottom = 0
                minHeight = dp(36)
                setOnClickListener { click(); refreshTasks() }
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    dp(36),
                    1f
                ).apply { marginEnd = dp(6) }
            })
        }
        when (task.status) {
            "downloading", "queued" -> action("暂停") { host.pauseDownload(task.id) }
            "paused", "failed" -> action("继续") { host.resumeDownload(task.id) }
        }
        if (task.status in setOf("downloading", "queued", "paused", "failed")) {
            action("取消") { host.cancelDownload(task.id) }
        }
        if (task.status in setOf("completed", "failed", "cancelled")) {
            action("删除") { host.deleteDownload(task.id, false) }
        }
        if (actions.childCount > 0) content.addView(actions)
        card.addView(content)
        b.downloadTaskControls.addView(card)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    override fun onDestroyView() {
        handler.removeCallbacks(refreshRunnable)
        binding = null
        super.onDestroyView()
    }
}
