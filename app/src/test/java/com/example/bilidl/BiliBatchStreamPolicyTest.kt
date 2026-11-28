package com.example.bilidl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BiliBatchStreamPolicyTest {
    private fun stream(
        id: Int,
        codec: String,
        bandwidth: Long = 1_000L,
        codecId: Long = when {
            codec.startsWith("avc", true) -> 7L
            codec.startsWith("hev", true) || codec.startsWith("hvc", true) -> 12L
            codec.startsWith("av01", true) -> 13L
            codec.contains("mp4a", true) -> 0L
            else -> 0L
        }
    ) = BiliStreamChoice(
        id = id,
        codecs = codec,
        bandwidth = bandwidth,
        codecId = codecId
    )

    @Test
    fun exactMatchRequiresSameRepresentationIdAndCodec() {
        val chosen = BiliBatchStreamPolicy.selectExact(
            stream(80, "avc1.640028"),
            listOf(
                stream(80, "hev1.1.6.L120.90"),
                stream(64, "avc1.640028"),
                stream(80, "avc1.640028", 2_000_000)
            )
        )
        assertEquals(2_000_000L, chosen?.bandwidth)
    }

    @Test
    fun exactCodecIdTakesPrecedenceOverDifferentCodecStringProfiles() {
        val chosen = BiliBatchStreamPolicy.selectExact(
            stream(80, "avc1.640028", codecId = 7L),
            listOf(
                stream(80, "avc1.640029", codecId = 7L),
                stream(80, "hev1.1.6.L120.90", codecId = 12L)
            )
        )
        assertEquals("avc1.640029", chosen?.codecs)
    }

    @Test
    fun exactMatchReturnsNullWhenOnlyDifferentOrLowerRepresentationsExist() {
        assertNull(
            BiliBatchStreamPolicy.selectExact(
                stream(80, "avc1.640028"),
                listOf(stream(80, "hev1.1.6.L120.90"), stream(64, "avc1.640028"))
            )
        )
    }

    @Test
    fun exactQualityAndCodecFamilyWins() {
        val chosen = BiliBatchStreamPolicy.selectCompatible(
            stream(80, "avc1.640028"),
            listOf(stream(80, "hev1.1.6.L120.90", 9_000_000), stream(80, "avc1.640029", 2_000_000))
        )
        assertEquals("avc", chosen?.codecFamily())
        assertEquals(80, chosen?.id)
    }

    @Test
    fun sameCodecFamilyNearestLowerQualityWinsBeforeOtherCodec() {
        val chosen = BiliBatchStreamPolicy.selectCompatible(
            stream(80, "avc1.640028"),
            listOf(
                stream(80, "hev1.1.6.L120.90", 8_000_000),
                stream(64, "avc1.640029", 2_000_000),
                stream(32, "avc1.640028", 1_000_000)
            )
        )
        assertEquals(64, chosen?.id)
        assertEquals("avc", chosen?.codecFamily())
    }

    @Test
    fun sameCodecFamilyLowerQualityPrecedesSameQualityOtherCodec() {
        val chosen = BiliBatchStreamPolicy.selectCompatible(
            stream(80, "avc1.640028"),
            listOf(stream(80, "hev1.1.6.L120.90"), stream(64, "avc1.640028"))
        )
        assertEquals(64, chosen?.id)
        assertEquals("avc", chosen?.codecFamily())
    }

    @Test
    fun sameQualityOtherCodecIsUsedWhenNoSameFamilyLowerTrackExists() {
        val chosen = BiliBatchStreamPolicy.selectCompatible(
            stream(80, "avc1.640028"),
            listOf(stream(80, "hev1.1.6.L120.90"), stream(32, "av01.0.08M.08"))
        )
        assertEquals(80, chosen?.id)
        assertEquals("hevc", chosen?.codecFamily())
    }

    @Test
    fun neverSelectsHigherQualityThanPreferred() {
        assertNull(
            BiliBatchStreamPolicy.selectCompatible(
                stream(64, "avc1.64001f"),
                listOf(stream(80, "avc1.640028"))
            )
        )
    }
}
