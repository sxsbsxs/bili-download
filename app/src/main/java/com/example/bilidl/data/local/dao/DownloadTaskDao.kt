package com.example.bilidl.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.example.bilidl.data.local.entity.DownloadTaskEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadTaskDao {
    @Upsert
    suspend fun upsert(task: DownloadTaskEntity)

    @Update
    suspend fun update(task: DownloadTaskEntity)

    @Query("SELECT * FROM download_tasks WHERE taskId = :taskId LIMIT 1")
    suspend fun getById(taskId: String): DownloadTaskEntity?

    @Query("SELECT * FROM download_tasks ORDER BY updatedAt DESC, createdAt DESC")
    suspend fun getAll(): List<DownloadTaskEntity>

    @Query("SELECT * FROM download_tasks ORDER BY updatedAt DESC, createdAt DESC")
    fun observeAll(): Flow<List<DownloadTaskEntity>>

    @Query(
        """
        SELECT * FROM download_tasks
        WHERE status IN (:statuses)
        ORDER BY updatedAt DESC, createdAt DESC
        """
    )
    fun observeByStatuses(statuses: List<Int>): Flow<List<DownloadTaskEntity>>

    @Query(
        """
        SELECT * FROM download_tasks
        WHERE title LIKE '%' || :query || '%'
           OR uploader LIKE '%' || :query || '%'
           OR bvid LIKE '%' || :query || '%'
        ORDER BY updatedAt DESC
        """
    )
    fun search(query: String): Flow<List<DownloadTaskEntity>>

    @Query("SELECT COUNT(*) FROM download_tasks WHERE status = :status")
    suspend fun countByStatus(status: Int): Int

    @Query("UPDATE download_tasks SET status = :status, updatedAt = :now WHERE taskId = :taskId")
    suspend fun setStatus(taskId: String, status: Int, now: Long)

    @Query(
        """
        UPDATE download_tasks
        SET progress = :progress, stage = :stage, detail = :detail, updatedAt = :now
        WHERE taskId = :taskId AND status = :runningStatus
        """
    )
    suspend fun updateProgress(
        taskId: String,
        progress: Int,
        stage: String,
        detail: String,
        now: Long,
        runningStatus: Int
    )

    @Query("DELETE FROM download_tasks WHERE taskId = :taskId")
    suspend fun deleteById(taskId: String)

    @Query("DELETE FROM download_tasks WHERE status IN (:statuses)")
    suspend fun deleteByStatuses(statuses: List<Int>)

    @Query("DELETE FROM download_tasks WHERE status = :status")
    suspend fun deleteByStatus(status: Int)

    @Query("SELECT COUNT(*) FROM download_tasks")
    suspend fun countAll(): Int
}
