package com.example.bilidl

/** Separates PiP preference/support checks from Activity lifecycle behavior. */
internal object PictureInPicturePolicy {
    fun shouldEnter(
        enabled: Boolean,
        systemSupported: Boolean,
        isVideo: Boolean,
        isPlaying: Boolean
    ): Boolean = enabled && systemSupported && isVideo && isPlaying
}
