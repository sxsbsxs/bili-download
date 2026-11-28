package com.example.bilidl

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.bilidl.data.local.entity.DownloadResourceEntity
import com.example.bilidl.data.local.entity.DownloadTaskEntity
import com.example.bilidl.data.local.entity.DownloadTaskStatus
import com.example.bilidl.data.repository.BiliDownloadTaskRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class BiliDownloadWorker(
    context: Context,
    parameters: WorkerParameters
) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val taskId = inputData.getString(KEY_TASK_ID).orEmpty()
        val taskRepository = BiliDownloadTaskRepository.getInstance(applicationContext)
        return try {
            setForeground(foregroundInfo())
            val bvid = inputData.getString(KEY_BVID)
            val aid = inputData.getLong(KEY_AID, 0L)
            val cid = inputData.getLong(KEY_CID, 0L)
            val title = inputData.getString(KEY_TITLE).orEmpty()
            val quality = inputData.getInt(KEY_QUALITY, 80)
            val audioOnly = inputData.getBoolean(KEY_AUDIO_ONLY, false)
            val downloadDanmaku = inputData.getBoolean(KEY_DOWNLOAD_DANMAKU, false)
            val downloadSubtitles = inputData.getBoolean(KEY_DOWNLOAD_SUBTITLES, false)
            val durationSeconds = inputData.getInt(KEY_DURATION, 0)
            val page = inputData.getInt(KEY_PAGE, 1)
            val transferId = inputData.getString(KEY_TASK_ID).orEmpty()
            val concurrency = inputData.getInt(KEY_CONCURRENT, 2)
            if (taskId.isBlank()) {
                return Result.failure(workDataOf(KEY_ERROR to "任务编号缺失"))
            }

            taskRepository.registerTask(
                DownloadTaskEntity(
                    taskId = taskId,
                    bvid = bvid,
                    aid = aid,
                    cid = cid,
                    pageNumber = page,
                    title = title,
                    uploader = "",
                    durationSeconds = durationSeconds,
                    coverUrl = null,
                    qualityId = quality,
                    audioOnly = audioOnly,
                    downloadDanmaku = downloadDanmaku,
                    downloadSubtitles = downloadSubtitles
                )
            )
            if (cid <= 0L || (bvid.isNullOrBlank() && aid <= 0L)) {
                taskRepository.markFailed(taskId, "下载参数无效")
                return Result.failure(workDataOf(KEY_ERROR to "下载参数无效"))
            }

            val persistedStatus = taskRepository.getTask(taskId)?.status
            if (persistedStatus == DownloadTaskStatus.FAILED || persistedStatus == DownloadTaskStatus.PAUSED) {
                taskRepository.markQueued(taskId, "任务已重新加入队列", countAttempt = true)
            }
            taskRepository.markRunning(taskId)
            setProgress(workDataOf(KEY_PROGRESS to 0, KEY_DETAIL to "正在准备下载"))
            setForeground(foregroundInfo(0, "正在准备下载"))

            val video = BiliVideo(
                bvid = bvid,
                aid = aid,
                cid = cid,
                page = page,
                title = title,
                uploader = "",
                durationSeconds = durationSeconds,
                coverUrl = null,
                cover = null,
                qualities = emptyList()
            )
            var lastReportedPercent = -1
            var lastReportedDetail = ""
            var lastPersistedPercent = -3
            var lastPersistedStage = ""
            val progressLock = Any()
            val saved = BiliDownloadEngine.download(
                applicationContext,
                video,
                quality,
                audioOnly,
                transferId.ifBlank { id.toString() },
                concurrency,
                SecureCookieStore.loadCookieHeader(applicationContext)
                    ?: SecureCookieStore.load(applicationContext),
                downloadDanmaku = downloadDanmaku,
                downloadSubtitles = downloadSubtitles,
                isCancelled = { isStopped }
            ) { percent, detail ->
                synchronized(progressLock) {
                    // The range engine reports each buffer write; update WorkManager
                    // only when the visible percentage or stage actually changes.
                    if (percent != lastReportedPercent || detail != lastReportedDetail) {
                        lastReportedPercent = percent
                        lastReportedDetail = detail
                        val progressData = workDataOf(KEY_PROGRESS to percent, KEY_DETAIL to detail)
                        setProgressAsync(progressData)
                        setForegroundAsync(foregroundInfo(percent, detail))

                        // Persist only meaningful progress changes. The byte-level range
                        // callback can be very chatty; Room stores snapshots, not each buffer.
                        if (taskId.isNotBlank() &&
                            (percent - lastPersistedPercent >= 3 || detail != lastPersistedStage || percent >= 100)
                        ) {
                            runBlocking(Dispatchers.IO) {
                                taskRepository.reportProgress(taskId, percent, detail, detail)
                            }
                            lastPersistedPercent = percent
                            lastPersistedStage = detail
                        }
                    }
                }
            }
            if (taskId.isNotBlank()) {
                val sidecarEntities = saved.additionalResources.mapIndexed { index, resource ->
                    DownloadResourceEntity(
                        resourceId = "$taskId:sidecar:$index:${resource.resourceType}",
                        taskId = taskId,
                        resourceType = resource.resourceType,
                        displayName = resource.displayName,
                        mimeType = resource.mimeType,
                        contentUri = resource.uri.toString(),
                        sizeBytes = resource.sizeBytes
                    )
                }
                taskRepository.markCompleted(
                    taskId = taskId,
                    outputUri = saved.uri.toString(),
                    outputName = saved.displayName,
                    mimeType = saved.mimeType,
                    sizeBytes = saved.sizeBytes,
                    additionalResources = sidecarEntities
                )
            }
            Result.success(
                workDataOf(
                    KEY_URI to saved.uri.toString(),
                    KEY_FILE_NAME to saved.displayName,
                    KEY_SIZE_TEXT to buildString {
                        append(BiliDownloadEngine.formatSize(saved.sizeBytes))
                        if (saved.additionalFiles.isNotEmpty()) {
                            append(" · 附加文件：")
                            append(saved.additionalFiles.joinToString("、"))
                        }
                        if (saved.warnings.isNotEmpty()) {
                            append(" · ")
                            append(saved.warnings.joinToString("；"))
                        }
                    },
                    KEY_CONTENT_FILES to saved.additionalFiles.toTypedArray(),
                    KEY_CONTENT_WARNINGS to saved.warnings.toTypedArray(),
                    KEY_PROGRESS to 100
                )
            )
        } catch (error: kotlinx.coroutines.CancellationException) {
            if (taskId.isNotBlank()) {
                withContext(NonCancellable + Dispatchers.IO) {
                    taskRepository.markPaused(taskId, "任务已取消")
                }
            }
            throw error
        } catch (error: Exception) {
            if (BiliDownloadRetryPolicy.shouldRetry(error, runAttemptCount, MAX_WORK_RETRIES)) {
                if (taskId.isNotBlank()) {
                    taskRepository.markQueued(taskId, "网络暂时不可用，等待自动重试", countAttempt = true)
                }
                Result.retry()
            } else {
                if (taskId.isNotBlank()) {
                    taskRepository.markFailed(taskId, error.message ?: "下载失败")
                }
                Result.failure(workDataOf(KEY_ERROR to (error.message ?: "下载失败")))
            }
        }
    }

    private fun foregroundInfo(
        progress: Int? = null,
        detail: String = ""
    ): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "BiliDl 下载", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val isComplete = progress != null && progress >= 100
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(if (isComplete) "BiliDl 下载完成" else "BiliDl 正在下载")
            .setContentText(detail.ifBlank { inputData.getString(KEY_TITLE).orEmpty() })
            .setOngoing(!isComplete)
            .setOnlyAlertOnce(true)
            .setProgress(100, (progress ?: 0).coerceIn(0, 100), progress == null)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val KEY_TASK_ID = "task_id"
        const val KEY_BVID = "bvid"
        const val KEY_AID = "aid"
        const val KEY_CID = "cid"
        const val KEY_TITLE = "title"
        const val KEY_QUALITY = "quality"
        const val KEY_AUDIO_ONLY = "audio_only"
        const val KEY_DOWNLOAD_DANMAKU = "download_danmaku"
        const val KEY_DOWNLOAD_SUBTITLES = "download_subtitles"
        const val KEY_DURATION = "duration_seconds"
        const val KEY_PAGE = "page_number"
        const val KEY_CONTENT_FILES = "content_files"
        const val KEY_CONTENT_WARNINGS = "content_warnings"
        const val KEY_CONCURRENT = "concurrent"
        const val KEY_PROGRESS = "progress"
        const val KEY_DETAIL = "detail"
        const val KEY_ERROR = "error"
        const val KEY_URI = "uri"
        const val KEY_FILE_NAME = "file_name"
        const val KEY_SIZE_TEXT = "size_text"
        const val CHANNEL_ID = "bilidl_downloads"
        const val NOTIFICATION_ID = 4107
        private const val MAX_WORK_RETRIES = 3
    }
}
