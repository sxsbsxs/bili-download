package com.example.bilidl

enum class BiliSearchCategory(
    val apiType: String,
    val displayName: String
) {
    VIDEO("video", "视频"),
    ANIME("media_bangumi", "番剧"),
    FILM_TV("media_ft", "影视")
}

data class BiliSearchResult(
    val url: String,
    val title: String,
    val author: String,
    val coverUrl: String?,
    val detail: String
)
