package com.example.bilidl

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.example.bilidl.common.content.BiliContentExporter
import com.example.bilidl.common.content.BiliDanmaku
import com.example.bilidl.common.content.BiliDanmakuProtoParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

data class SavedResource(
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long,
    val mimeType: String,
    val resourceType: Int
)

data class SavedDownload(
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long,
    val mimeType: String,
    val additionalFiles: List<String> = emptyList(),
    val additionalResources: List<SavedResource> = emptyList(),
    val warnings: List<String> = emptyList()
)

private data class SidecarSummary(
    val resources: List<SavedResource>,
    val warnings: List<String>
)

object BiliDownloadEngine {
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 Chrome/124.0.0.0 Mobile Safari/537.36"
    private const val DANMAKU_SEGMENT_SECONDS = 360
    private const val MAX_DANMAKU_SEGMENTS = 512
    private const val MAX_SUBTITLE_TRACKS = 12

    suspend fun download(
        context: Context,
        video: BiliVideo,
        qualityId: Int,
        audioOnly: Boolean,
        transferId: String,
        concurrency: Int,
        cookie: String?,
        downloadDanmaku: Boolean = false,
        downloadSubtitles: Boolean = false,
        isCancelled: () -> Boolean = { false },
        progress: (Int, String) -> Unit
    ): SavedDownload = withContext(Dispatchers.IO) {
        val repository = BiliRepository { cookie }
        val source = repository.getPlaySource(video, qualityId)
        val referer = videoReferer(video)
        val workDir = File(context.filesDir, "bilidl_transfers").apply { mkdirs() }
        val safeId = transferId.replace(Regex("[^A-Za-z0-9_-]"), "_").take(80)
        val prefix = "task_" + safeId
        var completed = false
        val finalTemp = File(workDir, prefix + ".mp4")
        val audioOutputTemp = File(workDir, prefix + "_audio.m4a")
        val videoTemp = File(workDir, prefix + "_video.m4s")
        val audioTemp = File(workDir, prefix + "_audio.m4s")
        val directTemp = File(workDir, prefix + "_direct")
        val directSegmentTemps = source.directSegments.mapIndexed { index, _ ->
            File(workDir, prefix + "_durl_${index.toString().padStart(3, '0')}.flv")
        }
        try {
            if (source.videoUrls.isNotEmpty()) {
                if (audioOnly) {
                    if (source.audioUrls.isEmpty()) throw IOException("这个视频没有独立音频流")
                    if (BiliMediaMuxer.isValidM4a(audioOutputTemp)) {
                        progress(84, "恢复已封装的 M4A")
                    } else {
                        progress(3, "正在下载音频流")
                        downloadStreamWithRefresh(
                            repository = repository,
                            video = video,
                            qualityId = qualityId,
                            initialCandidates = source.audioUrls,
                            refreshedCandidates = { fresh -> fresh.audioUrls },
                            target = audioTemp,
                            cookie = cookie,
                            concurrency = concurrency,
                            isCancelled = isCancelled,
                            referer = referer,
                            onRefreshing = { progress(3, "CDN 拒绝访问，正在刷新音频地址…") }
                        ) { value ->
                            progress(3 + value * 78 / 100, "音频流")
                        }
                        progress(84, "正在封装 M4A")
                        DownloadStoragePolicy.ensureAvailable(workDir, audioTemp.length())
                        BiliMediaMuxer.muxAudioToM4a(audioTemp, audioOutputTemp)
                    }
                    val saved = publishFile(
                        context,
                        audioOutputTemp,
                        video.title,
                        "m4a",
                        "audio/mp4"
                    )
                    // Keep the source track until the published copy succeeds so
                    // a failed MediaStore write does not discard the resumable input.
                    deleteTransferArtifact(audioTemp)
                    val sidecars = exportSidecars(
                        context = context,
                        workDir = workDir,
                        prefix = prefix,
                        repository = repository,
                        video = video,
                        downloadDanmaku = downloadDanmaku,
                        downloadSubtitles = downloadSubtitles,
                        isCancelled = isCancelled,
                        progress = progress
                    )
                    progress(100, completionDetail("音频已保存", sidecars))
                    completed = true
                    saved.copy(
                        additionalFiles = sidecars.resources.map { it.displayName },
                        additionalResources = sidecars.resources,
                        warnings = sidecars.warnings
                    )
                } else {
                    // Do not accidentally mux an audio fragment left by an earlier
                    // audio-capable attempt when the current playurl is video-only.
                    if (source.audioUrls.isEmpty()) deleteTransferArtifact(audioTemp)
                    if (BiliMediaMuxer.isValidMp4(finalTemp)) {
                        progress(95, "恢复已封装的 MP4")
                    } else {
                        progress(2, "正在下载视频流")
                        downloadStreamWithRefresh(
                            repository = repository,
                            video = video,
                            qualityId = qualityId,
                            initialCandidates = source.videoUrls,
                            refreshedCandidates = { fresh -> fresh.videoUrls },
                            target = videoTemp,
                            cookie = cookie,
                            concurrency = concurrency,
                            isCancelled = isCancelled,
                            referer = referer,
                            onRefreshing = { progress(2, "CDN 拒绝访问，正在刷新视频地址…") }
                        ) { value ->
                            progress(2 + (value * if (source.audioUrls.isEmpty()) 92 else 45) / 100, "视频流")
                        }
                        if (source.audioUrls.isNotEmpty()) {
                            progress(49, "正在下载音频流")
                            downloadStreamWithRefresh(
                                repository = repository,
                                video = video,
                                qualityId = qualityId,
                                initialCandidates = source.audioUrls,
                                refreshedCandidates = { fresh -> fresh.audioUrls },
                                target = audioTemp,
                                cookie = cookie,
                                concurrency = concurrency,
                                isCancelled = isCancelled,
                                referer = referer,
                                onRefreshing = { progress(49, "CDN 拒绝访问，正在刷新音频地址…") }
                            ) { value ->
                                progress(49 + value * 44 / 100, "音频流")
                            }
                        }
                        progress(95, "正在封装 MP4")
                        val sourceTrackBytes = videoTemp.length() +
                            audioTemp.takeIf { it.exists() }?.length().orZero()
                        DownloadStoragePolicy.ensureAvailable(workDir, sourceTrackBytes)
                        BiliMediaMuxer.muxToMp4(videoTemp, audioTemp.takeIf { it.exists() }, finalTemp)
                        // Only discard resumable source tracks after muxing succeeded.
                        // If MediaStore publication fails, the validated MP4 remains
                        // and is reused by the next worker attempt.
                        deleteTransferArtifact(videoTemp)
                        deleteTransferArtifact(audioTemp)
                    }
                    val saved = publishFile(context, finalTemp, video.title, "mp4", "video/mp4")
                    val sidecars = exportSidecars(
                        context = context,
                        workDir = workDir,
                        prefix = prefix,
                        repository = repository,
                        video = video,
                        downloadDanmaku = downloadDanmaku,
                        downloadSubtitles = downloadSubtitles,
                        isCancelled = isCancelled,
                        progress = progress
                    )
                    progress(100, completionDetail("下载完成", sidecars))
                    completed = true
                    saved.copy(
                    additionalFiles = sidecars.resources.map { it.displayName },
                    additionalResources = sidecars.resources,
                    warnings = sidecars.warnings
                )
                }
            } else {
                if (audioOnly) throw IOException("当前视频接口只返回完整视频流，暂不支持单独提取音频")
                val segments = source.directSegments
                if (segments.isEmpty()) throw IOException("B站没有返回可下载的直连分段")
                val isFlv = source.format.contains("flv", true)
                if (segments.size > 1 && !isFlv) {
                    throw IOException("B站返回了多段非 FLV 直连流；当前尚未确认其分段容器边界，已停止以避免损坏文件")
                }

                progress(2, if (segments.size > 1) "正在下载旧式视频分段" else "正在下载视频")
                if (segments.size == 1) {
                    val reusableDirect = directTemp.isFile && if (isFlv) {
                        BiliFlvSegmentMerger.isValid(directTemp)
                    } else {
                        BiliMediaMuxer.isValidMp4(directTemp)
                    }
                    if (reusableDirect) {
                        progress(96, "恢复已下载的视频文件")
                    } else {
                        downloadStreamWithRefresh(
                            repository = repository,
                            video = video,
                            qualityId = qualityId,
                            initialCandidates = segments.single().urls,
                            refreshedCandidates = { fresh ->
                                fresh.directSegments.firstOrNull()?.urls.orEmpty()
                            },
                            target = directTemp,
                            cookie = cookie,
                            concurrency = concurrency,
                            isCancelled = isCancelled,
                            referer = referer,
                            onRefreshing = { progress(1, "CDN 拒绝访问，正在刷新直连地址…") }
                        ) { value ->
                            progress(value.coerceIn(0, 99), "视频流")
                        }
                    }
                } else {
                    val alreadyMerged = directTemp.isFile && BiliFlvSegmentMerger.isValid(directTemp)
                    if (!alreadyMerged) {
                        directTemp.delete()
                        val weights = segments.map { segment ->
                            when {
                                segment.sizeBytes > 0L -> segment.sizeBytes.toDouble()
                                segment.lengthMs > 0L -> segment.lengthMs.toDouble()
                                else -> 1.0
                            }
                        }
                        val totalWeight = weights.sum().coerceAtLeast(1.0)
                        var completedWeight = 0.0
                        segments.forEachIndexed { index, segment ->
                            if (isCancelled()) throw CancellationException("下载已取消")
                            val target = directSegmentTemps[index]
                            downloadStreamWithRefresh(
                                repository = repository,
                                video = video,
                                qualityId = qualityId,
                                initialCandidates = segment.urls,
                                refreshedCandidates = { fresh ->
                                    fresh.directSegments.getOrNull(index)?.urls.orEmpty()
                                },
                                target = target,
                                cookie = cookie,
                                concurrency = concurrency,
                                isCancelled = isCancelled,
                                referer = referer,
                                onRefreshing = { progress(2, "CDN 拒绝访问，正在刷新第 ${index + 1} 段地址…") }
                            ) { value ->
                                val combined = (completedWeight + weights[index] * value.coerceIn(0, 100) / 100.0) /
                                    totalWeight
                                progress((2 + combined * 86).toInt().coerceIn(2, 89),
                                    "旧式视频分段 ${index + 1}/${segments.size}")
                            }
                            completedWeight += weights[index]
                        }

                        val mergedSourceBytes = directSegmentTemps.sumOf { it.length() }
                        DownloadStoragePolicy.ensureAvailable(workDir, mergedSourceBytes)
                        progress(90, "正在合并旧式 FLV 分段")
                        BiliFlvSegmentMerger.merge(
                            segments.mapIndexed { index, segment ->
                                BiliFlvSegmentInput(
                                    file = directSegmentTemps[index],
                                    declaredDurationMs = segment.lengthMs
                                )
                            },
                            directTemp
                        )
                        // The merged FLV is structurally verified and durable. Source
                        // fragments can now be released before the MediaStore copy.
                        directSegmentTemps.forEach(::deleteTransferArtifact)
                    }
                }

                val ext = if (isFlv) "flv" else "mp4"
                val mime = if (ext == "flv") "video/x-flv" else "video/mp4"
                val saved = publishFile(context, directTemp, video.title, ext, mime)
                val sidecars = exportSidecars(
                    context = context,
                    workDir = workDir,
                    prefix = prefix,
                    repository = repository,
                    video = video,
                    downloadDanmaku = downloadDanmaku,
                    downloadSubtitles = downloadSubtitles,
                    isCancelled = isCancelled,
                    progress = progress
                )
                progress(100, completionDetail("下载完成", sidecars))
                completed = true
                saved.copy(
                    additionalFiles = sidecars.resources.map { it.displayName },
                    additionalResources = sidecars.resources,
                    warnings = sidecars.warnings
                )
            }
        } finally {
            if (completed || !BiliMediaMuxer.isValidM4a(audioOutputTemp)) {
                audioOutputTemp.delete()
            }
            if (completed) {
                deleteTransferArtifact(videoTemp)
                deleteTransferArtifact(audioTemp)
                deleteTransferArtifact(directTemp)
                directSegmentTemps.forEach(::deleteTransferArtifact)
                finalTemp.delete()
            }
        }
    }

    private fun exportSidecars(
        context: Context,
        workDir: File,
        prefix: String,
        repository: BiliRepository,
        video: BiliVideo,
        downloadDanmaku: Boolean,
        downloadSubtitles: Boolean,
        isCancelled: () -> Boolean,
        progress: (Int, String) -> Unit
    ): SidecarSummary {
        if (!downloadDanmaku && !downloadSubtitles) return SidecarSummary(emptyList(), emptyList())

        val files = ArrayList<SavedResource>()
        val warnings = ArrayList<String>()

        if (downloadDanmaku) {
            try {
                progress(96, "正在获取弹幕")
                val requestedSegments = (
                    (video.durationSeconds.coerceAtLeast(1) + DANMAKU_SEGMENT_SECONDS - 1) /
                        DANMAKU_SEGMENT_SECONDS
                    )
                val segmentCount = requestedSegments.coerceIn(1, MAX_DANMAKU_SEGMENTS)
                val comments = ArrayList<BiliDanmaku>()
                var failedSegments = 0
                for (segment in 1..segmentCount) {
                    if (isCancelled()) throw CancellationException("下载已取消")
                    try {
                        val bytes = repository.getDanmakuSegment(video.cid, segment)
                        comments += BiliDanmakuProtoParser.parse(bytes)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        failedSegments++
                    }
                }

                if (comments.isEmpty()) {
                    warnings += if (failedSegments == segmentCount) {
                        "弹幕获取失败：B站未返回可用分段"
                    } else {
                        "该视频没有可导出的弹幕"
                    }
                } else {
                    val temp = File(workDir, "${prefix}_danmaku.xml")
                    try {
                        temp.writeText(
                            BiliContentExporter.danmakuToXml(video.cid, comments),
                            Charsets.UTF_8
                        )
                        val published = publishFile(
                            context,
                            temp,
                            video.title + "_弹幕",
                            "xml",
                            "application/xml"
                        )
                        files += SavedResource(
                            uri = published.uri,
                            displayName = published.displayName,
                            sizeBytes = published.sizeBytes,
                            mimeType = published.mimeType,
                            resourceType = com.example.bilidl.data.local.entity.DownloadResourceType.DANMAKU_XML
                        )
                    } finally {
                        temp.delete()
                    }
                    if (failedSegments > 0) {
                        warnings += "弹幕部分导出：${failedSegments}/$segmentCount 个分段获取失败"
                    }
                }
                if (requestedSegments > MAX_DANMAKU_SEGMENTS) {
                    warnings += "视频时长过长，本次最多获取 $MAX_DANMAKU_SEGMENTS 个弹幕分段"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                warnings += "弹幕导出失败：" + (error.message ?: "未知错误")
            }
        }

        if (downloadSubtitles) {
            try {
                progress(98, "正在获取字幕")
                if (isCancelled()) throw CancellationException("下载已取消")
                val allTracks = repository.getSubtitleTracks(video)
                val tracks = allTracks.take(MAX_SUBTITLE_TRACKS)
                if (tracks.isEmpty()) {
                    warnings += "该视频没有可下载的字幕轨道"
                } else {
                    var exportedTracks = 0
                    tracks.forEachIndexed { index, track ->
                        if (isCancelled()) throw CancellationException("下载已取消")
                        try {
                            val cues = repository.getSubtitleCues(track)
                            if (cues.isNotEmpty()) {
                                val temp = File(workDir, "${prefix}_subtitle_$index.srt")
                                try {
                                    temp.writeText(BiliContentExporter.subtitlesToSrt(cues), Charsets.UTF_8)
                                    val baseName = video.title + "_" + track.languageName
                                    val published = publishFile(
                                        context,
                                        temp,
                                        baseName,
                                        "srt",
                                        "application/x-subrip"
                                    )
                                    files += SavedResource(
                                        uri = published.uri,
                                        displayName = published.displayName,
                                        sizeBytes = published.sizeBytes,
                                        mimeType = published.mimeType,
                                        resourceType = com.example.bilidl.data.local.entity.DownloadResourceType.SUBTITLE_SRT
                                    )
                                    exportedTracks++
                                } finally {
                                    temp.delete()
                                }
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            warnings += "字幕 ${track.languageName} 导出失败：" +
                                (error.message ?: "未知错误")
                        }
                    }
                    if (exportedTracks == 0 && warnings.none { it.startsWith("字幕") }) {
                        warnings += "字幕轨道没有可导出的文字"
                    }
                }
                if (allTracks.size > MAX_SUBTITLE_TRACKS) {
                    warnings += "字幕轨道较多，本次最多导出 $MAX_SUBTITLE_TRACKS 条"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                warnings += "字幕导出失败：" + (error.message ?: "未知错误")
            }
        }

        return SidecarSummary(files.distinctBy { it.uri }, warnings.distinct())
    }

    private fun completionDetail(label: String, sidecars: SidecarSummary): String {
        return when {
            sidecars.resources.isNotEmpty() && sidecars.warnings.isNotEmpty() ->
                "$label · 已保存 ${sidecars.resources.size} 个附加文件，部分资源未能获取"
            sidecars.resources.isNotEmpty() ->
                "$label · 已保存 ${sidecars.resources.size} 个附加文件"
            sidecars.warnings.isNotEmpty() ->
                "$label · 附加资源未能获取"
            else -> label
        }
    }

    private suspend fun downloadStreamWithRefresh(
        repository: BiliRepository,
        video: BiliVideo,
        qualityId: Int,
        initialCandidates: List<String>,
        refreshedCandidates: (BiliPlaySource) -> List<String>,
        target: File,
        cookie: String?,
        concurrency: Int,
        isCancelled: () -> Boolean,
        referer: String,
        onRefreshing: () -> Unit = {},
        onProgress: (Int) -> Unit
    ) {
        try {
            downloadStream(
                initialCandidates, target, cookie, concurrency, isCancelled, referer, onProgress
            )
        } catch (error: Exception) {
            if (BiliDownloadRetryPolicy.httpStatus(error) != 403) throw error

            // Bilibili CDN URLs are short-lived. If all primary/backup URLs deny
            // access, resolve a fresh playurl once instead of failing immediately.
            onRefreshing()
            val refreshed = try {
                repository.getPlaySource(video, qualityId)
            } catch (refreshError: Exception) {
                throw IOException(
                    "CDN 返回 HTTP 403，刷新视频地址失败：" +
                        (refreshError.message ?: "B站暂时没有返回可用地址"),
                    refreshError
                )
            }
            val candidates = refreshedCandidates(refreshed).distinct()
            if (candidates.isEmpty()) {
                throw IOException("CDN 返回 HTTP 403，刷新后没有同类型可用地址", error)
            }
            downloadStream(
                candidates, target, cookie, concurrency, isCancelled, referer, onProgress
            )
        }
    }

    private suspend fun downloadStream(
        candidates: List<String>,
        target: File,
        cookie: String?,
        concurrency: Int,
        isCancelled: () -> Boolean,
        referer: String,
        onProgress: (Int) -> Unit
    ) {
        SegmentedHttpDownloader.download(
            urls = candidates,
            target = target,
            cookie = cookie,
            concurrency = concurrency,
            isCancelled = isCancelled,
            onProgress = onProgress,
            referer = referer
        )
    }

    private fun videoReferer(video: BiliVideo): String {
        val identity = video.bvid?.takeIf { it.isNotBlank() }
            ?: video.aid.takeIf { it > 0L }?.let { "av$it" }
            ?: return "https://www.bilibili.com/"
        return "https://www.bilibili.com/video/$identity?p=${video.page.coerceAtLeast(1)}"
    }

    private fun publishFile(
        context: Context,
        source: File,
        title: String,
        extension: String,
        mimeType: String
    ): SavedDownload {
        DownloadStoragePolicy.ensureAvailable(
            Environment.getExternalStorageDirectory(),
            source.length()
        )
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

    private fun deleteTransferArtifact(file: File) {
        file.delete()
        File(file.absolutePath + ".ranges").delete()
        File(file.absolutePath + ".meta").delete()
    }

    private fun Long?.orZero(): Long = this ?: 0L

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
