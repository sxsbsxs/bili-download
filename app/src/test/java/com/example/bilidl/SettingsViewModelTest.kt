package com.example.bilidl

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsViewModelTest {
    private lateinit var application: Application

    @Before
    fun clearPreferences() {
        application = ApplicationProvider.getApplicationContext()
        application.getSharedPreferences("ui", Application.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun exposesDefaultsAndPersistsAllDownloadPreferences() {
        val viewModel = SettingsViewModel(application)

        assertEquals(AppSettingsState(), viewModel.state.value)

        viewModel.setInt("quality", 3)
        viewModel.setInt("concurrent", 2)
        viewModel.setBoolean("wifi_only", false)
        viewModel.setBoolean("danmaku", true)
        viewModel.setBoolean("subtitles", true)
        viewModel.setBoolean("audio_only", true)
        viewModel.setBoolean("notify", false)

        assertEquals(
            AppSettingsState(
                quality = 3,
                concurrentDownloads = 2,
                wifiOnly = false,
                downloadDanmaku = true,
                downloadSubtitles = true,
                audioOnly = true,
                notifyOnCompletion = false
            ),
            viewModel.state.value
        )

        val restored = SettingsViewModel(application)
        assertEquals(viewModel.state.value, restored.state.value)
    }
}
