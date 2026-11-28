package com.sxsbsxs.bilivdl.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
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
                current.downloadTasks.text =
                    "还没有下载任务\n打开首页视频，在播放页点击“下载”即可添加任务"
                return@loadDownloadTasks
            }

            val downloading = tasks.count { it.status == "downloading" }
            val completed = tasks.count { it.status == "completed" }
            val failed = tasks.count { it.status == "failed" }
            current.downloadSummary.text = "共 " + tasks.size +
                " 个任务 · 下载中 " + downloading +
                " · 已完成 " + completed + " · 失败 " + failed

            current.downloadTasks.text = tasks.asReversed().joinToString("\n\n") { task ->
                val label = when (task.status) {
                    "downloading" -> "正在下载 · " + task.progress + "%"
                    "completed" -> "已完成"
                    "failed" -> "下载失败 · " + task.detail
                    "paused" -> "已暂停"
                    else -> task.detail.ifBlank { "等待处理" }
                }
                "• " + task.title + "（" + task.quality + "）\n  " + label
            }
            tasks.asReversed().forEach { task -> addTaskControls(task) }
        }
    }

    private fun addTaskControls(task: BiliVdlDownloadTask) {
        val b = binding ?: return
        val context = requireContext()
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14, 10, 14, 10)
            background = context.getDrawable(R.drawable.bg_empty_card)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 8 }
        }
        row.addView(TextView(context).apply {
            text = task.title
            textSize = 14f
            setTextColor(context.getColor(R.color.text_primary))
        })
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        fun action(label: String, click: () -> Unit) {
            actions.addView(MaterialButton(context).apply {
                text = label
                isAllCaps = false
                setOnClickListener { click(); refreshTasks() }
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                ).apply { marginEnd = 6 }
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
        row.addView(actions)
        b.downloadTaskControls.addView(row)
    }

    override fun onDestroyView() {
        handler.removeCallbacks(refreshRunnable)
        binding = null
        super.onDestroyView()
    }
}
