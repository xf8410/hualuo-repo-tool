package com.hualuo.repotool.ui.data

import com.hualuo.repotool.ui.model.IconKey
import com.hualuo.repotool.ui.model.SettingsItem
import com.hualuo.repotool.ui.model.SettingsSection

// 设置页信息架构：源出 ui/v13.html 的 8 组 26 项演示；2026-10-05 竖式改版，前两组分区名
// 改照 Agora（AI 服务->服务、对话->回复），条目一件没动；合并真设置项（SettingsCatalog 追加的
// 「失败与重试」「文件投递」）后为 8 组 28 项。二级页字段表在 SubPages.kt。
// 文案规矩（原型页脚）：每项一句大白话说清「干什么、数据去哪」，不许出现「管理 XX」这种绕话。
// 命名红线：新界面一律不再出现「Agora」字样（地基红线 10），原型里残留的几处已改为「内置/本应用」。
// 版本行用 %VERSION% 占位，渲染时由界面状态替换——数据文件里同样不许写死版本号。
//
// 家规：图标只写 IconKey 键名。图形字符（含转义写法）不进源码，唯一的住处是 res/values/icons.xml，
// 闸门是 engine 的 NoEmojiInSourceTest。
//
// 网页搜索那五家提供商不在这里：唯一事实表是引擎件的 SearchProviders，
// 真页面在 SettingsCatalog 的 RealSubPages 里（原型 v13 那份四选简化列表早已作数）。

val SettingsSections: List<SettingsSection> = listOf(
    // 分区名照 Agora 设置页（服务/回复/多模态，2026-10-05 用户拍板竖式改版）；
    // 后面几组（工具/网络/数据/外观/关于）Agora 没有，沿用自家分组不硬造。
    SettingsSection("s-service", "服务", listOf(
        SettingsItem(IconKey.SettingsProvider, "提供商", "API 密钥、基础 URL 和提供商选择；密钥只存本机，不外发", "3 家", "provider"),
        SettingsItem(IconKey.SettingsModel, "模型", "启用、禁用和配置 AI 模型；上下文上限用模型真实值", "启用 5", "model"),
    )),
    SettingsSection("s-chat", "回复", listOf(
        SettingsItem(IconKey.SettingsPrompt, "系统指令", "每次开聊前先交代的家规", "2 条", "prompt"),
        SettingsItem(IconKey.SettingsGen, "生成参数", "温度、top_p、带多少历史", "temp 0.7", "gen"),
        SettingsItem(IconKey.SettingsTitle, "标题生成", "聊完自动给会话起名字", null, "title"),
        SettingsItem(IconKey.SettingsTrim, "历史裁剪", "装不下时砍谁，砍了必须出声", "按 token", "trim"),
    )),
    SettingsSection("s-multi", "多模态", listOf(
        SettingsItem(IconKey.SettingsCaption, "图像转述", "能看的模型把图说成文字给不能看的用", null, "caption"),
        SettingsItem(IconKey.SettingsTranscription, "语音转写", "语音消息自动转成文字再发给模型", "2 模型", "transcription"),
        SettingsItem(IconKey.SettingsImageGen, "图像生成", "按你写的文字出图", null, "imagegen"),
    )),
    SettingsSection("s-tools", "工具", listOf(
        SettingsItem(IconKey.SettingsWebSearch, "网页搜索", "让 AI 上网查实时资料", null, "websearch"),
        SettingsItem(IconKey.SettingsChatSearch, "对话搜索", "翻以前聊过的内容", null, "chatsearch"),
        SettingsItem(IconKey.SettingsShell, "终端", "手机上跑命令（沙盒里）", null, "shell"),
        SettingsItem(IconKey.SettingsGithub, "GitHub 工作台", "登录、仓库、CI、PR", "xf8410", "github"),
        SettingsItem(IconKey.SettingsSites, "常用网站", "收藏链接速开，AI 帮你存", "12 条", "sites"),
        SettingsItem(IconKey.SettingsRoadmap, "待开发任务", "想要的功能先记这", "3 待办", "roadmap"),
        SettingsItem(IconKey.SettingsRelay, "多智能体接力", "几个模型接龙：一个主答一个挑错", "1 队", "relay"),
        SettingsItem(IconKey.SettingsUma, "赛马娘工作台", "18765 只读观测桥，不写游戏", "在线", "uma"),
        SettingsItem(IconKey.SettingsTasks, "定时任务", "到点自动干活，每次开新会话", "2 启用", "tasks"),
        SettingsItem(IconKey.SettingsLoop, "会话循环", "隔一阵自动接一句，有轮次上限", "1 运行", "loop"),
    )),
    SettingsSection("s-net", "网络", listOf(
        SettingsItem(IconKey.SettingsProxy, "代理", "网络不通时走 HTTP/SOCKS 转发", "未设置", "proxy"),
    )),
    SettingsSection("s-data", "记忆与数据", listOf(
        SettingsItem(IconKey.SettingsMemory, "记忆", "AI 长期记的东西，能看能删", "2 文件", "memory"),
        SettingsItem(IconKey.SettingsDataCtl, "数据控制", "导出一个文件 / 导入合并", null, "datactl"),
        SettingsItem(IconKey.SettingsStorage, "存储占用", "对话附件各占多少，清缓存", "2.4/8.1G", "storage"),
    )),
    SettingsSection("s-general", "外观与语言", listOf(
        SettingsItem(IconKey.SettingsAppearance, "外观", "亮/暗/跟随，主色可换", "跟随", "appearance"),
        SettingsItem(IconKey.SettingsLang, "语言", "界面显示语言", "中文", "lang"),
    )),
    SettingsSection("s-about", "关于", listOf(
        SettingsItem(IconKey.SettingsAbout, "关于", "版本 · 更新 · 崩溃报告 · 提 issue", null, "about"),
    )),
)