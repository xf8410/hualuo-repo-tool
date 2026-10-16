package com.hualuo.repotool.ui.state

import com.hualuo.repotool.ui.model.Conv
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 会话切换三口 + 对话搜索的实现件（999 行红线拆件，2026-10-16 排雷刀）。
 *
 * 为什么拆出来：AppUiState 加装 busy 闸后冲到 1005 行撞红线三，同 ProxyCardField
 * 前例拆独立文件。名字带 Impl 的原因：扩展函数跨包调用要逐个 import
 * （Drawer/SettingsOverlay/TaskWorker 都在别的包，run #738 编译段抓过），
 * 所以 AppUiState 里留一行壳成员转调这里——调用点零改动，实现住这边。
 *
 * 本文件的规矩（与 AppUiState 类 KDoc 会话库条目同源）：
 * 生成中（chat.busy）新建/切换/删当前会话一律拦下出声——流式追加与收场落盘
 * 都认 ChatRuntime 的「当前户头」，半途换户就把 A 的后半截写进 B（串台）。
 * 批删里不含当前会话的照常放行（别的会话文件与在跑的流无关）。
 */

/** 会话列表的时间戳写法（MM-dd HH:mm）；AppUiState 启动接线也用它。 */
internal fun fmtConvMeta(ms: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(ms))

/** 抽屉「新建会话」：生成中拒开（串台防护）；真库建文件，没接库走老演示路径。 */
internal fun AppUiState.newConversationImpl() {
    // 生成中不许换户：流式追加与收场落盘都认「当前户头」，半途新开就把这条的后半截写进新会话
    if (chat.busy) {
        toast("正在生成：先按停止再开新会话，别让两条话串台")
        return
    }
    val s = store
    if (s == null) {
        // 没接库（纯 JVM 测试/会话库没建成）：老演示路径，一字不动
        val c = Conv("c" + (convs.size + 1) + "-" + System.currentTimeMillis(), "新会话 · 刚刚", "刚刚")
        convs = listOf(c) + convs
        selecting = false
        selectedIds = emptySet()
        closeSheets()
        toast("已新建会话（演示数据）")
        return
    }
    val id = runCatching { s.create(currentModel) }.getOrNull()
    if (id == null) {
        toast("新会话没建成（盘上出事了）：还接着当前会话聊，字不会丢")
        closeSheets()
        return
    }
    chat.startFreshSession(id)
    // 标题允许空——还没说话就是没标题，屏上标「（未命名）」是明示不是编造
    convs = listOf(Conv(id, "（未命名）", fmtConvMeta(System.currentTimeMillis()))) + convs
    selecting = false
    selectedIds = emptySet()
    closeSheets()
    toast("已新建会话")
}

/** 抽屉点某条会话：同步读那份 JSONL 摆上屏；读不出就出声，绝不摆空壳。生成中拒切。 */
internal fun AppUiState.openConversationImpl(id: String) {
    if (store == null) return
    if (id == chat.sessionId) {
        closeSheets()
        return
    }
    // 生成中不许切：worker 还握着旧户头的卡，半途换户后半截话就落进这份会话
    if (chat.busy) {
        toast("正在生成：先按停止再切会话，别让两条话串台")
        return
    }
    val note = chat.restoreFromStore(id)
    closeSheets()
    when {
        note == null -> toast("这个会话读不到了：文件可能已被删")
        note.badLines > 0 -> toast("已切到该会话（${note.count} 条）；另有 ${note.badLines} 行读不出，已跳过")
        note.headMissing -> toast("已切到该会话（${note.count} 条）；这份会话头损坏，标题时间失真")
        else -> toast("已切到该会话（${note.count} 条）")
    }
}

/** 批删确认：含当前会话且正在生成 = 整批拦下（删掉户头，在跑这条的后半截无家可落）。 */
internal fun AppUiState.askDeleteSelectedImpl() {
    val n = selectedIds.size
    if (n == 0) return
    if (chat.busy && selectedIds.contains(chat.sessionId)) {
        toast("正在生成：这批里有当前会话，先按停止再删")
        return
    }
    confirmText = "删除 $n 个会话？"
    confirmAction = {
        val ids = selectedIds
        // 真库：删会话就是删文件（SessionStore 的家规，不留墓碑）
        store?.let { s -> ids.forEach { id -> runCatching { s.delete(id) } } }
        if (ids.contains(chat.sessionId)) {
            // 删的是当前正开的会话：屏上消息清掉、户头摘掉，下条消息自动开新户
            chat.startFreshSession(null)
        }
        convs = convs.filter { it.id !in ids }
        selectedIds = emptySet()
        selecting = false
        confirmOpen = false
        drawerOpen = false
    }
    confirmOpen = true
}

/** 对话搜索（chatsearch 页实装刀）：关键词逐会话逐行真搜，返回（标题, id, 命中行）。
 *  后台线程调用（IO）；一次最多回 50 条，界面端再截 20。 */
internal fun AppUiState.searchConversationsImpl(query: String): List<Triple<String, String, String>> {
    val s = store ?: return emptyList()
    val q = query.trim()
    if (q.isEmpty()) return emptyList()
    val out = ArrayList<Triple<String, String, String>>()
    for ((id, head) in s.list().heads) {
        if (out.size >= 50) break
        val loaded = s.load(id) ?: continue
        for (m in loaded.messages) {
            if (m.text.contains(q, ignoreCase = true)) {
                out.add(Triple(head.title.ifBlank { "（未命名）" }, id, m.text))
                break  // 一个会话只报第一处命中
            }
        }
    }
    return out
}
