package com.example.bilidl

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.bilidl.data.local.entity.DownloadTaskEntity
import com.example.bilidl.data.local.entity.DownloadTaskStatus
import com.example.bilidl.data.local.entity.DownloadTaskEventEntity
import com.example.bilidl.data.local.entity.DownloadResourceEntity
import com.example.bilidl.data.repository.BiliDownloadTaskRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns download-history and Room commands used by the download-management screen.
 *
 * WorkManager remains the execution scheduler. This ViewModel handles durable
 * metadata, resource ownership and task-history queries without blocking the
 * Activity or doing database work in view callbacks.
 */
class DownloadsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository by lazy {
        BiliDownloadTaskRepository.getInstance(getApplication())
    }

    fun registerTask(task: DownloadTaskEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.registerTask(task, preferIncomingSpec = true) }
                .onFailure { Log.e(TAG, "Unable to save task ${task.taskId}", it) }
        }
    }

    fun markPaused(taskId: String, reason: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.markPaused(taskId, reason) }
                .onFailure { Log.e(TAG, "Unable to persist pause state for $taskId", it) }
        }
    }

    suspend fun loadTasks(): List<DownloadTaskEntity> = withContext(Dispatchers.IO) {
        repository.getAllTasks()
    }

    suspend fun loadRecentEvents(taskId: String, limit: Int = 50): List<DownloadTaskEventEntity> =
        withContext(Dispatchers.IO) {
            repository.getRecentEvents(taskId, limit)
        }

    suspend fun loadResources(taskId: String): List<DownloadResourceEntity> =
        withContext(Dispatchers.IO) {
            repository.getResources(taskId)
        }

    suspend fun retryTask(taskId: String): Boolean = withContext(Dispatchers.IO) {
        val task = repository.getTask(taskId) ?: return@withContext false
        if (task.status != DownloadTaskStatus.FAILED &&
            task.status != DownloadTaskStatus.PAUSED
        ) {
            return@withContext false
        }
        repository.retry(taskId)
        true
    }

    fun deleteTask(taskId: String, deletePublishedFiles: Boolean, extraUri: Uri? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val publishedUris = repository.getResources(taskId)
                    .map { it.contentUri }
                    .plus(listOfNotNull(extraUri?.toString()))
                    .distinct()

                if (deletePublishedFiles) {
                    publishedUris.forEach { rawUri ->
                        runCatching {
                            getApplication<Application>().contentResolver.delete(
                                Uri.parse(rawUri),
                                null,
                                null
                            )
                        }.onFailure {
                            Log.w(TAG, "Unable to delete published resource $rawUri", it)
                        }
                    }
                }
                repository.deleteTask(taskId)
            }.onFailure {
                Log.e(TAG, "Unable to delete download task $taskId", it)
            }
        }
    }

    fun deleteCompletedTasks() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.deleteCompletedTasks() }
                .onFailure { Log.e(TAG, "Unable to clear completed task history", it) }
        }
    }

    companion object {
        private const val TAG = "DownloadsViewModel"
    }
}
