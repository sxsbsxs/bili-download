package com.example.bilidl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.IOException

class BiliWbiKeyPolicyTest {
    private val imgUrl =
        "https://i0.hdslb.com/bfs/wbi/0123456789abcdef0123456789abcdef.png"
    private val subUrl =
        "https://i0.hdslb.com/bfs/wbi/fedcba9876543210fedcba9876543210.png"

    @Test
    fun anonymousNavMayReturnMinus101AndStillProvidePublicWbiKeys() {
        val anonymousKey = BiliWbiKeyPolicy.createMixinKey(-101, imgUrl, subUrl)
        val normalKey = BiliWbiKeyPolicy.createMixinKey(0, imgUrl, subUrl)

        assertEquals(32, anonymousKey.length)
        assertEquals(normalKey, anonymousKey)
    }

    @Test
    fun differentImageAndSubKeysProduceDifferentMixinKey() {
        val otherKey = BiliWbiKeyPolicy.createMixinKey(
            -101,
            "https://i0.hdslb.com/bfs/wbi/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.png",
            "https://i0.hdslb.com/bfs/wbi/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb.png"
        )
        val expectedKey = BiliWbiKeyPolicy.createMixinKey(0, imgUrl, subUrl)
        assertNotEquals(expectedKey, otherKey)
    }

    @Test(expected = IOException::class)
    fun rejectsUnexpectedNavApiErrorEvenWhenKeysArePresent() {
        BiliWbiKeyPolicy.createMixinKey(-403, imgUrl, subUrl)
    }

    @Test(expected = IOException::class)
    fun rejectsAnonymousNavWithoutWbiImageData() {
        BiliWbiKeyPolicy.createMixinKey(-101, null, null)
    }

    @Test(expected = IOException::class)
    fun rejectsMalformedWbiKeyLength() {
        BiliWbiKeyPolicy.createMixinKey(
            0,
            "https://i0.hdslb.com/bfs/wbi/short.png",
            subUrl
        )
    }
}
