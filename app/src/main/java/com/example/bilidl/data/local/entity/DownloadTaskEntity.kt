package com.example.bilidl.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Durable task specification and lifecycle snapshot.
 *
 * WorkManager owns execution scheduling; this table owns the user's task history
 * and the information needed to reconstruct a task after the Activity is gone.
 */
@Entity(
    tableName = "download_tasks",
    indices = [
        Index(value = ["status", "updatedAt"]),
        Index(value = ["bvid", "cid"]),
        Index(value = ["createdAt"])
    ]
)
data class DownloadTaskEntity(
    @PrimaryKey
    val taskId: String,
    val bvid: String?,
    val aid: Long,
    val cid: Long,
    val pageNumber: Int,
    val title: String,
    val uploader: String,
    val durationSeconds: Int,
    val coverUrl: String?,
    val qualityId: Int,
    val audioOnly: Boolean,
    val downloadDanmaku: Boolean,
    val downloadSubtitles: Boolean,
    @ColumnInfo(defaultValue = "0")
    val downloadCover: Boolean = false,
    val status: Int = DownloadTaskStatus.QUEUED,
    val progress: Int = 0,
    val stage: String = "等待下载",
    val detail: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val completedAt: Long? = null,
    val outputUri: String? = null,
    val outputName: String? = null,
    val outputMimeType: String? = null,
    val outputSizeBytes: Long? = null,
    val errorMessage: String? = null,
    val attemptCount: Int = 0
)

object DownloadTaskStatus {
    const val QUEUED = 0
    const val RUNNING = 1
    const val PAUSED = 2
    const val FAILED = 3
    const val COMPLETED = 4

    fun isTerminal(status: Int): Boolean =
        status == FAILED || status == COMPLETED

    fun canTransition(from: Int, to: Int): Boolean {
        if (from == to) return true
        return when (from) {
            QUEUED -> to == RUNNING || to == PAUSED || to == FAILED
            RUNNING -> to == QUEUED || to == PAUSED || to == FAILED || to == COMPLETED
            PAUSED -> to == QUEUED
            FAILED -> to == QUEUED
            COMPLETED -> false
            else -> false
        }
    }
}
