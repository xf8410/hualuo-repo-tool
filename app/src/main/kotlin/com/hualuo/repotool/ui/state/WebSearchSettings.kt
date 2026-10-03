package com.hualuo.repotool.ui.state

import com.hualuo.engine.search.SearchConfig
import com.hualuo.engine.search.SearchProviderInfo
import com.hualuo.engine.search.SearchProviders

/**
 * 网页搜索设置的四格读法：提供商 id、那家的密钥、SearXNG 实例地址、默认条数。
 *
 * **执行那一刻现读**（老规矩：令牌与地址都不缓存，改设置下一句就生效）。
 * 读方负责把脏值收干净：认不出的提供商 id 回默认那家（设置文件是手可编辑的），
 * 条数读不懂回 5、越界夹到 1..10，密钥去空白——引擎件拿到的永远是干净值。
 *
 * 与旧仓的差别写在这里免得以后「统一」回去：旧仓把五家的钥匙塞在一个 JSON 字符串里
 * （webSearchApiKeys）再整体加密；本仓一把一家一个键（[UiKeys.webSearchKey]），
 * 换一家不丢上一家的，备份导出也逐项看得见。
 */
fun readWebSearchConfig(persist: UiPersistence): SearchConfig {
    val rawId = persist.load(UiKeys.WEB_SEARCH_PROVIDER)
    val provider = SearchProviders.normalize(rawId)
    return SearchConfig(
        providerId = provider.id,
        apiKey = persist.load(UiKeys.webSearchKey(provider.id)).orEmpty(),
        baseUrl = persist.load(UiKeys.WEB_SEARCH_BASE_URL).orEmpty(),
        numResults = readWebSearchNumResults(persist),
    )
}

/** 默认条数：读不懂回 5，越界夹到 1..10（模型给的 num_results 之外还有这一道收口）。 */
fun readWebSearchNumResults(persist: UiPersistence): Int =
    persist.load(UiKeys.WEB_SEARCH_NUM_RESULTS)?.trim()?.toIntOrNull()
        ?.coerceIn(SearchConfig.MIN_NUM_RESULTS, SearchConfig.MAX_NUM_RESULTS)
        ?: SearchConfig.DEFAULT_NUM_RESULTS

/** 设置页要摆的那家（认过 id 的，脏值已回默认）。 */
fun webSearchProvider(persist: UiPersistence): SearchProviderInfo =
    SearchProviders.normalize(persist.load(UiKeys.WEB_SEARCH_PROVIDER))

/** 设置页要显不显密钥框：静态事实由事实表说话（[SearchProviderInfo.needsKey]）。 */
fun webSearchNeedsKey(persist: UiPersistence): Boolean = webSearchProvider(persist).needsKey

/** 设置页要显不显实例地址框：SearXNG 这类自托管引擎才要。 */
fun webSearchUsesBaseUrl(persist: UiPersistence): Boolean = webSearchProvider(persist).usesBaseUrl