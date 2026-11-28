package com.example.bilidl

enum class BiliAccountSection(val title: String) {
    SUBMISSIONS("我的投稿"),
    HISTORY("观看历史"),
    WATCH_LATER("稍后再看"),
    RECENT_LIKES("最近点赞"),
    FAVORITES("收藏夹")
}

data class BiliAccountProfile(
    val mid: Long,
    val username: String,
    val faceUrl: String?,
    val sign: String
)

data class BiliAccountLibraryItem(
    val url: String,
    val title: String,
    val author: String,
    val coverUrl: String?,
    val detail: String
)

data class BiliFavoriteFolder(
    val id: Long,
    val title: String,
    val mediaCount: Int,
    val coverUrl: String?
)

data class BiliHistoryCursorState(
    val max: Long = 0L,
    val viewAt: Long = 0L,
    val business: String = ""
)

data class BiliAccountLibraryPage(
    val items: List<BiliAccountLibraryItem> = emptyList(),
    val folders: List<BiliFavoriteFolder> = emptyList(),
    val nextHistoryCursor: BiliHistoryCursorState? = null,
    val hasMore: Boolean = false
)
