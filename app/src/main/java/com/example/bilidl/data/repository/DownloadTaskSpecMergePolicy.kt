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
    fun merge(
        existing: DownloadTaskEntity,
        incoming: DownloadTaskEntity,
        preferIncomingSpec: Boolean = false
    ): DownloadTaskEntity = incoming.copy(
            bvid = choose(incoming.bvid, existing.bvid, preferIncomingSpec),
            aid = choose(incoming.aid, existing.aid, preferIncomingSpec),
            cid = choose(incoming.cid, existing.cid, preferIncomingSpec),
            pageNumber = choose(incoming.pageNumber, existing.pageNumber, preferIncomingSpec),
            title = chooseText(incoming.title, existing.title, preferIncomingSpec),
            uploader = chooseText(incoming.uploader, existing.uploader, preferIncomingSpec),
            durationSeconds = choose(incoming.durationSeconds, existing.durationSeconds, preferIncomingSpec),
            coverUrl = choose(incoming.coverUrl, existing.coverUrl, preferIncomingSpec),
            qualityId = choose(incoming.qualityId, existing.qualityId, preferIncomingSpec),
            audioOnly = choose(incoming.audioOnly, existing.audioOnly, preferIncomingSpec),
            downloadDanmaku = choose(incoming.downloadDanmaku, existing.downloadDanmaku, preferIncomingSpec),
            downloadSubtitles = choose(incoming.downloadSubtitles, existing.downloadSubtitles, preferIncomingSpec),
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

    private fun <T> choose(incoming: T, existing: T, preferIncoming: Boolean): T =
        if (preferIncoming) incoming else existing

    private fun choose(incoming: Long, existing: Long, preferIncoming: Boolean): Long =
        if (preferIncoming && incoming > 0L) incoming else existing.takeIf { it > 0L } ?: incoming

    private fun choose(incoming: Int, existing: Int, preferIncoming: Boolean): Int =
        if (preferIncoming && incoming > 0) incoming else existing.takeIf { it > 0 } ?: incoming

    private fun choose(incoming: String?, existing: String?, preferIncoming: Boolean): String? =
        if (preferIncoming) incoming ?: existing else existing ?: incoming

    private fun chooseText(incoming: String, existing: String, preferIncoming: Boolean): String =
        if (preferIncoming && incoming.isNotBlank()) incoming else existing.takeIf { it.isNotBlank() } ?: incoming
}
