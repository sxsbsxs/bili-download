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
        transferId: String,
        concurrency: Int,
        cookie: String?,
        isCancelled: () -> Boolean = { false },
        progress: (Int, String) -> Unit
    ): SavedDownload = withContext(Dispatchers.IO) {
        val repository = BiliRepository { cookie }
        val source = repository.getPlaySource(video, qualityId)
        val workDir = File(context.filesDir, "bilidl_transfers").apply { mkdirs() }
        val safeId = transferId.replace(Regex("[^A-Za-z0-9_-]"), "_").take(80)
        val prefix = "task_" + safeId
        var completed = false
        val finalTemp = File(workDir, prefix + ".mp4")
        val audioOutputTemp = File(workDir, prefix + "_audio.m4a")
        val videoTemp = File(workDir, prefix + "_video.m4s")
        val audioTemp = File(workDir, prefix + "_audio.m4s")
        val directTemp = File(workDir, prefix + "_direct")
        try {
            if (source.videoUrls.isNotEmpty()) {
                if (audioOnly) {
                    if (source.audioUrls.isEmpty()) throw IOException("这个视频没有独立音频流")
                    progress(3, "正在下载音频流")
                    downloadStream(source.audioUrls, audioTemp, cookie, concurrency, isCancelled) { value ->
                        progress(3 + value * 78 / 100, "音频流")
                    }
                    progress(84, "正在封装 M4A")
                    muxAudioToM4a(audioTemp, audioOutputTemp)
                    val saved = publishFile(
                        context,
                        audioOutputTemp,
                        video.title,
                        "m4a",
                        "audio/mp4"
                    )
                    progress(100, "音频已保存")
                    completed = true
                    saved
                } else {
                    progress(2, "正在下载视频流")
                    downloadStream(source.videoUrls, videoTemp, cookie, concurrency, isCancelled) { value ->
                        progress(2 + (value * if (source.audioUrls.isEmpty()) 92 else 45) / 100, "视频流")
                    }
                    if (source.audioUrls.isNotEmpty()) {
                        progress(49, "正在下载音频流")
                        downloadStream(source.audioUrls, audioTemp, cookie, concurrency, isCancelled) { value ->
                            progress(49 + value * 44 / 100, "音频流")
                        }
                    }
                    progress(95, "正在封装 MP4")
                    muxToMp4(videoTemp, audioTemp.takeIf { it.exists() }, finalTemp)
                    val saved = publishFile(context, finalTemp, video.title, "mp4", "video/mp4")
                    progress(100, "下载完成")
                    completed = true
                    saved
                }
            } else {
                if (audioOnly) throw IOException("当前视频接口只返回完整视频流，暂不支持单独提取音频")
                if (source.directUrls.size != 1) {
                    throw IOException("B站仅返回多段旧式 FLV 流；为避免生成损坏文件，本版暂不拼接此格式")
                }
                progress(2, "正在下载视频")
                downloadStream(source.directUrls, directTemp, cookie, concurrency, isCancelled) { value ->
                    progress(value, "视频流")
                }
                val ext = if (source.format.contains("flv", true)) "flv" else "mp4"
                val mime = if (ext == "flv") "video/x-flv" else "video/mp4"
                val saved = publishFile(context, directTemp, video.title, ext, mime)
                progress(100, "下载完成")
                completed = true
                saved
            }
        } finally {
            // The muxed audio file is reproducible from the retained downloaded stream.
            audioOutputTemp.delete()
            // Keep partial range files after a failure so retries can reuse finished chunks.
            if (completed) {
                videoTemp.delete()
                audioTemp.delete()
                directTemp.delete()
                finalTemp.delete()
                listOf(videoTemp, audioTemp, directTemp).forEach { file ->
                    File(file.absolutePath + ".ranges").delete()
                    File(file.absolutePath + ".meta").delete()
                }
            }
        }
    }

    private suspend fun downloadStream(
        candidates: List<String>,
        target: File,
        cookie: String?,
        concurrency: Int,
        isCancelled: () -> Boolean,
        onProgress: (Int) -> Unit
    ) {
        SegmentedHttpDownloader.download(
            candidates,
            target,
            cookie,
            concurrency,
            isCancelled,
            onProgress
        )
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
            val videoBuffer = sampleBuffer(videoExtractor.getTrackFormat(videoTrack))
            val audioBuffer = if (audioTrack >= 0 && audioExtractor != null) {
                sampleBuffer(audioExtractor.getTrackFormat(audioTrack))
            } else {
                null
            }
            writeInterleavedSamples(
                videoExtractor = videoExtractor,
                videoBuffer = videoBuffer,
                videoOutputTrack = outVideo,
                audioExtractor = audioExtractor?.takeIf { audioTrack >= 0 },
                audioBuffer = audioBuffer,
                audioOutputTrack = outAudio,
                muxer = muxer
            )
            muxer.stop()
            if (!NativeTransfer.validateMp4(output.absolutePath)) {
                throw IOException("MP4 容器校验失败：缺少完整的 ftyp/moov/mdat 结构")
            }
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

    /**
     * Package the AAC DASH track into a standalone M4A container. The downloaded
     * .m4s is only a transport fragment; it must not be published by renaming it.
     */
    private fun muxAudioToM4a(audioFile: File, output: File) {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(audioFile.absolutePath)
            val audioTrack = findTrack(extractor, "audio/")
                ?: throw IOException("下载的音频流中没有可读取的音轨")
            val format = extractor.getTrackFormat(audioTrack)
            val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime != "audio/mp4a-latm") {
                throw IOException("当前 B站返回的音频编码为 $mime，暂不支持无损封装为 M4A")
            }

            output.parentFile?.mkdirs()
            output.delete()
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outputTrack = muxer.addTrack(format)
            muxer.start()

            val buffer = sampleBuffer(format)
            var sample = readNextSample(extractor, buffer)
            while (sample != null) {
                muxer.writeSampleData(outputTrack, buffer, sample)
                extractor.advance()
                sample = readNextSample(extractor, buffer)
            }
            muxer.stop()

            if (!NativeTransfer.validateMp4(output.absolutePath)) {
                throw IOException("M4A 容器校验失败")
            }
            if (output.length() <= 0L) throw IOException("生成的 M4A 文件为空")
        } catch (error: Exception) {
            output.delete()
            if (error is IOException) throw error
            throw IOException("M4A 封装失败：" + (error.message ?: "设备不支持此音频格式"))
        } finally {
            try { muxer?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
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

    /**
     * Keep a sample buffer large enough for the selected codec's declared input size.
     * Some high-bitrate keyframes are larger than a small fixed 4 MiB buffer.
     */
    private fun sampleBuffer(format: MediaFormat): java.nio.ByteBuffer {
        val declaredSize = runCatching {
            format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
        }.getOrDefault(0)
        val capacity = maxOf(4 * 1024 * 1024, declaredSize)
            .coerceAtMost(64 * 1024 * 1024)
        return java.nio.ByteBuffer.allocate(capacity)
    }

    private fun readNextSample(
        extractor: MediaExtractor,
        buffer: java.nio.ByteBuffer
    ): MediaCodec.BufferInfo? {
        val presentationTimeUs = extractor.sampleTime
        if (presentationTimeUs < 0L) return null

        buffer.clear()
        val size = extractor.readSampleData(buffer, 0)
        if (size < 0) return null

        return MediaCodec.BufferInfo().apply {
            set(0, size, presentationTimeUs, extractor.sampleFlags)
            buffer.position(0)
            buffer.limit(size)
        }
    }

    /**
     * The video and audio DASH streams have independent extractors. Read one
     * pending sample from each and write whichever has the earlier timestamp.
     * This produces a properly time-ordered MP4 without re-encoding either track.
     */
    private fun writeInterleavedSamples(
        videoExtractor: MediaExtractor,
        videoBuffer: java.nio.ByteBuffer,
        videoOutputTrack: Int,
        audioExtractor: MediaExtractor?,
        audioBuffer: java.nio.ByteBuffer?,
        audioOutputTrack: Int,
        muxer: MediaMuxer
    ) {
        var videoSample = readNextSample(videoExtractor, videoBuffer)
        var audioSample = if (audioExtractor != null && audioBuffer != null) {
            readNextSample(audioExtractor, audioBuffer)
        } else {
            null
        }

        while (videoSample != null || audioSample != null) {
            val writeVideo = audioSample == null ||
                (videoSample != null &&
                    videoSample.presentationTimeUs <= audioSample.presentationTimeUs)

            if (writeVideo) {
                val sample = videoSample ?: break
                muxer.writeSampleData(videoOutputTrack, videoBuffer, sample)
                videoExtractor.advance()
                videoSample = readNextSample(videoExtractor, videoBuffer)
            } else {
                val extractor = audioExtractor ?: break
                val buffer = audioBuffer ?: break
                val sample = audioSample ?: break
                muxer.writeSampleData(audioOutputTrack, buffer, sample)
                extractor.advance()
                audioSample = readNextSample(extractor, buffer)
            }
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
