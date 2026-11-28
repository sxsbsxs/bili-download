package com.example.bilidl

internal object PlaybackSpeedPolicy {
    val SUPPORTED = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)

    fun label(speed: Float): String =
        String.format(java.util.Locale.US, "%.2gx", speed)
}
