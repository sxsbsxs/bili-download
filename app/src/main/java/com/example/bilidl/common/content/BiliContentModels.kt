package com.example.bilidl.common.content

data class BiliDanmaku(
    val id: Long,
    val progressMs: Long,
    val mode: Int,
    val fontSize: Int,
    val color: Long,
    val senderHash: String,
    val content: String,
    val sentAtEpochSeconds: Long,
    val pool: Int
)

data class BiliSubtitleTrack(
    val id: Long,
    val language: String,
    val languageName: String,
    val url: String
)

data class BiliSubtitleCue(
    val startSeconds: Double,
    val endSeconds: Double,
    val content: String
)
