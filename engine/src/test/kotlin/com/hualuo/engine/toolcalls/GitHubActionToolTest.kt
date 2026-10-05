package com.hualuo.engine.toolcalls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GitHub 动作族（2026-10-05 全套刀）的闸门契约测试：**闸门纪律不是注释，是断言**。
 *
 * 三条盯死的规矩：
 *  - confirmer 不给 = 六件一件都不许注册（默认拒，不是默认放行）；
 *  - 给了 confirmer = 恰好六件，名字一个不多一个不少（多一件就是绕闸门，少一件就是漏装）；
 *  - 闸门摇头（confirm 返回 false）= 什么都没发生，离线可验证（不发网，
 *    返回「没有确认…终态：未建」的人话——这是业务终态，不是工具故障）。
 *
 * 不测真执行：真建真删要真仓库，那是 CI 与机主手机上的活；这里只盯「闸门把得住把不住」。
 */
class GitHubActionToolTest {

    @Test
    fun withoutConfirmerNothingRegisters() {
        val registry = ToolRegistry()
        GitHubActionTool.register(
            registry = registry,
            loadToken = { "tok" },
            defaultRepo = { "o/r" },
            confirmer = null,
        )
        assertTrue("不给闸门就整族不存在（默认拒的另一半）", registry.isEmpty())
    }

    @Test
    fun withConfirmerExactlySixToolsRegister() {
        val registry = ToolRegistry()
        GitHubActionTool.register(
            registry = registry,
            loadToken = { "tok" },
            defaultRepo = { "o/r" },
            confirmer = { false },
        )
        assertEquals(
            setOf(
                "github_create_branch",
                "github_delete_branch",
                "github_create_issue",
                "github_add_issue_comment",
                "github_close_pull_request",
                "github_add_pull_request_comment",
            ),
            registry.specs().map { it.name }.toSet(),
        )
    }

    @Test
    fun shakenGateDoesNothingAndSaysSo() {
        val registry = ToolRegistry()
        GitHubActionTool.register(
            registry = registry,
            loadToken = { "tok" },
            defaultRepo = { "o/r" },
            confirmer = { false },
        )
        val outcome = registry.execute("github_create_issue", """{"title":"t","body":"b"}""")
        // 闸门摇头是「业务终态」不是「工具故障」：工具没崩、动作没发生，
        // 返回人话说清「没点头所以没干」。判据是文案，不是 ok 位。
        assertTrue(
            "要把「没点头所以没干」说清楚：${outcome.text}",
            outcome.text.contains("没有确认") && outcome.text.contains("终态") && outcome.text.contains("未"),
        )
    }

    @Test
    fun badBranchNameIsRejectedBeforeGate() {
        val registry = ToolRegistry()
        GitHubActionTool.register(
            registry = registry,
            loadToken = { "tok" },
            defaultRepo = { "o/r" },
            confirmer = { true },
        )
        // 坏名字在摆卡之前就拦下（省得用户对着一张注定失败的卡点头）
        val outcome = registry.execute("github_create_branch", """{"branch":"bad..name"}""")
        assertFalse(outcome.ok)
        assertTrue("坏分支名要说明白：${outcome.text}", outcome.text.contains("分支名"))
    }
}
