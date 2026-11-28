package com.example.bilidl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BiliBatchStreamPolicyTest {
    private fun stream(id: Int, codec: String, bandwidth: Long = 1_000L) =
        BiliStreamChoice(id = id, codecs = codec, bandwidth = bandwidth)

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
    fun sameQualityOtherCodecPrecedesLowerQualityAnyCodec() {
        val chosen = BiliBatchStreamPolicy.selectCompatible(
            stream(80, "avc1.640028"),
            listOf(stream(80, "hev1.1.6.L120.90"), stream(64, "avc1.640028"))
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
