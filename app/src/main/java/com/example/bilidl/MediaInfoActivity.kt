package com.example.bilidl

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private data class MediaTrackSummary(
    val index: Int,
    val type: String,
    val codec: String,
    val details: List<Pair<String, String>>
)

private data class MediaInspectionSummary(
    val container: String,
    val mimeType: String,
    val fileSize: Long?,
    val durationUs: Long,
    val tracks: List<MediaTrackSummary>
)

/**
 * Reads container and track metadata using Android's MediaExtractor.
 * No bundled FFmpeg runtime is required for basic stream inspection.
 */
class MediaInfoActivity : ComponentActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var status: TextView
    private lateinit var rows: LinearLayout
    private lateinit var playButton: TextView
    private var mediaUri: Uri? = null
    private var mediaTitle: String = ""
    private var mimeType: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mediaUri = intent.getStringExtra(EXTRA_URI)?.let(Uri::parse)
        mediaTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "本地媒体" }
        mimeType = intent.getStringExtra(EXTRA_MIME).orEmpty()
        val uri = mediaUri
        if (uri == null) {
            finish()
            return
        }
        setContentView(buildPage())
        inspect(uri)
    }

    private fun buildPage(): android.view.View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(247, 248, 250))
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        top.addView(TextView(this).apply {
            text = "‹"
            textSize = 32f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(38, 43, 52))
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(42), dp(46)))
        top.addView(TextView(this).apply {
            text = "媒体信息"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(38, 43, 52))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(top)

        val title = TextView(this).apply {
            text = mediaTitle
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(40, 45, 54))
            setPadding(dp(18), dp(6), dp(18), dp(8))
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        root.addView(title)

        status = TextView(this).apply {
            text = "正在读取媒体轨道…"
            textSize = 12f
            setTextColor(Color.rgb(123, 131, 145))
            setPadding(dp(18), 0, dp(18), dp(10))
        }
        root.addView(status)

        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        rows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(2), dp(12), dp(12))
        }
        scroll.addView(rows, android.view.ViewGroup.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        playButton = TextView(this).apply {
            text = "在播放器中打开"
            textSize = 14f
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = rounded(Color.rgb(226, 69, 117), 12)
            isClickable = true
            isFocusable = true
            setOnClickListener { openPlayer() }
        }
        root.addView(playButton, LinearLayout.LayoutParams(-1, dp(46)).apply {
            leftMargin = dp(16); rightMargin = dp(16); bottomMargin = dp(14)
        })
        return root
    }

    private fun inspect(uri: Uri) {
        status.text = "正在读取媒体轨道…"
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { readMediaInfo(uri) }
            }
            result.onSuccess { summary ->
                rows.removeAllViews()
                addSection("文件")
                addRow("容器 / 扩展名", summary.container)
                addRow("MIME 类型", summary.mimeType)
                summary.fileSize?.let { addRow("文件大小", BiliDownloadEngine.formatSize(it)) }
                if (summary.durationUs > 0L) addRow("总时长", formatDuration(summary.durationUs))
                addSection("媒体轨道（${summary.tracks.size}）")
                if (summary.tracks.isEmpty()) {
                    addRow("轨道", "系统没有识别到可读取的音视频轨道")
                } else {
                    summary.tracks.forEach { track ->
                        addSection("${track.type} #${track.index + 1} · ${track.codec}")
                        track.details.forEach { (label, value) -> addRow(label, value) }
                    }
                }
                status.text = "媒体信息读取完成"
            }.onFailure { error ->
                status.text = error.message ?: "读取媒体信息失败"
                addSection("无法读取")
                addRow("原因", status.text.toString())
            }
        }
    }

    private fun readMediaInfo(uri: Uri): MediaInspectionSummary {
        val extension = mediaTitle.substringAfterLast('.', "").takeIf { it.isNotBlank() } ?: "未知"
        val actualMime = mimeType.ifBlank { contentResolver.getType(uri) ?: "未知" }
        val size = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst() && sizeColumn >= 0) cursor.getLong(sizeColumn).takeIf { it > 0L } else null
            }
        }.getOrNull()
        val extractor = MediaExtractor()
        val tracks = mutableListOf<MediaTrackSummary>()
        var duration = 0L
        try {
            extractor.setDataSource(this, uri, null)
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val streamMime = runCatching { format.getString(MediaFormat.KEY_MIME) }.getOrNull()
                    ?: "application/octet-stream"
                val type = when {
                    streamMime.startsWith("video/") -> "视频"
                    streamMime.startsWith("audio/") -> "音频"
                    streamMime.startsWith("text/") || streamMime.contains("subtitle") -> "字幕"
                    else -> "其他"
                }
                val details = mutableListOf<Pair<String, String>>()
                fun addInt(key: String, label: String, suffix: String = "") {
                    if (format.containsKey(key)) {
                        val value = runCatching { format.getInteger(key) }.getOrNull()
                        if (value != null && value > 0) details += label to (value.toString() + suffix)
                    }
                }
                if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    val trackDuration = runCatching { format.getLong(MediaFormat.KEY_DURATION) }.getOrDefault(0L)
                    if (trackDuration > duration) duration = trackDuration
                }
                if (type == "视频") {
                    val width = runCatching { format.getInteger(MediaFormat.KEY_WIDTH) }.getOrDefault(0)
                    val height = runCatching { format.getInteger(MediaFormat.KEY_HEIGHT) }.getOrDefault(0)
                    if (width > 0 && height > 0) details += "分辨率" to "${width} × ${height}"
                    addInt(MediaFormat.KEY_FRAME_RATE, "帧率", " fps")
                    addInt(MediaFormat.KEY_BIT_RATE, "码率", " bit/s")
                    addInt(MediaFormat.KEY_ROTATION, "旋转", "°")
                } else if (type == "音频") {
                    addInt(MediaFormat.KEY_SAMPLE_RATE, "采样率", " Hz")
                    addInt(MediaFormat.KEY_CHANNEL_COUNT, "声道数")
                    addInt(MediaFormat.KEY_BIT_RATE, "码率", " bit/s")
                }
                val language = runCatching { format.getString(MediaFormat.KEY_LANGUAGE) }.getOrNull()
                if (!language.isNullOrBlank() && language != "und") details += "语言" to language
                tracks += MediaTrackSummary(index, type, streamMime.substringAfter('/', streamMime), details)
            }
        } catch (error: Exception) {
            throw IllegalStateException("系统无法解析此媒体容器：${error.message ?: error.javaClass.simpleName}", error)
        } finally {
            extractor.release()
        }
        return MediaInspectionSummary(extension.uppercase(Locale.US), actualMime, size, duration, tracks)
    }

    private fun addSection(title: String) {
        rows.addView(TextView(this).apply {
            text = title
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(218, 62, 106))
            setPadding(dp(6), dp(12), dp(6), dp(6))
        })
    }

    private fun addRow(label: String, value: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(Color.WHITE, 10)
            setPadding(dp(12), dp(11), dp(12), dp(11))
        }
        row.addView(TextView(this).apply {
            text = label
            textSize = 12f
            setTextColor(Color.rgb(112, 120, 133))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(TextView(this).apply {
            text = value
            textSize = 12f
            setTextColor(Color.rgb(42, 47, 56))
            gravity = Gravity.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
        rows.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(5)
        })
    }

    private fun openPlayer() {
        val uri = mediaUri ?: return
        runCatching {
            startActivity(
                LocalPlaybackActivity.intent(uri, mediaTitle, mimeType)
                    .setClass(this, LocalPlaybackActivity::class.java)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        }.onFailure {
            status.text = "无法打开播放器：${it.message ?: "未知错误"}"
        }
    }

    private fun formatDuration(durationUs: Long): String {
        val seconds = (durationUs / 1_000_000L).coerceAtLeast(0L)
        val hours = seconds / 3600L
        val minutes = seconds % 3600L / 60L
        val remainder = seconds % 60L
        return if (hours > 0L) {
            String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, remainder)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, remainder)
        }
    }

    private fun rounded(color: Int, radius: Int): android.graphics.drawable.Drawable =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URI = "com.example.bilidl.extra.MEDIA_INFO_URI"
        const val EXTRA_TITLE = "com.example.bilidl.extra.MEDIA_INFO_TITLE"
        const val EXTRA_MIME = "com.example.bilidl.extra.MEDIA_INFO_MIME"
    }
}
