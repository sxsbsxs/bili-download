package com.example.bilidl

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** User-facing MP4 remux and AAC extraction using Android's native media APIs. */
class MediaConversionActivity : ComponentActivity() {
    private var sourceUri: Uri? = null
    private var sourceTitle = ""
    private var sourceMime = ""
    private var selectedMode: NativeMediaConversionMode? = null
    private var completedUri: Uri? = null

    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var convertButtons: List<TextView>
    private lateinit var openResultButton: TextView

    private val outputPicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val mode = selectedMode
        val target = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && target != null && mode != null) {
            performConversion(target, mode)
        } else {
            selectedMode = null
            status.text = "已取消"
            setButtonsEnabled(true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sourceUri = intent.getStringExtra(EXTRA_URI)?.let(Uri::parse)
        sourceTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "BiliDl media" }
        sourceMime = intent.getStringExtra(EXTRA_MIME).orEmpty()
        if (sourceUri == null) {
            finish()
            return
        }
        window.statusBarColor = Color.rgb(247, 248, 250)
        window.navigationBarColor = Color.WHITE
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        setContentView(buildPage())
    }

    private fun buildPage(): View {
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
            text = "媒体转换"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(38, 43, 52))
        })
        root.addView(bar)

        root.addView(TextView(this).apply {
            text = sourceTitle
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(40, 45, 54))
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(18), dp(8), dp(18), dp(4))
        })
        root.addView(TextView(this).apply {
            text = "选择一种无损处理方式。不会重新压缩，也不会改变原文件。"
            textSize = 12f
            setTextColor(Color.rgb(121, 130, 144))
            setPadding(dp(18), 0, dp(18), dp(16))
        })

        val mp4Card = optionCard(
            NativeMediaConversionMode.MP4_REMUX.title,
            NativeMediaConversionMode.MP4_REMUX.description
        ) {
            chooseOutput(NativeMediaConversionMode.MP4_REMUX)
        }
        val audioCard = optionCard(
            NativeMediaConversionMode.AAC_TO_M4A.title,
            NativeMediaConversionMode.AAC_TO_M4A.description
        ) {
            chooseOutput(NativeMediaConversionMode.AAC_TO_M4A)
        }
        convertButtons = listOf(mp4Card.second, audioCard.second)
        root.addView(mp4Card.first, LinearLayout.LayoutParams(-1, -2).apply {
            leftMargin = dp(14); rightMargin = dp(14); bottomMargin = dp(10)
        })
        root.addView(audioCard.first, LinearLayout.LayoutParams(-1, -2).apply {
            leftMargin = dp(14); rightMargin = dp(14)
        })

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            visibility = View.GONE
        }
        root.addView(progress, LinearLayout.LayoutParams(-1, dp(6)).apply {
            topMargin = dp(22); leftMargin = dp(18); rightMargin = dp(18)
        })
        status = TextView(this).apply {
            text = "选择输出格式后，再选择保存位置"
            textSize = 12f
            setTextColor(Color.rgb(121, 130, 144))
            setPadding(dp(18), dp(12), dp(18), dp(10))
        }
        root.addView(status)

        openResultButton = button("播放转换结果").apply {
            visibility = View.GONE
            setOnClickListener { openCompletedResult() }
        }
        root.addView(openResultButton, LinearLayout.LayoutParams(-1, dp(46)).apply {
            leftMargin = dp(16); rightMargin = dp(16); topMargin = dp(8)
        })

        root.addView(TextView(this).apply {
            text = "说明：这是重新封装/提取音轨，不是编码转码。若源文件编码或容器不受 Android 媒体封装器支持，会提示失败；不会损坏原文件。"
            textSize = 11f
            setTextColor(Color.rgb(135, 142, 154))
            setPadding(dp(18), dp(20), dp(18), dp(18))
        })
        return root
    }

    private fun optionCard(
        title: String,
        description: String,
        onClick: () -> Unit
    ): Pair<LinearLayout, TextView> {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.WHITE, 14, Color.rgb(231, 234, 239))
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        card.addView(TextView(this).apply {
            text = title
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(40, 45, 54))
        })
        card.addView(TextView(this).apply {
            text = description
            textSize = 11f
            setTextColor(Color.rgb(121, 130, 144))
            setPadding(0, dp(5), 0, dp(10))
        })
        val action = button("选择保存位置").apply { setOnClickListener { onClick() } }
        card.addView(action, LinearLayout.LayoutParams(-1, dp(40)))
        return card to action
    }

    private fun chooseOutput(mode: NativeMediaConversionMode) {
        selectedMode = mode
        val create = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mode.mimeType
            putExtra(
                Intent.EXTRA_TITLE,
                NativeMediaConverter.suggestOutputName(sourceTitle, mode.extension)
            )
        }
        outputPicker.launch(create)
    }

    private fun performConversion(target: Uri, mode: NativeMediaConversionMode) {
        setButtonsEnabled(false)
        progress.visibility = View.VISIBLE
        progress.progress = 0
        status.text = "正在准备媒体轨道…"
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    NativeMediaConverter.convert(
                        context = applicationContext,
                        source = requireNotNull(sourceUri),
                        target = target,
                        mode = mode
                    ) { percent ->
                        runOnUiThread {
                            progress.progress = percent
                            status.text = "正在处理… " + percent + "%"
                        }
                    }
                }
            }
            result.onSuccess { converted ->
                completedUri = target
                status.text = "完成 · " + BiliDownloadEngine.formatSize(converted.bytesWritten) +
                    " · " + converted.sampleCount + " 个媒体数据块"
                openResultButton.visibility = View.VISIBLE
                setButtonsEnabled(true)
                Toast.makeText(this@MediaConversionActivity, "转换完成", Toast.LENGTH_SHORT).show()
            }.onFailure { error ->
                progress.visibility = View.GONE
                status.text = error.message ?: "转换失败"
                setButtonsEnabled(true)
                Toast.makeText(this@MediaConversionActivity, "转换失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openCompletedResult() {
        val uri = completedUri ?: return
        val mode = selectedMode ?: return
        runCatching {
            startActivity(
                LocalPlaybackActivity.intent(
                    uri,
                    NativeMediaConverter.suggestOutputName(sourceTitle, mode.extension),
                    mode.mimeType
                ).setClass(this, LocalPlaybackActivity::class.java)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        }.onFailure {
            status.text = "结果已保存，但无法在应用内打开：" + (it.message ?: "未知错误")
        }
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        if (::convertButtons.isInitialized) convertButtons.forEach { it.isEnabled = enabled }
    }

    private fun button(label: String): TextView =
        TextView(this).apply {
            text = label
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = rounded(Color.rgb(226, 69, 117), 10, null)
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

    companion object {
        const val EXTRA_URI = "com.example.bilidl.extra.CONVERT_URI"
        const val EXTRA_TITLE = "com.example.bilidl.extra.CONVERT_TITLE"
        const val EXTRA_MIME = "com.example.bilidl.extra.CONVERT_MIME"
    }
}
