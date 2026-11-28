package com.example.bilidl

/**
 * BVD's multi-P stream compatibility order:
 * exact track, same codec family at the nearest lower/equal quality,
 * same quality with another codec, then nearest lower/equal quality of any codec.
 * A later P is never promoted above the user's first-P selection.
 */
internal object BiliBatchStreamPolicy {
    fun selectCompatible(
        preferred: BiliStreamChoice?,
        available: List<BiliStreamChoice>
    ): BiliStreamChoice? {
        if (preferred == null || available.isEmpty()) return null
        val preferredFamily = preferred.codecFamily()

        available.firstOrNull {
            it.id == preferred.id && it.codecFamily() == preferredFamily
        }?.let { return it }

        available.asSequence()
            .filter { it.codecFamily() == preferredFamily && it.id <= preferred.id }
            .maxWithOrNull(compareBy<BiliStreamChoice> { it.id }.thenBy { it.bandwidth })
            ?.let { return it }

        available.firstOrNull { it.id == preferred.id }?.let { return it }

        return available.asSequence()
            .filter { it.id <= preferred.id }
            .maxWithOrNull(compareBy<BiliStreamChoice> { it.id }.thenBy { it.bandwidth })
    }
}
