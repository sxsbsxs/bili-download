package com.example.bilidl

internal object PlaybackSpeedPolicy {
    val SUPPORTED = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)

    fun label(speed: Float): String {
        val value = if (speed % 1f == 0f) {
            String.format(java.util.Locale.US, "%.1f", speed)
        } else {
            String.format(java.util.Locale.US, "%.2f", speed).trimEnd('0').trimEnd('.')
        }
        return value + "x"
    }
}
