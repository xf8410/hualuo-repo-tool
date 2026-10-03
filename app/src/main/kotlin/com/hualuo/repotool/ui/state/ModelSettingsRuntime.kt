package com.hualuo.repotool.ui.state

/**
 * 进程级模型设置句柄。Application 创建一次，设置页与聊天发送共用。
 *
 * 【2026-10-03 修】挂不上的时候留一句原因给人看：以前只有 `current()` 返回 null，
 * 设置里的「提供商」「模型」两页各自 `?: return`，整页白茫茫一片（机主实报：
 * 「选择 API、已经选择模型，里面也是空的」），屏上一个字都不说 ——
 * 空白是最难查的形态。原因存在这儿，界面好照实摆出来。
 */
object ModelSettingsRuntime {
    @Volatile private var installed: ModelSettingsState? = null
    @Volatile private var failure: String? = null

    fun install(state: ModelSettingsState) {
        installed = state
        failure = null
    }

    /** 预热/构建失败时记一笔原因（不抛给界面，界面去 failureReason 取）。 */
    fun recordFailure(reason: String) {
        failure = reason
    }

    fun current(): ModelSettingsState? = installed

    /** 已挂上就返回 null（没毛病）；没挂上就给一句人话，可能还没有原因（那就说没初始化）。 */
    fun failureReason(): String? = if (installed != null) null else (failure ?: "还没初始化")
}