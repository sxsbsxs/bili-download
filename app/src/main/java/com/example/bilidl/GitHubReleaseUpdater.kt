package com.example.bilidl

import android.content.Context
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks the repository's latest published GitHub Release and downloads its APK asset.
 * Draft releases, prereleases, debug APKs and unsigned APKs are deliberately ignored.
 */
internal object GitHubReleaseUpdater {
    private const val LATEST_RELEASE_API =
        "https://api.github.com/repos/sxsbsxs/bili-download/releases/latest"
    private const val USER_AGENT = "BiliDl-Android"

    data class ReleaseInfo(
        val tagName: String,
        val releaseName: String,
        val notes: String,
        val assetName: String,
        val downloadUrl: String,
        val assetSize: Long
    )

    sealed class CheckResult {
        data class UpdateAvailable(val release: ReleaseInfo, val currentVersion: String) : CheckResult()
        data class AlreadyLatest(val currentVersion: String) : CheckResult()
        object NoReleasePublished : CheckResult()
        data class NoApkAsset(val releaseTag: String) : CheckResult()
        data class Failed(val message: String) : CheckResult()
    }

    fun checkLatest(context: Context): CheckResult {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(LATEST_RELEASE_API).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 20_000
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                setRequestProperty("User-Agent", USER_AGENT)
            }

            when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_NOT_FOUND -> return CheckResult.NoReleasePublished
                in 200..299 -> Unit
                HttpURLConnection.HTTP_FORBIDDEN -> {
                    val detail = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    return CheckResult.Failed(
                        if (detail.contains("rate limit", ignoreCase = true)) {
                            "GitHub 请求次数暂时达到上限，请稍后再检查。"
                        } else {
                            "GitHub 暂时拒绝了更新请求（HTTP $code）。"
                        }
                    )
                }
                else -> return CheckResult.Failed("检查更新失败（HTTP $code）。")
            }

            val json = JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
            val tag = json.optString("tag_name").trim()
            if (tag.isBlank()) return CheckResult.Failed("GitHub Release 没有版本标签。")

            val assets = json.optJSONArray("assets")
            val apkCandidates = if (assets == null) {
                emptyList()
            } else {
                (0 until assets.length()).mapNotNull { index ->
                    val item = assets.optJSONObject(index) ?: return@mapNotNull null
                    val name = item.optString("name").trim()
                    val url = item.optString("browser_download_url").trim()
                    if (
                        name.endsWith(".apk", ignoreCase = true) &&
                        !name.contains("debug", ignoreCase = true) &&
                        !name.contains("unsigned", ignoreCase = true) &&
                        !name.contains("unaligned", ignoreCase = true) &&
                        url.startsWith("https://")
                    ) {
                        Triple(name, url, item.optLong("size", 0L))
                    } else {
                        null
                    }
                }
            }

            val asset = apkCandidates
                .sortedBy { if (it.first.contains("universal", ignoreCase = true)) 0 else 1 }
                .firstOrNull()
                ?: return CheckResult.NoApkAsset(tag)

            @Suppress("DEPRECATION")
            val currentVersion = context.packageManager
                .getPackageInfo(context.packageName, 0)
                .versionName
                .orEmpty()

            val release = ReleaseInfo(
                tagName = tag,
                releaseName = json.optString("name").ifBlank { tag },
                notes = json.optString("body").trim(),
                assetName = asset.first,
                downloadUrl = asset.second,
                assetSize = asset.third
            )

            val comparison = compareVersions(tag, currentVersion)
            val isNewer = comparison?.let { it > 0 }
                ?: tag.removePrefix("v").equals(currentVersion.removePrefix("v"), ignoreCase = true).not()

            if (isNewer) {
                CheckResult.UpdateAvailable(release, currentVersion)
            } else {
                CheckResult.AlreadyLatest(currentVersion)
            }
        } catch (error: Exception) {
            CheckResult.Failed(error.message?.takeIf { it.isNotBlank() } ?: "网络连接失败，请稍后重试。")
        } finally {
            connection?.disconnect()
        }
    }

    @Throws(IOException::class)
    fun download(
        context: Context,
        release: ReleaseInfo,
        onProgress: (Int) -> Unit
    ): File {
        val directory = File(context.cacheDir, "updates")
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("无法创建更新缓存目录。")
        }

        val safeName = release.assetName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val target = File(directory, safeName)
        val temporary = File(directory, "$safeName.part")
        if (temporary.exists()) temporary.delete()

        var connection: HttpURLConnection? = null
        try {
            connection = (URL(release.downloadUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 20_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/octet-stream")
                setRequestProperty("User-Agent", USER_AGENT)
            }

            val code = connection.responseCode
            if (code !in 200..299) {
                throw IOException("下载更新包失败（HTTP $code）。")
            }

            val totalBytes = release.assetSize.takeIf { it > 0L }
                ?: connection.contentLengthLong
            var downloaded = 0L
            var lastPercent = -2

            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(temporary).buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count

                        val percent = if (totalBytes > 0L) {
                            ((downloaded * 100L) / totalBytes).toInt().coerceIn(0, 100)
                        } else {
                            -1
                        }
                        if (percent != lastPercent) {
                            onProgress(percent)
                            lastPercent = percent
                        }
                    }
                    output.flush()
                }
            }

            if (downloaded <= 0L) throw IOException("下载到的安装包为空。")
            if (release.assetSize > 0L && downloaded != release.assetSize) {
                throw IOException("安装包大小校验失败，请重新下载。")
            }

            if (target.exists() && !target.delete()) {
                throw IOException("无法替换旧的更新缓存文件。")
            }
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
                temporary.delete()
            }

            onProgress(100)
            return target
        } catch (error: Exception) {
            temporary.delete()
            if (error is IOException) throw error
            throw IOException(error.message ?: "下载更新包失败。", error)
        } finally {
            connection?.disconnect()
        }
    }

    private fun compareVersions(releaseTag: String, currentVersion: String): Int? {
        fun parts(value: String): List<Int>? {
            val clean = value.trim().removePrefix("v").removePrefix("V")
                .substringBefore("-")
                .substringBefore("+")
            if (!clean.matches(Regex("\\d+(?:\\.\\d+)*"))) return null
            return clean.split(".").mapNotNull { it.toIntOrNull() }
                .takeIf { it.isNotEmpty() }
        }

        val latest = parts(releaseTag) ?: return null
        val installed = parts(currentVersion) ?: return null
        val length = maxOf(latest.size, installed.size)
        for (index in 0 until length) {
            val left = latest.getOrElse(index) { 0 }
            val right = installed.getOrElse(index) { 0 }
            if (left != right) return left.compareTo(right)
        }
        return 0
    }
}
