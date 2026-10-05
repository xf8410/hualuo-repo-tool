package com.hualuo.repotool.ui.state

import com.hualuo.engine.toolcalls.ToolRegistry

/**
 * 进程级工具注册表句柄（2026-10-05 修摆设刀⑤新增）。
 *
 * 为什么要有这个进程级句柄，而不把注册表塞进 [AppUiState] 的公开字段：
 * 注册表在 [AppUiState] 构造时一次性装配（注册只发生在启动期，之后整表不改），真正要读它的
 * 只有工具中心面板一处。挂成进程级单例有两个好处：装配点（[buildGithubToolRegistry] 的调用方）
 * 与显示点（工具页）不必互相持有引用，工具页也不必知道注册表是谁在装配。
 *
 * 装配没跑到时 [current] 返回 null——面板据此说「还没装配任何工具」，
 * 不摆一份演示清单顶数（「装作在用」比空着更骗人）。
 *
 * 与 [ModelSettingsRuntime] 同一套形状：进程级单例 + 挂不上时留一句原因给人看。
 */
object ToolRegistryRuntime {
    @Volatile private var installed: ToolRegistry? = null
    @Volatile private var failure: String? = null

    /** 装配完成时登记（覆盖旧表：装配在启动期只跑一次，重建走这里）。 */
    fun install(registry: ToolRegistry) {
        installed = registry
        failure = null
    }

    /** 装配失败时记一笔原因（不抛给界面，界面去 [failureReason] 取）。 */
    fun recordFailure(reason: String) {
        failure = reason
    }

    /** 当前那张表；没装配过就是 null（面板据此说真话，不补演示数据）。 */
    fun current(): ToolRegistry? = installed

    /** 已挂上返回 null；没挂上给一句人话（可能是装配崩了，也可能还没跑）。 */
    fun failureReason(): String? = if (installed != null) null else (failure ?: "还没装配")
}