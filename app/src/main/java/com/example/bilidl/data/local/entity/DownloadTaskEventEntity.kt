package com.example.bilidl.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Small, append-only audit trail for lifecycle transitions and user-visible failures.
 * Download byte progress stays on DownloadTaskEntity to avoid one row per buffer write.
 */
@Entity(
    tableName = "download_task_events",
    foreignKeys = [
        ForeignKey(
            entity = DownloadTaskEntity::class,
            parentColumns = ["taskId"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["taskId", "createdAt"])]
)
data class DownloadTaskEventEntity(
    @PrimaryKey(autoGenerate = true)
    val eventId: Long = 0L,
    val taskId: String,
    val fromStatus: Int?,
    val toStatus: Int,
    val stage: String,
    val message: String,
    val createdAt: Long = System.currentTimeMillis()
)
