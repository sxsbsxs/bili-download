package com.example.bilidl.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "download_resources",
    foreignKeys = [
        ForeignKey(
            entity = DownloadTaskEntity::class,
            parentColumns = ["taskId"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["taskId"]), Index(value = ["resourceType", "createdAt"])]
)
data class DownloadResourceEntity(
    @PrimaryKey
    val resourceId: String,
    val taskId: String,
    val resourceType: Int,
    val displayName: String,
    val mimeType: String,
    val contentUri: String,
    val sizeBytes: Long,
    val createdAt: Long = System.currentTimeMillis()
)

object DownloadResourceType {
    const val MEDIA = 0
    const val DANMAKU_XML = 1
    const val SUBTITLE_SRT = 2
}
