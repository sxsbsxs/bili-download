package com.example.bilidl

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferCheckpointPolicyTest {
    @Test
    fun reusesCheckpointWhenEtagAndFilesMatch() {
        assertTrue(
            TransferCheckpointPolicy.canReuse(
                etag = "\"video-etag\"",
                lastModified = "",
                targetMatches = true,
                metadataMatches = true,
                mapMatches = true
            )
        )
    }

    @Test
    fun reusesCheckpointWhenLastModifiedIsAvailable() {
        assertTrue(
            TransferCheckpointPolicy.canReuse(
                etag = "",
                lastModified = "Wed, 26 Nov 2026 10:00:00 GMT",
                targetMatches = true,
                metadataMatches = true,
                mapMatches = true
            )
        )
    }

    @Test
    fun doesNotResumeWithoutStableServerValidator() {
        assertFalse(
            TransferCheckpointPolicy.canReuse(
                etag = "",
                lastModified = "",
                targetMatches = true,
                metadataMatches = true,
                mapMatches = true
            )
        )
    }

    @Test
    fun rejectsCheckpointWhenFileSizeOrMetadataChanged() {
        assertFalse(
            TransferCheckpointPolicy.canReuse(
                etag = "\"video-etag\"",
                lastModified = "",
                targetMatches = false,
                metadataMatches = true,
                mapMatches = true
            )
        )
        assertFalse(
            TransferCheckpointPolicy.canReuse(
                etag = "\"video-etag\"",
                lastModified = "",
                targetMatches = true,
                metadataMatches = false,
                mapMatches = true
            )
        )
    }
}
