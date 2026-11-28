package com.example.bilidl

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns Bilibili authorization jobs and local SESSDATA storage.
 *
 * QR generation, polling and cookie verification run on IO. Polling waits
 * cooperatively, so changing tabs or leaving the login screen cancels the job
 * instead of occupying an executor thread with Thread.sleep().
 */
class LoginViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow<LoginUiState>(LoginUiState.Idle)
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    private var authJob: Job? = null
    private var qrJob: Job? = null

    fun restoreSession() {
        qrJob?.cancel()
        qrJob = null
        authJob?.cancel()
        authJob = viewModelScope.launch {
            _state.value = LoginUiState.CheckingSession
            val cookie = withContext(Dispatchers.IO) {
                SecureCookieStore.load(getApplication())
            }
            if (cookie.isNullOrBlank()) {
                _state.value = LoginUiState.Idle
                return@launch
            }
            try {
                val account = withContext(Dispatchers.IO) {
                    BiliRepository().verifyCookie(cookie)
                }
                _state.value = LoginUiState.Success(account.first, account.second)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                withContext(Dispatchers.IO) {
                    SecureCookieStore.clear(getApplication())
                }
                _state.value = LoginUiState.Idle
            }
        }
    }

    fun requestQrCode() {
        authJob?.cancel()
        authJob = null
        qrJob?.cancel()
        qrJob = viewModelScope.launch {
            _state.value = LoginUiState.RequestingQr
            try {
                val session = withContext(Dispatchers.IO) {
                    BiliQrLoginClient.generate()
                }
                val bitmap = withContext(Dispatchers.IO) {
                    BiliQrLoginClient.renderQr(session.url)
                }
                _state.value = LoginUiState.QrReady(bitmap)

                val expiresAt = System.currentTimeMillis() + QR_VALIDITY_MILLIS
                while (System.currentTimeMillis() < expiresAt) {
                    val result = withContext(Dispatchers.IO) {
                        BiliQrLoginClient.poll(session)
                    }
                    when (result.state) {
                        BiliQrLoginState.WAITING -> Unit
                        BiliQrLoginState.SCANNED -> _state.value = LoginUiState.Scanned
                        BiliQrLoginState.EXPIRED -> {
                            _state.value = LoginUiState.Expired
                            return@launch
                        }
                        BiliQrLoginState.SUCCESS -> {
                            val sessdata = result.sessdata
                                ?: throw IllegalStateException("扫码成功，但没有获取到登录凭据")
                            val account = withContext(Dispatchers.IO) {
                                val verified = BiliRepository().verifyCookie(sessdata)
                                SecureCookieStore.save(getApplication(), sessdata)
                                verified
                            }
                            _state.value = LoginUiState.Success(account.first, account.second)
                            return@launch
                        }
                    }
                    delay(POLL_INTERVAL_MILLIS)
                }
                _state.value = LoginUiState.Expired
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.value = LoginUiState.Error(
                    error.message ?: "B站扫码登录失败，请重试",
                    LoginFailureSource.QR_CODE
                )
            }
        }
    }

    fun loginWithSessdata(rawSessdata: String) {
        verifyAndSaveCookie(rawSessdata, LoginFailureSource.COOKIE)
    }

    /** BVD-style official WebView login hands the verified site's SESSDATA here. */
    fun loginWithWebCookie(rawCookieHeader: String) {
        verifyAndSaveCookie(rawCookieHeader, LoginFailureSource.WEBVIEW)
    }

    private fun verifyAndSaveCookie(rawCookie: String, failureSource: LoginFailureSource) {
        qrJob?.cancel()
        qrJob = null
        authJob?.cancel()
        authJob = viewModelScope.launch {
            _state.value = LoginUiState.VerifyingCookie
            try {
                val account = withContext(Dispatchers.IO) {
                    // SecureCookieStore extracts and encrypts only SESSDATA.
                    // Never persist the full WebView Cookie header in plaintext.
                    val verified = BiliRepository().verifyCookie(rawCookie)
                    SecureCookieStore.save(getApplication(), rawCookie)
                    verified
                }
                _state.value = LoginUiState.Success(account.first, account.second)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.value = LoginUiState.Error(
                    error.message ?: "授权验证失败",
                    failureSource
                )
            }
        }
    }

    fun stopQrLogin() {
        qrJob?.cancel()
        qrJob = null
        when (_state.value) {
            LoginUiState.RequestingQr,
            is LoginUiState.QrReady,
            LoginUiState.Scanned -> _state.value = LoginUiState.Idle
            else -> Unit
        }
    }

    fun logout() {
        qrJob?.cancel()
        qrJob = null
        authJob?.cancel()
        authJob = null
        viewModelScope.launch(Dispatchers.IO) {
            SecureCookieStore.clear(getApplication())
            _state.value = LoginUiState.LoggedOut
        }
    }

    override fun onCleared() {
        qrJob?.cancel()
        qrJob = null
        authJob?.cancel()
        authJob = null
        super.onCleared()
    }

    companion object {
        private const val QR_VALIDITY_MILLIS = 180_000L
        private const val POLL_INTERVAL_MILLIS = 2_000L
    }
}
