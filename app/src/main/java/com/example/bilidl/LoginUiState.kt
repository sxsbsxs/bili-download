package com.example.bilidl

import android.graphics.Bitmap

enum class LoginFailureSource {
    QR_CODE,
    COOKIE
}

/** One-way UI state for local Bilibili account authorization. */
sealed interface LoginUiState {
    data object Idle : LoginUiState
    data object CheckingSession : LoginUiState
    data object RequestingQr : LoginUiState
    data class QrReady(val bitmap: Bitmap, val url: String) : LoginUiState
    data object Scanned : LoginUiState
    data object Expired : LoginUiState
    data object VerifyingCookie : LoginUiState
    data class Success(val username: String, val vip: Boolean) : LoginUiState
    data class Error(val message: String, val source: LoginFailureSource) : LoginUiState
    data object LoggedOut : LoginUiState
}
