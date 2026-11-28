package com.example.bilidl

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity

/**
 * BVD-style official Bilibili mobile Passport login page.
 *
 * This Activity never treats a page load as a successful login. It only hands
 * a cookie header to MainActivity after the Passport flow returns to
 * m.bilibili.com and SESSDATA is present; LoginViewModel then verifies it
 * against Bilibili before saving it to Android Keystore.
 */
class BiliWebLoginActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private var completed = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(8))
        }
        toolbar.addView(TextView(this).apply {
            text = "‹"
            textSize = 32f
            setTextColor(Color.rgb(35, 39, 47))
            gravity = Gravity.CENTER
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(44), dp(48)))
        toolbar.addView(TextView(this).apply {
            text = "B站账号登录"
            textSize = 17f
            setTextColor(Color.rgb(35, 39, 47))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(toolbar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        root.addView(progress, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(3)
        ))

        status = TextView(this).apply {
            text = "正在打开 B站官方登录页面…"
            textSize = 12f
            setTextColor(Color.rgb(105, 112, 124))
            setPadding(dp(18), dp(8), dp(18), dp(8))
        }
        root.addView(status, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.loadsImagesAutomatically = true
            settings.setSupportMultipleWindows(false)
            webViewClient = createWebViewClient()
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    this@BiliWebLoginActivity.progress.progress = newProgress
                    this@BiliWebLoginActivity.progress.visibility = if (newProgress >= 100) android.view.View.GONE else android.view.View.VISIBLE
                }
            }
        }
        root.addView(webView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        setContentView(root)

        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(webView, true)
        // Match BVD's clean login session: do not let an old WebView session
        // silently select a different account from the one the user intends.
        cookies.removeAllCookies {
            cookies.flush()
            webView.post {
                if (!isFinishing && !isDestroyed) {
                    webView.loadUrl(BiliWebLoginPolicy.LOGIN_URL)
                }
            }
        }
    }

    private fun createWebViewClient() = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest
        ): Boolean = captureLoginCookie(request.url.toString())

        @Suppress("OVERRIDE_DEPRECATION")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
            captureLoginCookie(url)

        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
            super.onPageStarted(view, url, favicon)
            progress.visibility = android.view.View.VISIBLE
            status.text = "请在 B站官方页面完成登录…"
        }

        override fun onPageFinished(view: WebView, url: String?) {
            super.onPageFinished(view, url)
            progress.visibility = android.view.View.GONE
            if (!url.isNullOrBlank()) captureLoginCookie(url)
        }
    }

    private fun captureLoginCookie(url: String): Boolean {
        if (completed || !BiliWebLoginPolicy.isCallbackUrl(url)) return completed

        val manager = CookieManager.getInstance()
        manager.flush()
        val cookieHeader = BiliWebLoginPolicy.mergeCookieHeaders(
            listOf(
                manager.getCookie("https://bilibili.com"),
                manager.getCookie("https://www.bilibili.com"),
                manager.getCookie("https://m.bilibili.com")
            )
        )

        if (cookieHeader.isNullOrBlank()) {
            status.text = "检测到 B站回跳，但尚未取得有效登录凭据，请确认登录已完成"
            return false
        }

        completed = true
        setResult(
            Activity.RESULT_OK,
            Intent().putExtra(EXTRA_COOKIE_HEADER, cookieHeader)
        )
        finish()
        return true
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.webChromeClient = null
            webView.webViewClient = WebViewClient()
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_COOKIE_HEADER = "com.example.bilidl.extra.BILI_COOKIE_HEADER"
    }
}
