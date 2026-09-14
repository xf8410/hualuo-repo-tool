package com.hualuo.engine.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BaseUrlResolver] 的账：搬自原版 Agora，重点钉住**搬时修掉的那个 bug**，
 * 顺手把原版已经对的规矩也钉上（免得以后有人"顺手重构"又把它改回去）。
 */
class BaseUrlResolverTest {

    @Test
    fun appendsV1WhenUrlHasNoVersion() {
        assertEquals("https://api.openai.com/v1", BaseUrlResolver.withV1("https://api.openai.com"))
        assertEquals("https://api.openai.com/v1", BaseUrlResolver.withV1("https://api.openai.com/"))
        assertEquals("https://api.b.ai/v1", BaseUrlResolver.withV1("https://api.b.ai"))
    }

    @Test
    fun keepsUrlThatAlreadyCarriesAVersion() {
        // 这几条是原版就对的规矩，照搬钉住
        val already = listOf(
            "https://api.openai.com/v1",
            "https://open.bigmodel.cn/api/coding/paas/v4",
            "https://generativelanguage.googleapis.com/v1beta",
            "https://host/compatible-mode/v1",
            "http://localhost:11434/v1",
        )
        for (url in already) {
            assertTrue("该认出已带版本段：$url", BaseUrlResolver.hasVersionSegment(url))
            assertEquals("不该再补一段 /v1：$url", url.trimEnd('/'), BaseUrlResolver.withV1(url))
        }
    }

    @Test
    fun hostnameStartingWithVersionLikeTextIsNotTreatedAsVersioned() {
        // 这就是搬时修的 bug：原版在整个 URL 上找 /v数字，协议分隔符的第二个斜杠会命中，
        // 于是 v1.internal 这种主机名被当成"已带版本"，不补 /v1，请求少一段路径打 404。
        val hostedV1 = "https://v1.internal.example.com/chat"
        assertFalse("主机名里的 v1 不算版本段", BaseUrlResolver.hasVersionSegment(hostedV1))
        assertEquals(
            "这种域名必须补 /v1",
            "https://v1.internal.example.com/chat/v1",
            BaseUrlResolver.withV1(hostedV1),
        )
        val bareV2Host = "https://v2.gateway.io"
        assertFalse("光有 v2 主机名、路径为空也不算", BaseUrlResolver.hasVersionSegment(bareV2Host))
        assertEquals("https://v2.gateway.io/v1", BaseUrlResolver.withV1(bareV2Host))
    }

    @Test
    fun versionLikeWordsInsidePathAreNotVersions() {
        // /video、/vlog2 不是版本段；必须是 v + 数字开头的一整段
        assertFalse(BaseUrlResolver.hasVersionSegment("https://api.x.com/video"))
        assertFalse(BaseUrlResolver.hasVersionSegment("https://api.x.com/vlog2/edit"))
        assertFalse(BaseUrlResolver.hasVersionSegment("https://api.x.com/api/nova1"))
        assertTrue(BaseUrlResolver.hasVersionSegment("https://api.x.com/v1beta"))
        assertTrue(BaseUrlResolver.hasVersionSegment("https://api.x.com/v1/chat"))
    }

    @Test
    fun stripsOnlyTrailingVersion() {
        assertEquals(
            "https://api.openai.com",
            BaseUrlResolver.withoutTrailingVersion("https://api.openai.com/v1"),
        )
        assertEquals(
            "https://api.x.com/compatible-mode",
            BaseUrlResolver.withoutTrailingVersion("https://api.x.com/compatible-mode/v1"),
        )
        assertEquals(
            "https://api.x.com/v1",
            BaseUrlResolver.withoutTrailingVersion("https://api.x.com/v1/proxy"),
            "中间那段版本不许动：老同步逻辑只会往末尾补",
        )
        assertNull("没有版本段就返回 null，不许造一个空串出来", BaseUrlResolver.withoutTrailingVersion("https://api.x.com"))
        assertNull("只剩协议不算有效地址", BaseUrlResolver.withoutTrailingVersion("https://api.x.com/"))
        assertNull("空串给 null", BaseUrlResolver.withoutTrailingVersion(""))
    }

    @Test
    fun blankInputStaysBlankInsteadOfBecomingV1() {
        // 原版这里给空串，照搬：填了一半就保存也不能凭空发出一个 "/v1" 请求
        assertEquals("", BaseUrlResolver.withV1(""))
        assertEquals("", BaseUrlResolver.withV1("   "))
        assertEquals("", BaseUrlResolver.withV1("/"))
    }

    @Test
    fun endpointJoinsWithExactlyOneSlash() {
        assertEquals("https://a.b/v1/models", BaseUrlResolver.endpoint("https://a.b/v1/", "/models"))
        assertEquals("https://a.b/v1/models", BaseUrlResolver.endpoint("https://a.b/v1", "models"))
        assertEquals("https://a.b", BaseUrlResolver.endpoint("https://a.b", ""))
        assertEquals("models", BaseUrlResolver.endpoint("", "models"))
    }
}
