package com.example.bilidl

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PictureInPicturePolicyTest {
    @Test
    fun entersOnlyWhenEnabledSupportedAndPlayingVideo() {
        assertTrue(
            PictureInPicturePolicy.shouldEnter(
                enabled = true,
                systemSupported = true,
                isVideo = true,
                isPlaying = true
            )
        )
    }

    @Test
    fun disabledPreferenceBlocksAutomaticPip() {
        assertFalse(PictureInPicturePolicy.shouldEnter(true.not(), true, true, true))
    }

    @Test
    fun unsupportedDeviceBlocksPip() {
        assertFalse(PictureInPicturePolicy.shouldEnter(true, false, true, true))
    }

    @Test
    fun audioAndPausedVideoNeverEnterPip() {
        assertFalse(PictureInPicturePolicy.shouldEnter(true, true, false, true))
        assertFalse(PictureInPicturePolicy.shouldEnter(true, true, true, false))
    }
}
