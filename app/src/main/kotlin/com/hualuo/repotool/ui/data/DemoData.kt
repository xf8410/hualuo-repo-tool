package com.hualuo.repotool.ui.data

import com.hualuo.repotool.ui.model.IconKey

// 演示数据残留区（2026-10-05 大扫除）：
// 已删——DemoMessages/DemoConversations/DemoComposerThumbs/DemoTasks/DemoModels/
//        DemoRepoRows/DemoObsRows（假对话/假会话/假缩略位/假任务/假模型清单/假仓库行/假观测行，
//        「装作在用」比空着更骗人，用户拍板撤除）。
// 2026-10-05 全套刀再删——DemoToolStates（工具页四态假卡）：真账卡（ToolLedgerCard）
//        从真实注册表读，假四态连定义一起撤，不留备份。
// 仍保留——回合流栏（Composer 循环条/排队条/ctx 读数/附件菜单键位表）还在引用的
//        静态形状。对应功能实装时一并替换为真数据源，届时本文件清空删除。

val DemoCtx = "ctx 21.4k/1M · 发 856"

/** 附件菜单三项（v13 addmenu，原样开头是一个全角加号）：图形键名 + 文字。 */
val DemoAttachMenu = listOf(
    IconKey.Picture to "照片",
    IconKey.Clapper to "视频（自动抽帧）",
    IconKey.Phone to "文件（PDF 选页）",
)
