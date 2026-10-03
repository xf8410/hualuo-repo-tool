package com.hualuo.repotool.ui.state

import com.hualuo.engine.backup.AgoraImportPlan
import com.hualuo.engine.search.SearchConfig
import com.hualuo.engine.search.SearchProviders

/**
 * 旧 Agora 包里的网页搜索设置怎么进新版的键（数据控制 → 导入旧包那条路）。
 *
 * 摆在独立文件而不是塞进 AppUiState：那件本来就已经近千行（红线三），每多一行都在借债。
 * 本文件只做一件事：把兑换单里的网页搜索三样（提供商、各家密钥、自托管实例地址）
 * 逐键送进**活通道**（[UiPersistence.save]），返回真写进去的项数让上层报账。
 *
 * 两条纪律：
 *  - 旧包没带的（null / 空表）一律**不写**：不许拿空串盖掉用户手填的新值；
 *  - 逐键写，不整表塞一个 JSON 字符串（那是旧仓那套加密+JSON 双层坑的起点）。
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

/**
 * 工具族用的搜索配置（现读设置，不缓存）：设置页改完下一句对话就生效。
 *
 * 认不出的提供商 id 在 [searchConfigFrom] 里就回默认了，这儿不做第二份判断。
 */
fun webSearchConfig(persist: UiPersistence): SearchConfig =
    searchConfigFrom { key -> persist.load(key).orEmpty() }

/** 界面上那一句「现在会用谁」（认过 id 的名字；缺密钥当场说清，不让人白搜一次）。 */
fun webSearchStatusLine(persist: UiPersistence): String {
    val info = SearchProviders.normalize(persist.load(UiKeys.WEB_SEARCH_PROVIDER))
    val key = persist.load(UiKeys.webSearchKey(info.id)).orEmpty().trim()
    val count = searchNumResultsFrom { k -> persist.load(k).orEmpty() }
    return if (info.needsKey && key.isEmpty()) {
        "${info.name}：还没填密钥（去下面那格填上）"
    } else {
        "${info.name}：每搜最多 $count 条"
    }
}