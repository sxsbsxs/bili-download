package com.example.bilidl.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.bilidl.data.local.entity.DownloadTaskEventEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadTaskEventDao {
    @Insert
    suspend fun insert(event: DownloadTaskEventEntity)

    @Query(
        """
        SELECT * FROM download_task_events
        WHERE taskId = :taskId
        ORDER BY createdAt DESC
        LIMIT :limit
        """
    )
    fun observeRecent(taskId: String, limit: Int): Flow<List<DownloadTaskEventEntity>>

    @Query(
        """
        SELECT * FROM download_task_events
        WHERE taskId = :taskId
        ORDER BY createdAt DESC
        LIMIT :limit
        """
    )
    suspend fun getRecent(taskId: String, limit: Int): List<DownloadTaskEventEntity>

    @Query("DELETE FROM download_task_events WHERE taskId = :taskId")
    suspend fun deleteByTask(taskId: String)
}
