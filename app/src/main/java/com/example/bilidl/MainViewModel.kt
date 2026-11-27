package com.example.bilidl

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns the home screen's Bilibili link parsing lifecycle.
 *
 * The Activity only sends user input and observes [parseState]. Parsing runs
 * off the main thread, and a newer request cancels the previous one so slow
 * responses cannot replace a more recent user selection.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _parseState = MutableStateFlow<VideoParseState>(VideoParseState.Idle)
    val parseState: StateFlow<VideoParseState> = _parseState.asStateFlow()

    private var parseJob: Job? = null

    fun parse(rawInput: String) {
        val input = rawInput.trim()
        if (input.isEmpty()) {
            parseJob?.cancel()
            _parseState.value = VideoParseState.Error("请输入 B 站视频链接")
            return
        }

        parseJob?.cancel()
        parseJob = viewModelScope.launch {
            _parseState.value = VideoParseState.Loading
            try {
                val video = withContext(Dispatchers.IO) {
                    BiliRepository {
                        SecureCookieStore.load(getApplication())
                    }.inspect(input)
                }
                _parseState.value = VideoParseState.Success(video)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _parseState.value = VideoParseState.Error(
                    error.message ?: "解析失败，请检查网络后重试"
                )
            }
        }
    }

    override fun onCleared() {
        parseJob?.cancel()
        parseJob = null
        super.onCleared()
    }
}
