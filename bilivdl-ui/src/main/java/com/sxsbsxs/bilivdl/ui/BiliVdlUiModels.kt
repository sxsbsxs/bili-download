package com.sxsbsxs.bilivdl.ui

data class BiliVdlVideo(
    val title: String,
    val cover: String,
    val author: String,
    val play: String,
    val duration: String,
    val bvid: String,
    val aid: Long,
    val description: String = "",
    val authorMid: Long = 0L,
    val authorAvatarUrl: String? = null
)

data class BiliVdlComment(
    val id: Long,
    val user: String,
    val text: String,
    val likes: Long,
    val replyCount: Long,
    val liked: Boolean = false,
    val avatarUrl: String? = null,
    val userMid: Long = 0L
)

data class BiliVdlDanmaku(
    val text: String,
    val timeMs: Long,
    val color: Int = 0xFFFFFF,
    val fontSize: Int = 25,
    val mode: Int = 1
)

data class BiliVdlDownloadTask(
    val id: String,
    val title: String,
    val quality: String,
    val status: String,
    val progress: Int,
    val detail: String = ""
)

data class BiliVdlAccount(
    val name: String,
    val status: String,
    val loggedIn: Boolean,
    val avatarUrl: String? = null,
    val signature: String = "",
    val mid: Long = 0L
)

data class BiliVdlSettings(
    val qualityIndex: Int,
    val concurrencyIndex: Int,
    val wifiOnly: Boolean,
    val downloadDanmaku: Boolean,
    val downloadSubtitles: Boolean,
    val audioOnly: Boolean,
    val downloadPath: String,
    val batchQualityMismatchIndex: Int = 0
)
