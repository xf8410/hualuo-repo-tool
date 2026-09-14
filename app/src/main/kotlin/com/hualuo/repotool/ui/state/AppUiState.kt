package com.hualuo.repotool.ui.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hualuo.repotool.ui.model.NavTab

/**
 * 全局界面状态（v13.1 的 JS 变量一对一翻译）。
 * 目前只有「演示态」：所有值可交互但无后端；M2 接线时把这些字段逐个换成仓库/接口来源。
 */
class AppUiState {
    // 底栏五页
    var tab by mutableStateOf(NavTab.Chat)

    // 抽屉
    var drawerOpen by mutableStateOf(false)
    var convQuery by mutableStateOf("")
    var selecting by mutableStateOf(false)
    var selectedIds by mutableStateOf(setOf<String>())
    var confirmOpen by mutableStateOf(false)
    var confirmText by mutableStateOf("")

    // 输入区
    var input by mutableStateOf("")
    var busy by mutableStateOf(false)
    var micOn by mutableStateOf(false)
    var addMenuOpen by mutableStateOf(false)
    var loopBarOn by mutableStateOf(true)
    var queueBarOn by mutableStateOf(true)
    var attachCount by mutableStateOf(3)
    var currentModel by mutableStateOf("qwen3.8-flash")

    // 原位弹层（模型/工具/任务详情互斥，同原型 closeAll）
    var modelSheetOpen by mutableStateOf(false)
    var toolSheetOpen by mutableStateOf(false)
    var taskSheetKey by mutableStateOf<String?>(null)
    var modelQuery by mutableStateOf("")

    // 工具表（本回合）
    var thinkOn by mutableStateOf(true)
    var thinkLevel by mutableStateOf(2)
    var webSearchOn by mutableStateOf(true)
    var shellOn by mutableStateOf(false)
    var codeExecOn by mutableStateOf(false)
    var relayOn by mutableStateOf(false)

    // 设置
    var settingsOpen by mutableStateOf(false)
    var settingsQuery by mutableStateOf("")
    /** 子页栈：空=停在设置主页；栈顶=当前二级页 key（对应原型 SUBSTACK）。 */
    var subStack by mutableStateOf(listOf<String>())

    // toast
    var toastText by mutableStateOf("")
    var toastVisible by mutableStateOf(false)
    private var toastSeq = 0
    var toastToken by mutableStateOf(0)

    fun toast(msg: String) {
        toastText = msg
        toastVisible = true
        toastSeq += 1
        toastToken = toastSeq
    }

    fun closeSheets() {
        modelSheetOpen = false
        toolSheetOpen = false
        taskSheetKey = null
        addMenuOpen = false
        drawerOpen = false
    }

    fun toggleSelect(id: String) {
        val cur = selectedIds
        selectedIds = if (id in cur) cur - id else cur + id
    }

    fun openSettings(anchorSub: String? = null) {
        closeSheets()
        settingsQuery = ""
        subStack = if (anchorSub != null) listOf(anchorSub) else emptyList()
        settingsOpen = true
    }

    fun backFromSettings() {
        val s = subStack
        if (s.isNotEmpty()) {
            subStack = s.dropLast(1)
        } else {
            settingsOpen = false
        }
    }
}
