package com.example.bilidl

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

data class SavedDownload(
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long,
    val mimeType: String
)

object BiliDownloadEngine {
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 Chrome/124.0.0.0 Mobile Safari/537.36"

    suspend fun download(
        context: Context,
        video: BiliVideo,
        qualityId: Int,
        audioOnly: Boolean,
        cookie: String?,
        isCancelled: () -> Boolean = { false },
        progress: (Int, String) -> Unit
    ): SavedDownload = withContext(Dispatchers.IO) {
        val repository = BiliRepository { cookie }
        val source = repository.getPlaySource(video, qualityId)
        val workDir = File(context.cacheDir, "bilidl").apply { mkdirs() }
        val prefix = "task_" + java.util.UUID.randomUUID().toString()
        val finalTemp = File(workDir, prefix + ".mp4")
        val videoTemp = File(workDir, prefix + "_video.m4s")
        val audioTemp = File(workDir, prefix + "_audio.m4s")
        val directTemp = File(workDir, prefix + "_direct")
        try {
            if (source.videoUrls.isNotEmpty()) {
                if (audioOnly) {
                    if (source.audioUrls.isEmpty()) throw IOException("这个视频没有独立音频流")
                    progress(3, "正在下载音频")
                    downloadStream(source.audioUrls, audioTemp, isCancelled) { value ->
                        progress(3 + value * 94 / 100, "音频流")
                    }
                    val ext = extensionForMime(source.audioMime, "m4a")
                    val saved = publishFile(context, audioTemp, video.title, ext, source.audioMime.ifBlank { "audio/mp4" })
                    progress(100, "已保存")
                    saved
                } else {
                    progress(2, "正在下载视频流")
                    downloadStream(source.videoUrls, videoTemp, isCancelled) { value ->
                        progress(2 + (value * if (source.audioUrls.isEmpty()) 92 else 45) / 100, "视频流")
                    }
                    if (source.audioUrls.isNotEmpty()) {
                        progress(49, "正在下载音频流")
                        downloadStream(source.audioUrls, audioTemp, isCancelled) { value ->
                            progress(49 + value * 44 / 100, "音频流")
                        }
                    }
                    progress(95, "正在封装 MP4")
                    muxToMp4(videoTemp, audioTemp.takeIf { it.exists() }, finalTemp)
                    val saved = publishFile(context, finalTemp, video.title, "mp4", "video/mp4")
                    progress(100, "下载完成")
                    saved
                }
            } else {
                if (audioOnly) throw IOException("当前视频接口只返回完整视频流，暂不支持单独提取音频")
                if (source.directUrls.size != 1) {
                    throw IOException("B站仅返回多段旧式 FLV 流；为避免生成损坏文件，本版暂不拼接此格式")
                }
                progress(2, "正在下载视频")
                downloadStream(source.directUrls, directTemp, isCancelled) { value ->
                    progress(value, "视频流")
                }
                val ext = if (source.format.contains("flv", true)) "flv" else "mp4"
                val mime = if (ext == "flv") "video/x-flv" else "video/mp4"
                val saved = publishFile(context, directTemp, video.title, ext, mime)
                progress(100, "下载完成")
                saved
            }
        } finally {
            videoTemp.delete()
            audioTemp.delete()
            directTemp.delete()
            finalTemp.delete()
        }
    }

    private fun downloadStream(
        candidates: List<String>,
        target: File,
        isCancelled: () -> Boolean,
        onProgress: (Int) -> Unit
    ) {
        var lastError: Exception? = null
        for (candidate in candidates) {
            try {
                downloadOne(candidate, target, isCancelled, onProgress)
                return
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                target.delete()
                throw cancelled
            } catch (error: Exception) {
                target.delete()
                lastError = error
            }
        }
        throw IOException("音视频流下载失败：" + (lastError?.message ?: "没有可用地址"))
    }

    private fun downloadOne(
        url: String,
        target: File,
        isCancelled: () -> Boolean,
        onProgress: (Int) -> Unit
    ) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 45_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Referer", "https://www.bilibili.com/")
            setRequestProperty("Origin", "https://www.bilibili.com")
            setRequestProperty("Accept", "*/*")
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("Connection", "keep-alive")
        }
        try {
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK && status != HttpURLConnection.HTTP_PARTIAL) {
                throw IOException("流服务器返回 HTTP " + status)
            }
            val total = connection.contentLengthLong
            var copied = 0L
            var lastPercent = -1
            connection.inputStream.use { input ->
                target.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (isCancelled()) throw kotlinx.coroutines.CancellationException("下载已取消")
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        copied += count
                        if (total > 0L) {
                            val percent = ((copied * 100L) / total).toInt().coerceIn(0, 100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
            }
            if (copied == 0L) throw IOException("下载到的文件为空")
            if (total > 0L && copied < total) throw IOException("下载未完成，连接提前中断")
            onProgress(100)
        } finally {
            connection.disconnect()
        }
    }

    private fun muxToMp4(videoFile: File, audioFile: File?, output: File) {
        val videoExtractor = MediaExtractor()
        val audioExtractor = if (audioFile != null) MediaExtractor() else null
        var muxer: MediaMuxer? = null
        try {
            videoExtractor.setDataSource(videoFile.absolutePath)
            val videoTrack = findTrack(videoExtractor, "video/")
                ?: throw IOException("视频流格式无法由当前 Android 设备读取")
            var audioTrack = -1
            if (audioExtractor != null && audioFile != null) {
                audioExtractor.setDataSource(audioFile.absolutePath)
                audioTrack = findTrack(audioExtractor, "audio/")
                    ?: throw IOException("音频流格式无法由当前 Android 设备读取")
            }

            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outVideo = muxer.addTrack(videoExtractor.getTrackFormat(videoTrack))
            val outAudio = if (audioTrack >= 0 && audioExtractor != null) {
                muxer.addTrack(audioExtractor.getTrackFormat(audioTrack))
            } else {
                -1
            }
            muxer.start()
            copyTrack(videoExtractor, videoTrack, muxer, outVideo)
            if (audioTrack >= 0 && audioExtractor != null && outAudio >= 0) {
                copyTrack(audioExtractor, audioTrack, muxer, outAudio)
            }
            muxer.stop()
        } catch (error: Exception) {
            output.delete()
            if (error is IOException) throw error
            throw IOException("音视频合并失败：" + (error.message ?: "当前设备可能不支持该编码"))
        } finally {
            try { muxer?.release() } catch (_: Exception) {}
            try { videoExtractor.release() } catch (_: Exception) {}
            try { audioExtractor?.release() } catch (_: Exception) {}
        }
    }

    private fun findTrack(extractor: MediaExtractor, prefix: String): Int? {
        for (index in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith(prefix)) {
                extractor.selectTrack(index)
                return index
            }
        }
        return null
    }

    private fun copyTrack(
        extractor: MediaExtractor,
        sourceTrack: Int,
        muxer: MediaMuxer,
        targetTrack: Int
    ) {
        val buffer = java.nio.ByteBuffer.allocate(4 * 1024 * 1024)
        val info = MediaCodec.BufferInfo()
        while (true) {
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            info.offset = 0
            info.size = size
            info.presentationTimeUs = extractor.sampleTime
            info.flags = extractor.sampleFlags
            muxer.writeSampleData(targetTrack, buffer, info)
            extractor.advance()
        }
    }

    private fun publishFile(
        context: Context,
        source: File,
        title: String,
        extension: String,
        mimeType: String
    ): SavedDownload {
        val safeTitle = title
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .take(80)
            .ifBlank { "BiliDl video" }
        val displayName = safeTitle + "." + extension
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/BiliDl"
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val uri = resolver.insert(collection, values) ?: throw IOException("无法创建下载文件")
        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                source.inputStream().buffered().use { input -> input.copyTo(output) }
            } ?: throw IOException("无法写入下载文件")
            val complete = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            resolver.update(uri, complete, null, null)
            return SavedDownload(uri, displayName, source.length(), mimeType)
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    private fun extensionForMime(mime: String, fallback: String): String {
        return when {
            mime.contains("mp4", true) || mime.contains("m4a", true) -> "m4a"
            mime.contains("mpeg", true) -> "mp3"
            mime.contains("webm", true) -> "webm"
            else -> fallback
        }
    }

    fun formatSize(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return String.format(Locale.US, "%.1f MB", mb)
    }
}
