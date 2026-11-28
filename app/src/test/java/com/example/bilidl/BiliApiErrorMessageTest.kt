package com.example.bilidl

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BiliApiErrorMessageTest {
    @Test
    fun riskControlErrorIsNotMisreportedAsLoginFailure() {
        val message = formatBiliApiError(-352, "风控校验失败", "评论举报失败")

        assertTrue(message.contains("风控校验失败（-352）"))
        assertTrue(message.contains("不等同于未登录"))
        assertFalse(message.contains("账号未登录（-101）"))
    }

    @Test
    fun unauthenticatedErrorSuggestsLogin() {
        val message = formatBiliApiError(-101, "账号未登录", "评论点赞失败")

        assertTrue(message.contains("账号未登录（-101）"))
        assertTrue(message.contains("点击账号区域登录"))
    }

    @Test
    fun csrfErrorExplainsThatAuthorizationMustBeRenewed() {
        val message = formatBiliApiError(-111, "csrf 校验失败", "举报失败")

        assertTrue(message.contains("CSRF 校验失败（-111）"))
        assertTrue(message.contains("重新扫码授权"))
    }

    @Test
    fun alreadyReportedCommentIsExplainedClearly() {
        val message = formatBiliApiError(12008, "已经举报过了", "评论举报失败")

        assertTrue(message.contains("已经举报过这条评论（12008）"))
    }

    @Test
    fun reportRateLimitIsExplainedClearly() {
        val message = formatBiliApiError(12019, "举报过于频繁", "评论举报失败")

        assertTrue(message.contains("举报过于频繁（12019）"))
        assertTrue(message.contains("稍后再试"))
    }

    @Test
    fun unknownErrorsKeepOriginalCodeAndMessage() {
        val message = formatBiliApiError(12019, "举报过于频繁", "举报失败")

        assertTrue(message.contains("举报失败：举报过于频繁（12019）"))
    }
}
