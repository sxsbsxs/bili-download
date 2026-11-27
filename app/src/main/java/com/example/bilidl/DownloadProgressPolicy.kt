package com.example.bilidl

/**
 * Decides which transfer progress callbacks should reach WorkManager and Room.
 *
 * The HTTP range engine can emit callbacks for every buffer write. UI progress may
 * be published whenever its visible value changes, while durable snapshots are
 * written less frequently to keep SQLite I/O out of the transfer hot path.
 */
internal class DownloadProgressPolicy(
    private val persistenceIntervalPercent: Int = DEFAULT_PERSISTENCE_INTERVAL_PERCENT
) {
    private var lastPublishedProgress = -1
    private var lastPublishedDetail = ""
    private var lastPersistedProgress = -persistenceIntervalPercent
    private var lastPersistedStage = ""

    init {
        require(persistenceIntervalPercent > 0) {
            "Progress persistence interval must be positive"
        }
    }

    fun evaluate(progress: Int, detail: String): DownloadProgressDecision {
        val safeProgress = progress.coerceIn(0, 100)
        val publish = safeProgress != lastPublishedProgress || detail != lastPublishedDetail
        if (!publish) return DownloadProgressDecision.NONE

        lastPublishedProgress = safeProgress
        lastPublishedDetail = detail

        val persist = safeProgress - lastPersistedProgress >= persistenceIntervalPercent ||
            detail != lastPersistedStage ||
            safeProgress >= 100

        if (persist) {
            lastPersistedProgress = safeProgress
            lastPersistedStage = detail
        }
        return DownloadProgressDecision(
            publishWorkProgress = true,
            persistSnapshot = persist
        )
    }

    companion object {
        const val DEFAULT_PERSISTENCE_INTERVAL_PERCENT = 3
    }
}

internal data class DownloadProgressDecision(
    val publishWorkProgress: Boolean,
    val persistSnapshot: Boolean
) {
    companion object {
        val NONE = DownloadProgressDecision(
            publishWorkProgress = false,
            persistSnapshot = false
        )
    }
}
