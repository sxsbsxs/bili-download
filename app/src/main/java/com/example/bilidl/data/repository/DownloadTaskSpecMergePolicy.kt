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
            bvid = existing.bvid ?: incoming.bvid,
            aid = existing.aid.takeIf { it > 0L } ?: incoming.aid,
            cid = existing.cid.takeIf { it > 0L } ?: incoming.cid,
            pageNumber = existing.pageNumber.takeIf { it > 0 } ?: incoming.pageNumber,
            title = existing.title.takeIf { it.isNotBlank() } ?: incoming.title,
            uploader = existing.uploader.takeIf { it.isNotBlank() } ?: incoming.uploader,
            durationSeconds = existing.durationSeconds.takeIf { it > 0 } ?: incoming.durationSeconds,
            coverUrl = existing.coverUrl ?: incoming.coverUrl,
            qualityId = existing.qualityId.takeIf { it > 0 } ?: incoming.qualityId,
            audioOnly = existing.audioOnly,
            downloadDanmaku = existing.downloadDanmaku,
            downloadSubtitles = existing.downloadSubtitles,
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
