package com.hualuo.repotool.ui.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 界面状态的纯 JVM 测试：子页栈、多选删除、附件增减、toast、弹层互斥。
 * 这些是最容易在「改样式」时悄悄改坏的逻辑，所以进 CI 当门禁（:app:testDebugUnitTest）。
 * 标识符全 ASCII（家规：中文不进代码），断言只碰行为不碰像素。
 */
class AppUiStateTest {

    @Test
    fun openSettingsWithoutAnchorStaysOnMainPage() {
        val s = AppUiState()
        s.openSettings(null)
        assertTrue(s.settingsOpen)
        assertTrue(s.subStack.isEmpty())
    }

    @Test
    fun openSettingsWithAnchorPushesOneSubPage() {
        val s = AppUiState()
        s.openSettings("model")
        assertEquals(listOf("model"), s.subStack)
    }

    @Test
    fun backFromSubPagePopsStackBeforeClosingSettings() {
        val s = AppUiState()
        s.openSettings("shell")
        s.subStack = s.subStack + "sandbox"
        s.backFromSettings()
        assertEquals(listOf("shell"), s.subStack)
        assertTrue(s.settingsOpen)
        s.backFromSettings()
        assertTrue(s.subStack.isEmpty())
        s.backFromSettings()
        assertFalse(s.settingsOpen)
    }

    @Test
    fun toggleSelectAddsThenRemoves() {
        val s = AppUiState()
        s.toggleSelect("c1")
        assertTrue("c1" in s.selectedIds)
        s.toggleSelect("c1")
        assertFalse("c1" in s.selectedIds)
    }

    @Test
    fun askDeleteWithoutSelectionDoesNotOpenConfirm() {
        val s = AppUiState()
        s.askDeleteSelected()
        assertFalse(s.confirmOpen)
    }

    @Test
    fun confirmActionDeletesSelectedConversationsAndClosesLayers() {
        val s = AppUiState()
        // 演示数据已撤：先铺两个真会话再测删除（不再依赖 DemoConversations 的 c1/c2）
        s.newConversation(); s.newConversation()
        val fresh = s.convs.take(2).map { it.id }
        s.selecting = true
        fresh.forEach { s.toggleSelect(it) }
        s.askDeleteSelected()
        assertTrue(s.confirmOpen)
        assertEquals("删除 ${fresh.size} 个会话？", s.confirmText)
        assertNotNull(s.confirmAction)
        s.confirmAction!!.invoke()
        assertEquals(0, s.convs.size)
        assertTrue(s.selectedIds.isEmpty())
        assertFalse(s.selecting)
        assertFalse(s.confirmOpen)
        assertFalse(s.drawerOpen)
    }

    @Test
    fun newConversationPrependsAndClearsSelection() {
        val s = AppUiState()
        val before = s.convs.size
        s.selecting = true
        s.toggleSelect("c1")
        s.newConversation()
        assertEquals(before + 1, s.convs.size)
        assertEquals("新会话 · 刚刚", s.convs.first().title)
        assertFalse(s.selecting)
        assertTrue(s.selectedIds.isEmpty())
    }

    @Test
    fun addAndRemoveThumbKeepsOrderAndCount() {
        val s = AppUiState()
        // 演示数据已撤：thumbs 空表起步，先加两个再测增删序
        s.addThumb("A")
        s.addThumb("X")
        assertEquals(listOf("A", "X"), s.thumbs)
        s.removeThumb(0)
        assertEquals(listOf("X"), s.thumbs)
    }

    @Test
    fun toastBumpsTokenEachTime() {
        val s = AppUiState()
        val t0 = s.toastToken
        s.toast("甲")
        assertTrue(s.toastVisible)
        assertEquals("甲", s.toastText)
        s.toast("乙")
        assertEquals(t0 + 2, s.toastToken)
    }

    @Test
    fun closeSheetsResetsEveryLayer() {
        val s = AppUiState()
        s.modelSheetOpen = true
        s.toolSheetOpen = true
        s.taskSheetKey = "夜间备份导出"
        s.addMenuOpen = true
        s.drawerOpen = true
        s.closeSheets()
        assertFalse(s.modelSheetOpen)
        assertFalse(s.toolSheetOpen)
        assertNull(s.taskSheetKey)
        assertFalse(s.addMenuOpen)
        assertFalse(s.drawerOpen)
    }

    @Test
    fun visionGateBlocksModelWhenAttachmentsPresent() {
        val s = AppUiState()
        // 演示数据已撤（2026-10-05 撤摆设刀）：thumbs 空表起步，无视觉模型的演示前提不复存在。
        // 这里盯住的新前提：thumbs 为空（撤干净了没残留）——有人把演示数据加回来这行就红。
        assertTrue(s.thumbs.isEmpty())
    }
}
