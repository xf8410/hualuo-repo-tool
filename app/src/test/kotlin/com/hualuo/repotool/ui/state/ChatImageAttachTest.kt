package com.hualuo.repotool.ui.state

import com.hualuo.engine.store.SessionStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 图片附件在状态层的链路（2026-10-16 刀，纯 JVM）：
 * 待发区进 -> 发送随消息带走 -> 用户卡带图 -> 待发区清空 -> 落盘行带图。
 * worker 吞线程（busy 恒真无所谓——这里只验「发送那一刻的账」，不验生成）。
 */
class ChatImageAttachTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `pending images ride the outgoing message and persist with it`() {
        val sessions = SessionStore(File(tmp.root, "sessions"))
        val state = AppUiState(persist = UiPersistence.None, store = sessions, chatWorker = {})
        state.currentModel = "m"
        state.input = "看看这张"
        val imgs = listOf("/data/x/img_1.jpg", "/data/x/img_2.jpg")
        state.addChatImages(imgs)

        state.sendCurrentInput()

        val userCard = state.chat.messages.first { it.fromMe }
        assertEquals("用户卡必须带图", imgs, userCard.imagePaths)
        assertEquals("发出去待发区就清空", emptyList<String>(), state.chatImages)
        // 落盘行同带：重开会话图还在（读盘回来再走一遍 restore 验）
        val sid = state.chat.sessionId ?: error("发送后户头必须在")
        val stored = sessions.load(sid)!!.messages.first { it.role == "user" }
        assertEquals("落盘行带同一份图清单", imgs, stored.images)
    }

    @Test
    fun `empty pending area sends exactly like before`() {
        val sessions = SessionStore(File(tmp.root, "sessions"))
        val state = AppUiState(persist = UiPersistence.None, store = sessions, chatWorker = {})
        state.currentModel = "m"
        state.input = "纯文字"

        state.sendCurrentInput()

        val userCard = state.chat.messages.first { it.fromMe }
        assertEquals(emptyList<String>(), userCard.imagePaths)
        val sid = state.chat.sessionId!!
        assertEquals(emptyList<String>(), sessions.load(sid)!!.messages.first { it.role == "user" }.images)
    }

    @Test
    fun `remove takes exactly one image out of the pending area`() {
        val state = AppUiState(persist = UiPersistence.None, chatWorker = {})
        state.addChatImages(listOf("a", "b", "c"))

        state.removeChatImage(1)

        assertEquals(listOf("a", "c"), state.chatImages)
    }
}
