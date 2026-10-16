package com.hualuo.engine.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 会话落盘的图片路径集（2026-10-16 图片附件刀）：
 *  - 带图消息写得出读得回（imgs 字段，单元分隔符连写）；
 *  - 空表不落字段（老形状逐字节不变）；
 *  - 老文件没有 imgs 读回空表（逐位兼容）；
 *  - 路径里带中文/空格/竖线都原样回来（分隔符是 U+001F，正常路径不含它）。
 */
class SessionStoreImageTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `images round trip through append and load`() {
        val store = SessionStore(tmp.root)
        val id = store.create("m")
        val paths = listOf("/data/x/img_1.jpg", "/data/x/图 2.png")

        assertTrue(store.append(id, StoredMsg(StoredMsg.ROLE_USER, "看图", 1L, images = paths)))
        val loaded = store.load(id)!!

        assertEquals(1, loaded.messages.size)
        assertEquals(paths, loaded.messages[0].images)
        assertEquals(0, loaded.badLines)
    }

    @Test
    fun `empty images writes no imgs field`() {
        val store = SessionStore(tmp.root)
        val id = store.create("m")

        store.append(id, StoredMsg(StoredMsg.ROLE_USER, "纯文字", 1L))
        val line = store.pathOf(id).readLines().last()

        assertTrue("空表不落字段（老形状逐字节不变）", !line.contains("imgs"))
        assertEquals(emptyList<String>(), store.load(id)!!.messages[0].images)
    }

    @Test
    fun `old line without imgs reads as empty list`() {
        val store = SessionStore(tmp.root)
        val id = store.create("m")
        // 手写一条老形状的行（没有 imgs 键）
        store.pathOf(id).appendText("{\"k\":\"m\",\"role\":\"user\",\"text\":\"老话\",\"at\":1}\n")

        val loaded = store.load(id)!!

        assertEquals(emptyList<String>(), loaded.messages[0].images)
        assertEquals(0, loaded.badLines)
    }
}
