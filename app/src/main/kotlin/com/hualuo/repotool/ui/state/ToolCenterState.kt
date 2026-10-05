package com.hualuo.repotool.ui.state

import com.hualuo.engine.toolcalls.ToolLedgerRow
import com.hualuo.engine.toolcalls.ToolRegistry

/**
 * 工具中心面板（2026-10-05 修摆设刀⑤新增）：把这套应用**真实注册**的工具一件不落列出来。
 *
 * 为什么不是把 GitHub 一族拆出来单独数：工具表是整表注册的，拆族数就得维护一张
 * 「族名 → 该族有几件」的对照表，那张表迟早和真注册表漂移——漂了又是「界面上写着能调、
 * 实际没有」的老病。这里直接问注册表要**整本账**（[ToolRegistry.ledger]），报告说几件就是几件。
 *
 * 为什么必须带 [visibleIf]：网页搜索、图像生成、观察桥那几族是按设置开关现问的，
 * 关掉时模型看不见它们。面板要照实说「注册了但此刻关着」，而不是把它们从名单里抹掉
 * 让人以为不存在（抹掉与「没做」在屏上长得一模一样）。
 *
 * 与 [ToolWiring] 的分工：那边只管装配（注入什么就注册什么），这边只管报数，两边都不
 * 自己造一份清单。任何新族接进来，面板自动多出那几行——这是本件存在的全部意义。
 */
object ToolCenterState {

    /**
     * 整本账。[registry] 为 null（纯 JVM 测试、或装配还没跑）时给空表——
     * 面板据此说「还没装配任何工具」，不摆演示清单顶数。
     */
    fun rows(registry: ToolRegistry?): List<ToolLedgerRow> = registry?.ledger().orEmpty()

    /** 总件数（注册了几件）。 */
    fun total(registry: ToolRegistry?): Int = rows(registry).size

    /** 此刻开着几件（无开关的恒算开着；开关读出错的不算，并另有 gateNote 出声）。 */
    fun visibleCount(registry: ToolRegistry?): Int = rows(registry).count { it.visible }

    /** 带运行时开关的件数：面板据此说明「其中几件受设置开关控制」。 */
    fun gatedCount(registry: ToolRegistry?): Int = rows(registry).count { it.gated }

    /**
     * 一句话总账，给卡片标题用：一件都没装、全部开着、还是关着几件。
     *
     * 说真话，不说「一切正常」：关着的件必须是屏上看得见的数字，否则用户以为
     * 「它坏了」而实际只是自己把开关关了——这两种误会都要靠这句账拆开。
     */
    fun summary(registry: ToolRegistry?): String {
        val all = rows(registry)
        if (all.isEmpty()) return "还没装配任何工具"
        val on = all.count { it.visible }
        val gated = all.count { it.gated }
        val gatedOff = all.count { it.gated && !it.visible }
        return buildString {
            append("真实注册 ${all.size} 件，此刻模型能调 $on 件")
            if (gated > 0) append("；其中 $gated 件受设置开关控制，关着 $gatedOff 件")
        }
    }

    /** 开关读取出岔子的那几行（正常情况空表）：面板照实出声，不拿灰点糊过去。 */
    fun gateIssues(registry: ToolRegistry?): List<ToolLedgerRow> =
        rows(registry).filter { it.gateNote != null }

    /**
     * 按说明文字首行归族，让一屏二十几行读得下去。
     *
     * 归族只影响**显示分组**，不改变报数（件数一律取 [total]）。分组键取说明里的族名
     * （各族的 description 都以族名开头）；取不出族名的单独归「未标注」，不硬塞进某一族。
     */
    fun grouped(registry: ToolRegistry?): List<Pair<String, List<ToolLedgerRow>>> =
        rows(registry)
            .groupBy { familyOf(it) }
            .toList()
            .sortedBy { (family, _) -> if (family == "未标注") "zzz" else family }
}