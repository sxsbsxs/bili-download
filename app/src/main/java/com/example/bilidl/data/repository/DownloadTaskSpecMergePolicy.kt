package com.example.bilidl.data.repository

import com.example.bilidl.data.local.entity.DownloadTaskEntity

/**
 * Merges task input received from different process boundaries.
 *
 * The Activity knows presentation metadata such as uploader and cover URL.
 * The WorkManager input contains execution identifiers but may intentionally
 * omit those display-only fields. Re-registration must not erase richer data.
 */
internal object DownloadTaskSpecMergePolicy {
    fun merge(existing: DownloadTaskEntity, incoming: DownloadTaskEntity): DownloadTaskEntity =
        incoming.copy(
            bvid = incoming.bvid ?: existing.bvid,
            aid = incoming.aid.takeIf { it > 0L } ?: existing.aid,
            cid = incoming.cid.takeIf { it > 0L } ?: existing.cid,
            pageNumber = incoming.pageNumber.takeIf { it > 0 } ?: existing.pageNumber,
            title = incoming.title.takeIf { it.isNotBlank() } ?: existing.title,
            uploader = incoming.uploader.takeIf { it.isNotBlank() } ?: existing.uploader,
            durationSeconds = incoming.durationSeconds.takeIf { it > 0 } ?: existing.durationSeconds,
            coverUrl = incoming.coverUrl ?: existing.coverUrl,
            qualityId = incoming.qualityId.takeIf { it > 0 } ?: existing.qualityId,
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
}
