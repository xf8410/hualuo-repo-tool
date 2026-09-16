package com.hualuo.repotool.ui.state

import com.hualuo.engine.backup.AgoraImportPlan
import com.hualuo.engine.backup.AgoraSessionPlan
import com.hualuo.engine.store.SessionHead
import com.hualuo.engine.store.StoredMsg
import com.hualuo.repotool.backup.BackupGateway
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 旧 Agora 兑换单应用进活通道的契约（纯 JVM，UiPersistence.None 时 save 是空操作，
 * 这里钉的是「报出来的账」与「键进没进」——persist 是 None，计数逻辑照样走）。
 */
class AgoraImportApplyTest {

    private fun outcome(
        recognized: Boolean = true,
        appliedPlan: AgoraImportPlan? = plan(),
        imported: Int = 2,
        skipped: Int = 1,
        error: String? = null,
    ) = BackupGateway.AgoraImportOutcome(recognized, appliedPlan, imported, skipped, error)

    private fun plan(
        providerName: String? = "deepseek",
        baseUrl: String? = "https://api.deepseek.com/v1",
        apiKey: String? = "sk-live-9",
        selectedModel: String? = "deepseek-chat",
        systemPrompt: String? = "你是账房先生。",
        notes: List<String> = listOf("定时任务 1 条、循环 0 条：新版还没有这功能，没导（已在路线图）"),
    ) = AgoraImportPlan(
        recognized = true,
        formatVersion = 3,
        providerName = providerName,
        baseUrl = baseUrl,
        apiKey = apiKey,
        selectedModel = selectedModel,
        thinkingOn = true,
        thinkingLevel = 3,
        codeExecOn = false,
        webSearchOn = true,
        shellOn = false,
        systemPrompt = systemPrompt,
        sessions = listOf(
            AgoraSessionPlan(
                "agora-x",
                SessionHead("挖矿记录", "deepseek-chat", 1700000000000),
                listOf(StoredMsg(StoredMsg.ROLE_USER, "开矿", 1700000001000)),
            ),
        ),
        notes = notes,
    )

    @Test
    fun recognizedPlanReportsCountsAndNotes() {
        val state = AppUiState()
        val message = state.applyAgoraImport(outcome())
        assertTrue(message.contains("旧 Agora 备份导入完成"), message)
        assertTrue(message.contains("会话 2 份"), message)
        assertTrue(message.contains("重名会话跳过 1 份"), message)
        assertTrue(message.contains("定时任务 1 条"), message)
    }

    @Test
    fun unrecognizedPlanImportsNothingAndSaysWhy() {
        val state = AppUiState()
        val message = state.applyAgoraImport(outcome(recognized = false, appliedPlan = null, error = "不是旧 Agora 的备份包"))
        assertEquals("不是旧 Agora 的备份包", message)
    }

    @Test
    fun emptyFieldsAreCountedAsNotAppliedRatherThanOverwriting() {
        val state = AppUiState()
        // 没兑出密钥/base URL：不写空串盖旧值（旧值是用户手填的，兑换不许清它）
        val message = state.applyAgoraImport(
            outcome(appliedPlan = plan(baseUrl = null, apiKey = null, systemPrompt = null, notes = emptyList())),
        )
        assertTrue(message.contains("设置 7 项"), message)
        assertTrue(!message.contains("；；"), message)
    }
}
