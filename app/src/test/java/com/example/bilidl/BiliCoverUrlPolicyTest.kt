package com.example.bilidl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BiliCoverUrlPolicyTest {
    @Test
    fun upgradesProtocolRelativeAndHttpCoversToHttps() {
        assertEquals(
            "https://i0.hdslb.com/bfs/archive/example.jpg",
            BiliCoverUrlPolicy.normalize("//i0.hdslb.com/bfs/archive/example.jpg")
        )
        assertEquals(
            "https://i0.hdslb.com/bfs/archive/example.jpg",
            BiliCoverUrlPolicy.normalize("http://i0.hdslb.com/bfs/archive/example.jpg")
        )
        assertEquals(
            "https://i0.hdslb.com/bfs/archive/example.jpg",
            BiliCoverUrlPolicy.normalize("https://i0.hdslb.com/bfs/archive/example.jpg")
        )
    }

    @Test
    fun acceptsRootRelativeUrlsAndRejectsUnknownSchemes() {
        assertEquals(
            "https://www.bilibili.com/static/cover.jpg",
            BiliCoverUrlPolicy.normalize("/static/cover.jpg")
        )
        assertNull(BiliCoverUrlPolicy.normalize("data:image/png;base64,abc"))
        assertNull(BiliCoverUrlPolicy.normalize(""))
        assertNull(BiliCoverUrlPolicy.normalize(null))
    }
}
