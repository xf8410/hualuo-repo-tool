package com.hualuo.repotool.ui.state

/**
 * 代理启动恢复（对齐旧仓 ProxySettingsSynchronizer）：盘上有配置就装回
 * TransportProxy——设置页保存当场装的只是内存态，重启后配置不该躺在盘上失效。
 * 存的数据坏了装不回去：出声并直连，设置页里重新保存一次就好。
 */
internal fun restoreSavedProxy(persist: UiPersistence, toast: (String) -> Unit) {
    val savedType = persist.load(UiKeys.PROXY_TYPE)?.trim().orEmpty()
    if (savedType != "http" && savedType != "socks") return
    val savedHost = persist.load(UiKeys.PROXY_HOST)?.trim().orEmpty()
    val savedPort = persist.load(UiKeys.PROXY_PORT)?.trim().toIntOrNull()
    if (savedHost.isEmpty() || savedPort == null) return
    val savedBypass = persist.load(UiKeys.PROXY_BYPASS)
        ?.split(Regex("[,\\n]"))?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
    val ok = com.hualuo.engine.api.TransportProxy.installProxy(
        savedType, savedHost, savedPort, savedBypass,
        username = persist.load(UiKeys.PROXY_USER)?.trim().orEmpty(),
        password = persist.load(UiKeys.PROXY_PASS).orEmpty(),
    )
    if (!ok) toast("存盘的代理配置装不回去（数据可能坏了），这轮先直连；设置页里重新保存一次")
}
