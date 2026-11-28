package com.example.bilidl

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackSpeedPolicyTest {
    @Test
    fun exposesCommonPlaybackSpeeds() {
        assertEquals(listOf(0.75f, 1f, 1.25f, 1.5f, 2f), PlaybackSpeedPolicy.SUPPORTED)
    }

    @Test
    fun formatsSpeedMenuLabels() {
        assertEquals("1.0x", PlaybackSpeedPolicy.label(1f))
        assertEquals("1.5x", PlaybackSpeedPolicy.label(1.5f))
    }
}
