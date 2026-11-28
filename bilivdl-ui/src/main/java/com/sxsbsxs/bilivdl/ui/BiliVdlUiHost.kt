package com.sxsbsxs.bilivdl.ui

/**
 * Boundary between the BiliVdl UI component and the host application's real
 * repository, account and WorkManager implementations.
 */
interface BiliVdlUiHost {
    fun loadPopular(page: Int, callback: (Result<List<BiliVdlVideo>>) -> Unit)
    fun searchVideos(query: String, page: Int, callback: (Result<List<BiliVdlVideo>>) -> Unit)
    fun openVideo(video: BiliVdlVideo)
    fun openUserSpace(mid: Long)
    fun openDanmakuSettings()
    fun chooseDownloadDirectory()
    fun navigateToDiscover()
    fun loadDownloadTasks(callback: (List<BiliVdlDownloadTask>) -> Unit)
    fun pauseDownload(taskId: String)
    fun resumeDownload(taskId: String)
    fun cancelDownload(taskId: String)
    fun deleteDownload(taskId: String, deleteSavedFile: Boolean)
    fun clearCompletedDownloads()
    fun retryFailedDownloads()
    fun clearDownloadHistory()
    fun loadAccount(callback: (BiliVdlAccount) -> Unit)
    fun loadSettings(callback: (BiliVdlSettings) -> Unit)
    fun updateSetting(key: String, value: Any)
    fun clearTemporaryCache()
    fun requestLogin()
    fun requestLogout()
    fun openRepository()
    fun checkForUpdates()
}
