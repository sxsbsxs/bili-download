package com.example.bilidl.data.repository

import android.content.Context
import androidx.room.withTransaction
import com.example.bilidl.data.local.BiliDownloadDatabase
import com.example.bilidl.data.local.entity.DownloadResourceEntity
import com.example.bilidl.data.local.entity.DownloadTaskEntity
import com.example.bilidl.data.local.entity.DownloadTaskEventEntity
import com.example.bilidl.data.local.entity.DownloadTaskStatus
import kotlinx.coroutines.flow.Flow

/**
 * Transactional task lifecycle and history API.
 *
 * The repository is intentionally independent from Activity/Worker classes so
 * download queue, retry, history, and resource screens can share one source of truth.
 */
class BiliDownloadTaskRepository private constructor(
    private val database: BiliDownloadDatabase
) {
    private val tasks = database.downloadTaskDao()
    private val resources = database.downloadResourceDao()
    private val events = database.downloadTaskEventDao()

    fun observeAll(): Flow<List<DownloadTaskEntity>> = tasks.observeAll()

    fun observeByStatuses(statuses: List<Int>): Flow<List<DownloadTaskEntity>> =
        tasks.observeByStatuses(statuses)

    fun search(query: String): Flow<List<DownloadTaskEntity>> = tasks.search(query.trim())

    fun observeResources(taskId: String): Flow<List<DownloadResourceEntity>> =
        resources.observeByTask(taskId)

    fun observeRecentEvents(taskId: String, limit: Int = 100) =
        events.observeRecent(taskId, limit)

    suspend fun getTask(taskId: String): DownloadTaskEntity? = tasks.getById(taskId)

    suspend fun getAllTasks(): List<DownloadTaskEntity> = tasks.getAll()

    suspend fun getResources(taskId: String): List<DownloadResourceEntity> =
        resources.getByTask(taskId)

    suspend fun registerTask(task: DownloadTaskEntity) {
        database.withTransaction {
            val existing = tasks.getById(task.taskId)
            if (existing == null) {
                tasks.upsert(task)
                events.insert(
                    DownloadTaskEventEntity(
                        taskId = task.taskId,
                        fromStatus = null,
                        toStatus = task.status,
                        stage = task.stage,
                        message = "任务已创建"
                    )
                )
            } else {
                tasks.upsert(
                    task.copy(
                        createdAt = existing.createdAt,
                        status = existing.status,
                        progress = existing.progress,
                        stage = existing.stage,
                        detail = existing.detail,
                        completedAt = existing.completedAt,
                        outputUri = existing.outputUri,
                        outputName = existing.outputName,
                        outputMimeType = existing.outputMimeType,
                        outputSizeBytes = existing.outputSizeBytes,
                        errorMessage = existing.errorMessage,
                        attemptCount = existing.attemptCount
                    )
                )
            }
        }
    }

    suspend fun markQueued(
        taskId: String,
        detail: String,
        countAttempt: Boolean = false,
        resetProgress: Boolean = false
    ) {
        transition(
            taskId = taskId,
            targetStatus = DownloadTaskStatus.QUEUED,
            stage = "等待下载",
            detail = detail,
            countAttempt = countAttempt,
            resetProgress = resetProgress
        )
    }

    suspend fun markRunning(taskId: String) {
        transition(
            taskId = taskId,
            targetStatus = DownloadTaskStatus.RUNNING,
            stage = "正在下载",
            detail = "后台任务已启动"
        )
    }

    suspend fun reportProgress(taskId: String, progress: Int, stage: String, detail: String) {
        database.withTransaction {
            val task = tasks.getById(taskId) ?: return@withTransaction
            if (task.status != DownloadTaskStatus.RUNNING) return@withTransaction
            val nextProgress = progress.coerceIn(task.progress, 100)
            tasks.updateProgress(
                taskId = taskId,
                progress = nextProgress,
                stage = stage.take(120),
                detail = detail.take(1000)
            )
        }
    }

    suspend fun markCompleted(
        taskId: String,
        outputUri: String,
        outputName: String,
        mimeType: String,
        sizeBytes: Long,
        additionalResources: List<DownloadResourceEntity> = emptyList()
    ) {
        database.withTransaction {
            val current = tasks.getById(taskId) ?: return@withTransaction
            if (!DownloadTaskStatus.canTransition(current.status, DownloadTaskStatus.COMPLETED)) {
                return@withTransaction
            }
            val now = System.currentTimeMillis()
            tasks.update(
                current.copy(
                    status = DownloadTaskStatus.COMPLETED,
                    progress = 100,
                    stage = "已完成",
                    detail = "文件已保存",
                    updatedAt = now,
                    completedAt = now,
                    outputUri = outputUri,
                    outputName = outputName,
                    outputMimeType = mimeType,
                    outputSizeBytes = sizeBytes.coerceAtLeast(0L),
                    errorMessage = null
                )
            )
            val allResources = listOf(
                DownloadResourceEntity(
                    resourceId = "$taskId:media",
                    taskId = taskId,
                    resourceType = com.example.bilidl.data.local.entity.DownloadResourceType.MEDIA,
                    displayName = outputName,
                    mimeType = mimeType,
                    contentUri = outputUri,
                    sizeBytes = sizeBytes.coerceAtLeast(0L)
                )
            ) + additionalResources
            resources.deleteByTask(taskId)
            if (allResources.isNotEmpty()) resources.upsertAll(allResources)
            events.insert(
                DownloadTaskEventEntity(
                    taskId = taskId,
                    fromStatus = current.status,
                    toStatus = DownloadTaskStatus.COMPLETED,
                    stage = "已完成",
                    message = "下载完成"
                )
            )
        }
    }

    suspend fun markFailed(taskId: String, message: String) {
        transition(
            taskId = taskId,
            targetStatus = DownloadTaskStatus.FAILED,
            stage = "下载失败",
            detail = message.take(1000),
            errorMessage = message.take(2000)
        )
    }

    suspend fun markPaused(taskId: String, reason: String = "任务已暂停") {
        transition(
            taskId = taskId,
            targetStatus = DownloadTaskStatus.PAUSED,
            stage = "已暂停",
            detail = reason.take(1000)
        )
    }

    suspend fun retry(taskId: String) {
        database.withTransaction {
            val current = tasks.getById(taskId) ?: return@withTransaction
            require(
                current.status == DownloadTaskStatus.FAILED ||
                    current.status == DownloadTaskStatus.PAUSED
            ) { "只有失败或暂停的任务可以重试" }
            transitionInsideTransaction(
                current = current,
                targetStatus = DownloadTaskStatus.QUEUED,
                stage = "等待重试",
                detail = "已重新加入下载队列",
                countAttempt = true
            )
        }
    }

    suspend fun deleteTask(taskId: String) {
        // Resource files are not deleted here. The caller must clean up MediaStore
        // content URIs explicitly before removing the history record.
        tasks.deleteById(taskId)
    }

    suspend fun deleteTerminalTasks() {
        tasks.deleteByStatuses(
            listOf(DownloadTaskStatus.FAILED, DownloadTaskStatus.COMPLETED)
        )
    }

    suspend fun countByStatus(status: Int): Int = tasks.countByStatus(status)

    private suspend fun transition(
        taskId: String,
        targetStatus: Int,
        stage: String,
        detail: String,
        countAttempt: Boolean = false,
        resetProgress: Boolean = false,
        errorMessage: String? = null
    ) {
        database.withTransaction {
            val current = tasks.getById(taskId) ?: return@withTransaction
            transitionInsideTransaction(
                current = current,
                targetStatus = targetStatus,
                stage = stage,
                detail = detail,
                countAttempt = countAttempt,
                resetProgress = resetProgress,
                errorMessage = errorMessage
            )
        }
    }

    private suspend fun transitionInsideTransaction(
        current: DownloadTaskEntity,
        targetStatus: Int,
        stage: String,
        detail: String,
        countAttempt: Boolean = false,
        resetProgress: Boolean = false,
        errorMessage: String? = null
    ) {
        if (!DownloadTaskStatus.canTransition(current.status, targetStatus)) return
        val now = System.currentTimeMillis()
        val next = current.copy(
            status = targetStatus,
            progress = if (resetProgress) 0 else current.progress,
            stage = stage.take(120),
            detail = detail.take(1000),
            updatedAt = now,
            errorMessage = errorMessage,
            attemptCount = current.attemptCount + if (countAttempt) 1 else 0
        )
        tasks.update(next)
        if (current.status != targetStatus) {
            events.insert(
                DownloadTaskEventEntity(
                    taskId = current.taskId,
                    fromStatus = current.status,
                    toStatus = targetStatus,
                    stage = next.stage,
                    message = next.detail
                )
            )
        }
    }

    companion object {
        @Volatile
        private var instance: BiliDownloadTaskRepository? = null

        fun getInstance(context: Context): BiliDownloadTaskRepository =
            instance ?: synchronized(this) {
                instance ?: BiliDownloadTaskRepository(
                    BiliDownloadDatabase.getInstance(context.applicationContext)
                ).also { instance = it }
            }
    }
}
