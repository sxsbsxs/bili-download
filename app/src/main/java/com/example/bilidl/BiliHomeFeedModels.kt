package com.example.bilidl

enum class BiliHomeFeedType(val title: String) {
    RECOMMEND("推荐"),
    POPULAR("热门")
}

data class BiliHomeFeedItem(
    val url: String,
    val bvid: String?,
    val aid: Long,
    val title: String,
    val uploader: String,
    val coverUrl: String?,
    val duration: String,
    val viewCount: Long,
    val danmakuCount: Long,
    val likeCount: Long,
    val reason: String = ""
)

data class BiliHomeFeedPage(
    val items: List<BiliHomeFeedItem>,
    val hasMore: Boolean
)
