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

class BiliDownloadWorker(
    context: Context,
    parameters: WorkerParameters
) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        return try {
            setForeground(foregroundInfo())
            val bvid = inputData.getString(KEY_BVID)
            val aid = inputData.getLong(KEY_AID, 0L)
            val cid = inputData.getLong(KEY_CID, 0L)
            val title = inputData.getString(KEY_TITLE).orEmpty()
            val quality = inputData.getInt(KEY_QUALITY, 80)
            val audioOnly = inputData.getBoolean(KEY_AUDIO_ONLY, false)
            if (cid <= 0L || (bvid.isNullOrBlank() && aid <= 0L)) {
                return Result.failure(workDataOf(KEY_ERROR to "下载参数无效"))
            }

            val video = BiliVideo(
                bvid = bvid,
                aid = aid,
                cid = cid,
                page = 1,
                title = title,
                uploader = "",
                durationSeconds = 0,
                coverUrl = null,
                cover = null,
                qualities = emptyList()
            )
            val saved = BiliDownloadEngine.download(
                applicationContext,
                video,
                quality,
                audioOnly,
                SecureCookieStore.load(applicationContext)
            ) { percent, detail ->
                setProgressAsync(workDataOf(KEY_PROGRESS to percent, KEY_DETAIL to detail))
            }
            Result.success(
                workDataOf(
                    KEY_URI to saved.uri.toString(),
                    KEY_FILE_NAME to saved.displayName,
                    KEY_SIZE_TEXT to BiliDownloadEngine.formatSize(saved.sizeBytes),
                    KEY_PROGRESS to 100
                )
            )
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(workDataOf(KEY_ERROR to (error.message ?: "下载失败")))
        }
    }

    private fun foregroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "BiliDl 下载", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("BiliDl 正在下载")
            .setContentText(inputData.getString(KEY_TITLE).orEmpty())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, 0, true)
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
        const val KEY_BVID = "bvid"
        const val KEY_AID = "aid"
        const val KEY_CID = "cid"
        const val KEY_TITLE = "title"
        const val KEY_QUALITY = "quality"
        const val KEY_AUDIO_ONLY = "audio_only"
        const val KEY_PROGRESS = "progress"
        const val KEY_DETAIL = "detail"
        const val KEY_ERROR = "error"
        const val KEY_URI = "uri"
        const val KEY_FILE_NAME = "file_name"
        const val KEY_SIZE_TEXT = "size_text"
        const val CHANNEL_ID = "bilidl_downloads"
        const val NOTIFICATION_ID = 4107
    }
}
