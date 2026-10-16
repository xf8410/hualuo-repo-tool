package com.hualuo.repotool.ui.state

import com.hualuo.engine.store.SessionStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 生成中切会话的闸门测试（2026-10-16 排雷刀）。
 *
 * 不拦的后果：流式追加（appendStreaming）与收场落盘（persistSettleLocked）都认
 * ChatRuntime 的「当前户头」——生成途中换户/新开/删当前会话，A 会话的后半截就写进
 * B 会话的屏上与盘上，历史账目直接烂掉。三道口（openConversation / newConversation /
 * askDeleteSelected 含当前会话）必须在 busy 时拦下并出声。
 *
 * 测试 seam：chatWorker 传吞线程的 lambda（不 start），send 之后 busy 恒真，
 * 全程确定性、不睡不等真线程。
 */
class SessionSwitchBusyGuardTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 造一个「正在生成」的状态：真会话库 + 吞掉的 worker（busy 恒真）。 */
    private fun busyState(): Pair<AppUiState, SessionStore> {
        val sessions = SessionStore(File(tmp.root, "sessions"))
        val state = AppUiState(persist = UiPersistence.None, store = sessions, chatWorker = {})
        state.chat.send("你好", "m")
        assertTrue("线程被吞掉，busy 必须恒真", state.chat.busy)
        return state to sessions
    }

    @Test
    fun `busy openConversation refused and session stays`() {
        val (state, sessions) = busyState()
        val other = sessions.create("m")
        val before = state.chat.sessionId

        state.openConversation(other)

        assertEquals("户头不许动", before, state.chat.sessionId)
        assertTrue("必须出声指路：${state.toastText}", state.toastText.contains("停止"))
    }

    @Test
    fun `busy newConversation refused and list unchanged`() {
        val (state, _) = busyState()
        val before = state.convs.size

        state.newConversation()

        assertEquals("会话列表不许多", before, state.convs.size)
        assertTrue("必须出声指路：${state.toastText}", state.toastText.contains("停止"))
    }

    @Test
    fun `busy batch delete containing current session is refused entirely`() {
        val (state, _) = busyState()
        val current = state.chat.sessionId ?: error("发送后户头必须在")
        state.toggleSelect(current)

        state.askDeleteSelected()

        assertFalse("确认弹层不许摆出来", state.confirmOpen)
        assertTrue("必须出声指路：${state.toastText}", state.toastText.contains("停止"))
        assertTrue("文件必须还在", SessionStore(File(tmp.root, "sessions")).exists(current))
    }

    @Test
    fun `busy batch delete without current session still proceeds`() {
        val (state, sessions) = busyState()
        val other = sessions.create("m")
        state.toggleSelect(other)

        state.askDeleteSelected()

        assertTrue("不碰当前会话的删除不受闸", state.confirmOpen)
    }

    @Test
    fun `idle openConversation still works`() {
        val sessions = SessionStore(File(tmp.root, "sessions"))
        val state = AppUiState(persist = UiPersistence.None, store = sessions, chatWorker = {})
        val other = sessions.create("m")

        state.openConversation(other)

        assertEquals("闲时照常切", other, state.chat.sessionId)
    }
}
