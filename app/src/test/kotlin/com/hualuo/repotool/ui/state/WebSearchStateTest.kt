package com.hualuo.repotool.ui.state

import com.hualuo.engine.backup.AgoraImportPlan
import com.hualuo.engine.search.SearchProviders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网页搜索设置舱的契约（纯 JVM，不碰网、不碰安卓）。
 *
 * 盯的是三件事：
 *  - **四格各自落地**：提供商、那家的密钥、实例地址、条数，各写各的键（一家一把钥匙）；
 *  - **换一家不丢上一家的密钥**，也不把别家的钥匙显示到当前那家的格子里；
 *  - **脏值收口**：认不出的 id 回默认、密钥去空白、条数读不懂回 5 / 越界夹住——
 *    设置文件是手可编辑的，脏值不许把网页工具打死。
 */
class WebSearchStateTest {

    private class MemPersist(initial: Map<String, String> = emptyMap()) : UiPersistence {
        val map = initial.toMutableMap()
        override fun load(key: String): String? = map[key]
        override fun save(key: String, value: String) { map[key] = value }
        override fun flush(): String? = null
        override fun drainMessages(): List<String> = emptyList()
    }

    private fun stateOf(
        persist: MemPersist,
        bump: () -> Unit = {},
    ): Pair<WebSearchState, () -> Unit> {
        val bumps = arrayOf(0)
        val state = WebSearchState(
            load = { key -> persist.load(key).orEmpty() },
            save = { key, value ->
                persist.save(key, value)
                bumps[0] += 1
                bump()
            },
        )
        return state to { bumps[0] += 0 }
    }

    @Test
    fun freshInstallUsesTheFreeTier() {
        val (state, _) = stateOf(MemPersist())
        assertEquals(SearchProviders.DEFAULT_ID, state.provider.id)
        assertEquals("DuckDuckGo", state.providerLabel())
        assertFalse("默认那家不要密钥", state.needsKey)
        assertFalse("默认那家不接实例地址", state.usesBaseUrl)
        assertEquals(5, state.numResults())
        assertEquals("DuckDuckGo：每搜最多 5 条", state.statusLine())
    }

    @Test
    fun switchingProviderKeepsOtherProvidersKeys() {
        val persist = MemPersist()
        val (state, _) = stateOf(persist)
        state.setProvider("brave")
        state.setApiKey("BSA-1")
        state.setProvider("tavily")
        state.setApiKey("tvly-2")

        assertEquals("tavily", persist.map[UiKeys.WEB_SEARCH_PROVIDER])
        assertEquals("BSA-1", persist.map[UiKeys.webSearchKey("brave")])
        assertEquals("tvly-2", persist.map[UiKeys.webSearchKey("tavily")])

        state.setProvider("brave")
        assertEquals("换回来钥匙还在", "BSA-1", state.apiKey())
    }

    @Test
    fun missingKeyIsSaidOutLoudInsteadOfPretendingReady() {
        val (state, _) = stateOf(MemPersist(mapOf(UiKeys.WEB_SEARCH_PROVIDER to "serper")))
        assertTrue(state.needsKey)
        assertTrue("要显红字说清：${state.statusLine()}", state.statusLine().contains("还没填密钥"))

        state.setApiKey("  sk-1  ")
        assertEquals("sk-1", state.apiKey())
        assertTrue("填了就不该再报错缺：${state.statusLine()}", !state.statusLine().contains("还没填密钥"))
    }

    @Test
    fun searxngShowsTheInstanceFieldAndClampsIt() {
        val (state, _) = stateOf(MemPersist(mapOf(UiKeys.WEB_SEARCH_PROVIDER to "searxng")))
        assertTrue(state.usesBaseUrl)
        state.setBaseUrl("https://searx.my.example/")
        assertEquals("https://searx.my.example", state.baseUrl())
        assertEquals("https://searx.my.example", state.config().effectiveBaseUrl)
    }

    @Test
    fun numResultsIsClampedOnBothEnds() {
        val (state, _) = stateOf(MemPersist())
        state.setNumResults(99)
        assertEquals("10", state.numResults().toString())
        state.setNumResults(0)
        assertEquals(1, state.numResults())
        state.setNumResults(7)
        assertEquals(7, state.numResults())
    }

    @Test
    fun garbageInSettingsFallsBackInsteadOfBreakingSearch() {
        val persist = MemPersist(
            mapOf(
                UiKeys.WEB_SEARCH_PROVIDER to "bing",
                UiKeys.WEB_SEARCH_NUM_RESULTS to "不是数字",
            ),
        )
        val (state, _) = stateOf(persist)
        assertEquals(SearchProviders.DEFAULT_ID, state.provider.id)
        assertEquals(5, state.numResults())
        assertEquals("duckduckgo", state.config().providerId)
    }

    @Test
    fun configIsReadFreshEveryTimeSoSettingsTakeEffectImmediately() {
        val persist = MemPersist()
        val (state, _) = stateOf(persist)
        assertEquals("duckduckgo", state.config().providerId)
        persist.save(UiKeys.WEB_SEARCH_PROVIDER, "tavily")
        persist.save(UiKeys.webSearchKey("tavily"), "tvly-9")
        val cfg = state.config()
        assertEquals("tavily", cfg.providerId)
        assertEquals("tvly-9", cfg.apiKey)
    }

    @Test
    fun agoraImportWritesSearchKeysIntoTheLiveChannel() {
        val persist = MemPersist()
        val applied = applyWebSearchFromAgora(
            persist,
            AgoraImportPlan(
                recognized = true,
                webSearchProvider = "searxng",
                webSearchBaseUrl = "https://searx.my.example/",
                webSearchApiKeys = mapOf("brave" to "BSA-old", "serper" to "sk-old"),
            ),
        )
        assertEquals("四项（提供商、地址、两把钥匙）：$applied", 4, applied)
        assertEquals("searxng", persist.map[UiKeys.WEB_SEARCH_PROVIDER])
        assertEquals("https://searx.my.example", persist.map[UiKeys.WEB_SEARCH_BASE_URL])
        assertEquals("BSA-old", persist.map[UiKeys.webSearchKey("brave")])
        assertEquals("sk-old", persist.map[UiKeys.webSearchKey("serper")])
    }

    @Test
    fun agoraImportWithoutSearchFieldsWritesNothing() {
        val persist = MemPersist()
        val applied = applyWebSearchFromAgora(persist, AgoraImportPlan(recognized = true))
        assertEquals(0, applied)
        assertTrue("不许拿空串盖用户手填的值：${persist.map}", persist.map.isEmpty())
    }
}