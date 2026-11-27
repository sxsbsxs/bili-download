package com.example.bilidl.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.bilidl.data.local.entity.DownloadResourceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadResourceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(resource: DownloadResourceEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(resources: List<DownloadResourceEntity>)

    @Query("SELECT * FROM download_resources WHERE taskId = :taskId ORDER BY createdAt, displayName")
    suspend fun getByTask(taskId: String): List<DownloadResourceEntity>

    @Query("SELECT * FROM download_resources WHERE taskId = :taskId ORDER BY createdAt, displayName")
    fun observeByTask(taskId: String): Flow<List<DownloadResourceEntity>>

    @Query("DELETE FROM download_resources WHERE taskId = :taskId")
    suspend fun deleteByTask(taskId: String)

    @Query("SELECT COUNT(*) FROM download_resources WHERE taskId = :taskId")
    suspend fun countByTask(taskId: String): Int
}
