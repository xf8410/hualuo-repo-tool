package com.hualuo.repotool.ui.state

import com.hualuo.engine.search.SearchConfig
import com.hualuo.engine.search.SearchProviderInfo
import com.hualuo.engine.search.SearchProviders

/**
 * 网页搜索四格（提供商 id、那家的密钥、实例地址、默认条数）的**纯读法**。
 *
 * 只吃一个 `load` 函数，不认识安卓也不认识文件：界面舱（[WebSearchState]）与纯 JVM 测试
 * 共用这一份收口逻辑，两边不会各写一套。
 *
 * 收口规矩（认不出的 id 回默认、密钥去空白、条数夹范围）都在这里，别散到界面里。
 * 与旧仓的差别写在这儿免得以后「统一」回去：旧仓把五家的钥匙塞在一个 JSON 字符串里
 * （webSearchApiKeys）再整体加密；本仓一把一家一个键（[UiKeys.webSearchKey]），
 * 换一家不丢上一家的，导出备份也逐项看得见。
 */

/** 读一份搜索配置（脏 id 已回默认、密钥已去空白、条数已夹范围）。 */
fun searchConfigFrom(load: (String) -> String): SearchConfig {
    val provider = searchProviderFrom(load)
    return SearchConfig(
        providerId = provider.id,
        apiKey = load(UiKeys.webSearchKey(provider.id)).trim(),
        baseUrl = load(UiKeys.WEB_SEARCH_BASE_URL).trim().trimEnd('/'),
        numResults = searchNumResultsFrom(load),
    )
}

/** 认过 id 的那家（认不出回默认，不抛）。 */
fun searchProviderFrom(load: (String) -> String): SearchProviderInfo =
    SearchProviders.normalize(load(UiKeys.WEB_SEARCH_PROVIDER))

/** 默认条数：读不懂回 5，越界夹到 1..10。 */
fun searchNumResultsFrom(load: (String) -> String): Int =
    load(UiKeys.WEB_SEARCH_NUM_RESULTS).trim().toIntOrNull()
        ?.coerceIn(SearchConfig.MIN_NUM_RESULTS, SearchConfig.MAX_NUM_RESULTS)
        ?: SearchConfig.DEFAULT_NUM_RESULTS