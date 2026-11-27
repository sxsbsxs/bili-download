package com.example.bilidl.data.repository

import com.example.bilidl.data.local.entity.DownloadTaskStatus

internal enum class DownloadTaskRecoveryAction {
    ENQUEUE_MISSING_WORK,
    OBSERVE_EXISTING_WORK,
    RESTORE_TERMINAL_STATE
}

/**
 * Reconcile Room's durable user-facing task state with WorkManager's execution ledger.
 *
 * A null WorkInfo count means the lookup failed or timed out: prefer observing the
 * scheduler over replacing work whose existence could not be confirmed.
 */
internal object DownloadTaskRecoveryPolicy {
    fun decide(taskStatus: Int, workInfoCount: Int?): DownloadTaskRecoveryAction {
        val executionIsExpected =
            taskStatus == DownloadTaskStatus.QUEUED ||
                taskStatus == DownloadTaskStatus.RUNNING
        if (!executionIsExpected) {
            return DownloadTaskRecoveryAction.RESTORE_TERMINAL_STATE
        }
        return if (workInfoCount == 0) {
            DownloadTaskRecoveryAction.ENQUEUE_MISSING_WORK
        } else {
            DownloadTaskRecoveryAction.OBSERVE_EXISTING_WORK
        }
    }
}
