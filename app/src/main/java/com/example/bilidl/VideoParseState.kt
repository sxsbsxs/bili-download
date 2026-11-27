package com.example.bilidl

/**
 * UI state for parsing a single Bilibili link.
 *
 * Keeping the resolved BiliVideo in the state lets the Activity render the
 * result without owning network work or coordinating parse callbacks.
 */
internal sealed interface VideoParseState {
    data object Idle : VideoParseState
    data object Loading : VideoParseState
    data class Success(val video: BiliVideo) : VideoParseState
    data class Error(val message: String) : VideoParseState
}
