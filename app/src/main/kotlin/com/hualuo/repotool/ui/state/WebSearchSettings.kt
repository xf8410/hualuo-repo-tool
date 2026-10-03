package com.hualuo.repotool.ui.state

import com.hualuo.engine.backup.AgoraImportPlan
import com.hualuo.engine.search.SearchConfig
import com.hualuo.engine.search.SearchProviderInfo
import com.hualuo.engine.search.SearchProviders

/**
 * 网页搜索四格（提供商 id、那家的密钥、自托管实例地址、默认条数）的**纯读法**。
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

/** 默认条数：读不懂回 5，越界夹到范围里。 */
fun searchNumResultsFrom(load: (String) -> String): Int =
    load(UiKeys.WEB_SEARCH_NUM_RESULTS).trim().toIntOrNull()
        ?.coerceIn(SearchProviders.MIN_RESULTS, SearchProviders.MAX_RESULTS)
        ?: SearchConfig.DEFAULT_NUM_RESULTS

/**
 * 工具族用的搜索配置（现读设置，不缓存）：设置页改完下一句对话就生效。
 *
 * 认不出的提供商 id 在 [searchConfigFrom] 里就回默认了，这儿不做第二份判断。
 */
fun webSearchConfig(persist: UiPersistence): SearchConfig =
    searchConfigFrom { key -> persist.load(key).orEmpty() }

/**
 * 旧 Agora 包里的网页搜索设置怎么进新版的键（数据控制 → 导入旧包那条路）。
 *
 * 摆在独立文件而不是塞进 AppUiState：那件本来就已经近千行（红线三），每多一行都在借债。
 * 本函数只做一件事：把兑换单里的网页搜索三样（提供商、各家密钥、自托管实例地址）
 * 逐键送进**活通道**（[UiPersistence.save]），返回真写进去的项数让上层报账。
 *
 * 两条纪律：
 *  - 旧包没带的（null / 空表）一律**不写**：不许拿空串盖掉用户手填的新值；
 *  - 逐键写，不整表塞一个 JSON 字符串（那是旧仓那套加密加 JSON 双层坑的起点）。
 */
fun applyWebSearchFromAgora(persist: UiPersistence, plan: AgoraImportPlan): Int {
    var applied = 0
    plan.webSearchProvider?.let { id ->
        persist.save(UiKeys.WEB_SEARCH_PROVIDER, id)
        applied += 1
    }
    plan.webSearchBaseUrl?.let { url ->
        persist.save(UiKeys.WEB_SEARCH_BASE_URL, url.trimEnd('/'))
        applied += 1
    }
    for ((providerId, key) in plan.webSearchApiKeys) {
        // 一家一把钥匙（键名由 [UiKeys.webSearchKey] 拼，不在别处手写字符串）
        persist.save(UiKeys.webSearchKey(providerId), key)
        applied += 1
    }
    return applied
}