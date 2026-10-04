package com.hualuo.repotool.ui.data

import com.hualuo.repotool.ui.model.IconKey
import com.hualuo.repotool.ui.model.Tone
import com.hualuo.repotool.ui.model.ToolState

// 演示数据残留区（2026-10-05 大扫除）：
// 已删——DemoMessages/DemoConversations/DemoComposerThumbs/DemoTasks/DemoModels/
//        DemoRepoRows/DemoObsRows（假对话/假会话/假缩略位/假任务/假模型清单/假仓库行/假观测行，
//        「装作在用」比空着更骗人，用户拍板撤除）。
// 仍保留——弹层与回合流栏还在引用的静态形状（SheetScaffold 工具状态位/循环条/排队条/ctx 读数/
//        附件菜单键位表）。对应功能实装时一并替换为真数据源，届时本文件清空删除。

val DemoToolStates: List<ToolState> = listOf(
    ToolState("audit_list", listOf(Tone.Ok, Tone.Ok, Tone.Ok, Tone.Ok)),
    ToolState("courier 文件投递", listOf(Tone.Ok, Tone.Ok, Tone.Ok, Tone.Warn)),
    ToolState("uma_read_endpoint", listOf(Tone.Ok, Tone.Ok, Tone.Err, Tone.Neutral)),
)

val DemoLoopIcon = IconKey.Loop
val DemoLoopBar = "会话循环 4/20 · 每 300s"
val DemoQueueIcon = IconKey.Hourglass
val DemoQueueBar = "排队中 1 条：「继续挖 phase2」"
val DemoCtx = "ctx 21.4k/1M · 发 856"

/** 附件菜单三项（v13 addmenu，原样开头是一个全角加号）：图形键名 + 文字。 */
val DemoAttachMenu = listOf(
    IconKey.Picture to "照片",
    IconKey.Clapper to "视频（自动抽帧）",
    IconKey.Phone to "文件（PDF 选页）",
)
