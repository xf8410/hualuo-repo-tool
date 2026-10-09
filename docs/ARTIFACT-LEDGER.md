# Artifact 统一账本（功能 · 包 · 证据 对齐表）

> 生成：2026-10-09 08:25（Asia/Shanghai）· 生成器：`tools/gen-artifact-ledger.py`（幂等，随时重跑）
> 数据源：`docs/FEATURE-LEDGER.csv` + GitHub API（releases / actions runs / artifacts）
> 规矩出处：一功能一包一存档（用户规矩：跑一次 CI 留一个包）；本表只登记事实，覆盖判定看审计报告。

## 一、总览

- 功能账：**818** 条（1-818）
- Release：**93** 个（功能包 23 · 常规自检 70）
- Actions run：**680** 个；其中功能名 run 238 条（涉及 125 个功能号）
- 对齐：功能包 23/23 能对上功能账（全对上）
- 无包功能：795 条（包是两波：10-02 的 1-10 语言批与 805 起的实装批；其余条目存证=PR+commit，属正常态）

## 二、功能包对照表（新到旧）

| 功能号 | 功能名（账本） | 账本关联 | 构建 run | Release（APK） | 源码 | 包日期 | 证据（逐测试 XML） |
|---|---|---|---|---|---|---|---|
| 818 | 撤最后三页无后端演示页（语音转写/多智能体接力/Claude导入）；清 relayOn 摆设布尔与图标资源；24 设置页全真实 | [PR#100](https://github.com/xf8410/hualuo-repo-tool/pull/100) | [#690](https://github.com/xf8410/hualuo-repo-tool/actions/runs/37861597645) | [功能818-src7b72fa6](https://github.com/xf8410/hualuo-repo-tool/releases/tag/%E5%8A%9F%E8%83%BD818-src7b72fa6) · [Hualuo-v0.6.1-F818-signed.apk](https://github.com/xf8410/hualuo-repo-tool/releases/download/%E5%8A%9F%E8%83%BD818-src7b72fa6/Hualuo-v0.6.1-F818-signed.apk) | [7b72fa6](https://github.com/xf8410/hualuo-repo-tool/commit/7b72fa6) | 2026-10-08 | [Hualuo-v0.6.1-功能818-构建包-src7b72fa6](https://github.com/xf8410/hualuo-repo-tool/actions/runs/37861597645) |
| 817 | 撤 DEFAULT_MODEL（无钥匙的默认=摆设）；视觉/图像生成页换已接入模型点选卡；清假模型清单 | [PR#99](https://github.com/xf8410/hualuo-repo-tool/pull/99) | [#686](https://github.com/xf8410/hualuo-repo-tool/actions/runs/37758028600) | [功能817-srce3f570d](https://github.com/xf8410/hualuo-repo-tool/releases/tag/%E5%8A%9F%E8%83%BD817-srce3f570d) · [Hualuo-v0.6.1-F817-signed.apk](https://github.com/xf8410/hualuo-repo-tool/releases/download/%E5%8A%9F%E8%83%BD817-srce3f570d/Hualuo-v0.6.1-F817-signed.apk) | [e3f570d](https://github.com/xf8410/hualuo-repo-tool/commit/e3f570d) | 2026-10-08 | [Hualuo-v0.6.1-功能817-构建包-srce3f570d](https://github.com/xf8410/hualuo-repo-tool/actions/runs/37758028600) |
| 816 | 复用工具页 ApkCheckCard 真组件（ApkInspector 引擎） | [PR#98](https://github.com/xf8410/hualuo-repo-tool/pull/98) | [#685](https://github.com/xf8410/hualuo-repo-tool/actions/runs/37758024912) | [功能816-srce3f570d](https://github.com/xf8410/hualuo-repo-tool/releases/tag/%E5%8A%9F%E8%83%BD816-srce3f570d) · [Hualuo-v0.6.1-F816-signed.apk](https://github.com/xf8410/hualuo-repo-tool/releases/download/%E5%8A%9F%E8%83%BD816-srce3f570d/Hualuo-v0.6.1-F816-signed.apk) | [e3f570d](https://github.com/xf8410/hualuo-repo-tool/commit/e3f570d) | 2026-10-08 | [Hualuo-v0.6.1-功能816-构建包-srce3f570d](https://github.com/xf8410/hualuo-repo-tool/actions/runs/37758024912) |
| 815 | 选图真调视觉模型（VisionTurns三协议）；三护栏：无模型明示/全链runCatching/4MB上限 | [PR#97](https://github.com/xf8410/hualuo-repo-tool/pull/97) | [#684](https://github.com/xf8410/hualuo-repo-tool/actions/runs/37758020794) | [功能815-srce3f570d](https://github.com/xf8410/hualuo-repo-tool/releases/tag/%E5%8A%9F%E8%83%BD815-srce3f570d) · [Hualuo-v0.6.1-F815-signed.apk](https://github.com/xf8410/hualuo-repo-tool/releases/download/%E5%8A%9F%E8%83%BD815-srce3f570d/Hualuo-v0.6.1-F815-signed.apk) | [e3f570d](https://github.com/xf8410/hualuo-repo-tool/commit/e3f570d) | 2026-10-08 | [Hualuo-v0.6.1-功能815-构建包-srce3f570d](https://github.com/xf8410/hualuo-repo-tool/actions/runs/37758020794) |
| 814 | 关键词逐会话搜+点行切会话；空库空词安静返回 | [PR#96](https://github.com/xf8410/hualuo-repo-tool/pull/96) | [#683](https://github.com/xf8410/hualuo-repo-tool/actions/runs/37758016633) | [功能814-srce3f570d](https://github.com/xf8410/hualuo-repo-tool/releases/tag/%E5%8A%9F%E8%83%BD814-srce3f570d) · [Hualuo-v0.6.1-F814-signed.apk](https://github.com/xf8410/hualuo-repo-tool/releases/download/%E5%8A%9F%E8%83%BD814-srce3f570d/Hualuo-v0.6.1-F814-signed.apk) | [e3f570d](https://github.com/xf8410/hualuo-repo-tool/commit/e3f570d) | 2026-10-08 | [Hualuo-v0.6.1-功能814-构建包-srce3f570d](https://github.com/xf8410/hualuo-repo-tool/actions/runs/37758016633) |
| 813 | ProxyCard：类型/地址/端口真存盘当场 installProxy（AI+GitHub API 出网口）；观测桥不经此口 | [PR#95](https://github.com/xf8410/hualuo-repo-tool/pull/95) | [#668](https://github.com/xf8410/hualuo-repo-tool/actions/runs/37712288353) | [功能813-src513f2f7](https://github.com/xf8410/hualuo-repo-tool/releases/tag/%E5%8A%9F%E8%83%BD813-src513f2f7) · [Hualuo-v0.6.1-F813-signed.apk](https://github.com/xf8410/hualuo-repo-tool/releases/download/%E5%8A%9F%E8%83%BD813-src513f2f7/Hualuo-v0.6.1-F813-signed.apk) | [513f2f7](https://github.com/xf8410/hualuo-repo-tool/commit/513f2f7) | 2026-10-08 | [Hualuo-v0.6.1-功能813-构建包-src513f2f7](https://github.com/xf8410/hualuo-repo-tool/actions/runs/37712288353) |
| 812 | 数据分析（AI严格JSON+Canvas折线图）/汇报文档（Markdown渲染）/PPT大纲（页卡翻页）；NavTab 第六页+一次性chat通路 | [PR#94](https://github.com/xf8410/hualuo-repo-tool/pull/94) | [#663](https://github.com/xf8410/hualuo-repo-tool/actions/runs/37699038098) | [功能812-srce826a73](https://github.com/xf8410/hualuo-repo-tool/releases/tag/%E5%8A%9F%E8%83%BD812-srce826a73) · [Hualuo-v0.6.1-F812-signed.apk](https://github.com/xf8410/hualuo-repo-tool/releases/download/%E5%8A%9F%E8%83%BD812-srce826a73/Hualuo-v0.6.1-F812-signed.apk) | [e826a73](https://github.com/xf8410/hualuo-repo-tool/commit/e826a73) | 2026-10-07 | [Hualuo-v0.6.1-功能812-构建包-srce826a73](https://github.com/xf8410/hualuo-repo-tool/actions/runs/37699038098) |
| 811 | LoopController 真调度（ChatRuntime.onSettled 驱动/按停还控制权/换会话清零）+循环条真读态+LoopCard；排队条诚实化 | [PR#93](https://github.com/xf8410/hualuo-repo-tool/pull/93) | [#662](https://github.com/xf8410/hualuo-repo-tool/actions/runs/37699035134) | [功能811-srce826a73](https://github.com/xf8410/hualuo-repo-tool/releases/tag/%E5%8A%9F%E8%83%BD811-srce826a73) · [Hualuo-v0.6.1-F811-signed.apk](https://github.com/xf8410/hualuo-repo-tool/releases/download/%E5%8A%9F%E8%83%BD811-srce826a73/Hualuo-v0.6.1-F811-signed.apk) | [e826a73](https://github.com/xf8410/hualuo-repo-tool/commit/e826a73) | 2026-10-07 | [Hualuo-v0.6.1-功能811-构建包-srce826a73](https://github.com/xf8410/hualuo-repo-tool/actions/runs/37699035134) |
| 810 | TaskStore 真任务表+TaskWorker（15min节拍到点开新会话发提示词，runLog 50条）+TasksCard 新建/启停/确认删除 | [PR#92](https://github.com/xf8410/hualuo-repo-tool/pull/92) | [#661](https://github.com/xf8410/hualuo-repo-tool/actions/runs/37699032229) | [功能810-srce826a73](https://github.com/xf8410/hualuo-repo-tool/releases/tag/%E5%8A%9F%E8%83%BD810-srce826a73) · [Hualuo-v0.6.1-F810-signed.apk](https://github.com/xf8410/hualuo-repo-tool/releases/download/%E5%8A%9F%E8%83%BD810-srce826a73/Hualuo-v0.6.1-F810-signed.apk) | [e826a73](https://github.com/xf8410/hualuo-repo-tool/commit/e826a73) | 2026-10-07 | [Hualuo-v0.6.1-功能810-构建包-srce826a73](https://github.com/xf8410/hualuo-repo-tool/actions/runs/37699032229) |
| 809 | CiRunsCard：真调 GitHubCiClient.latestRuns 最近5条红绿；与后台通知双通道 | [PR#86](https://github.com/xf8410/hualuo-repo-tool/pull/86) | [#616](https://github.com/xf8410/hualuo-repo-tool/actions/runs/37464512694) | [功能809-src6d96933](https://github.com/xf8410/hualuo-repo-tool/releases/tag/%E5%8A%9F%E8%83%BD809-src6d96933) · [Hualuo-v0.6.1-F809-signed.apk](https://github.com/xf8410/hualuo-repo-tool/releases/download/%E5%8A%9F%E8%83%BD809-src6d96933/Hualuo-v0.6.1-F809-signed.apk) | [6d96933](https://github.com/xf8410/hualuo-repo-tool/commit/6d96933) | 2026-10-06 | [Hualuo-v0.6.1-功能809-构建包-src6d96933](https://github.com/xf8410/hualuo-repo-tool/actions/runs/37464512694) |
| 808 | MemoryCard：memory_db 真统计+活动记忆原文+带确认删除（deleteFile 连 meta 清） | [PR#85](https://github.com/xf8410/hualuo-repo-tool/pull/85) | [#615](https://github.com/xf8410/hualuo-repo-tool/actions/runs/37464509215) | [功能808-src6d96933](https://github.com/xf8410/hualuo-repo-tool/releases/tag/%E5%8A%9F%E8%83%BD808-src6d96933) · [Hualuo-v0.6.1-F808-signed.apk](https://github.com/xf8410/hualuo-repo-tool/releases/download/%E5%8A%9F%E8%83%BD808-src6d96933/Hualuo-v0.6.1-F808-signed.apk) | [6d96933](https://github.com/xf8410/hualuo-repo-tool/commit/6d96933) | 2026-10-06 | [Hualuo-v0.6.1-功能808-构建包-src6d96933](https://github.com/xf8410/hualuo-repo-tool/actions/runs/37464509215) |
| 807 | AboutCard 真卡：版本真值/检查更新真调 releases/latest/提issue真intent/开源许可弹层；崩溃留档开关全链（CrashObserver.keepLocal 内存门） | [PR#84](https://github.com/xf8410/hualuo-repo-tool/pull/84) | [#614](https://github.com/xf8410/hualuo-repo-tool/actions/runs/37464505754) | [功能807-src6d96933](https://github.com/xf8410/hualuo-repo-tool/releases/tag/%E5%8A%9F%E8%83%BD807-src6d96933) · [Hualuo-v0.6.1-F807-signed.apk](https://github.com/xf8410/hualuo-repo-tool/releases/download/%E5%8A%9F%E8%83%BD807-src6d96933/Hualuo-v0.6.1-F807-signed.apk) | [6d96933](https://github.com/xf8410/hualuo-repo-tool/commit/6d96933) | 2026-10-06 | [Hualuo-v0.6.1-功能807-构建包-src6d96933](https://github.com/xf8410/hualuo-repo-tool/actions/runs/37464505754) |
| 805 | 设置「生成参数」页真滑块/真选项组（温度0-2步进0.1、top_p、历史条数），温度/top_p 首次接进聊天请求体；替换 Slider(label=...) 字面量演示 | [PR#78](https://github.com/xf8410/hualuo-repo-tool/pull/78) | [#584](https://github.com/xf8410/hualuo-repo-tool/actions/runs/37142983182) | [功能805-srcfb0ce3e](https://github.com/xf8410/hualuo-repo-tool/releases/tag/%E5%8A%9F%E8%83%BD805-srcfb0ce3e) · [Hualuo-v0.6.1-F805-signed.apk](https://github.com/xf8410/hualuo-repo-tool/releases/download/%E5%8A%9F%E8%83%BD805-srcfb0ce3e/Hualuo-v0.6.1-F805-signed.apk) | [fb0ce3e](https://github.com/xf8410/hualuo-repo-tool/commit/fb0ce3e) | 2026-10-03 | [Hualuo-v0.6.1-功能805-构建包-srcfb0ce3e](https://github.com/xf8410/hualuo-repo-tool/actions/runs/37142983182) |
| 10 | TypeScript（typescript）·语法高亮与查看器着色 | — | [#551](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36969008985) | [功能10-src87e5110](https://github.com/xf8410/hualuo-repo-tool/releases/tag/%E5%8A%9F%E8%83%BD10-src87e5110) · [Hualuo-v0.6.0-F10-signed.apk](https://github.com/xf8410/hualuo-repo-tool/releases/download/%E5%8A%9F%E8%83%BD10-src87e5110/Hualuo-v0.6.0-F10-signed.apk) | [87e5110](https://github.com/xf8410/hualuo-repo-tool/commit/87e5110) | 2026-10-02 | [Hualuo-v0.6.0-功能10-构建包-src87e5110](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36969008985) |
| 9 | JavaScript（javascript）·语法高亮与查看器着色 | — | [#550](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36969002807) | [功能9-src87e5110](https://github.com/xf8410/hualuo-repo-tool/releases/tag/%E5%8A%9F%E8%83%BD9-src87e5110) · [Hualuo-v0.6.0-F9-signed.apk](https://github.com/xf8410/hualuo-repo-tool/releases/download/%E5%8A%9F%E8%83%BD9-src87e5110/Hualuo-v0.6.0-F9-signed.apk) | [87e5110](https://github.com/xf8410/hualuo-repo-tool/commit/87e5110) | 2026-10-02 | [Hualuo-v0.6.0-功能9-构建包-src87e5110](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36969002807) |
| 8 | C#（csharp）·语法高亮与查看器着色 | — | [#549](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36968997567) | [功能8-src87e5110](https://github.com/xf8410/hualuo-repo-tool/releases/tag/%E5%8A%9F%E8%83%BD8-src87e5110) · [Hualuo-v0.6.0-F8-signed.apk](https://github.com/xf8410/hualuo-repo-tool/releases/download/%E5%8A%9F%E8%83%BD8-src87e5110/Hualuo-v0.6.0-F8-signed.apk) | [87e5110](https://github.com/xf8410/hualuo-repo-tool/commit/87e5110) | 2026-10-02 | [Hualuo-v0.6.0-功能8-构建包-src87e5110](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36968997567) |
| 7 | C++（cpp）·语法高亮与查看器着色 | — | [#548](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36968989606) | [功能7-src87e5110](https://github.com/xf8410/hualuo-repo-tool/releases/tag/%E5%8A%9F%E8%83%BD7-src87e5110) · [Hualuo-v0.6.0-F7-signed.apk](https://github.com/xf8410/hualuo-repo-tool/releases/download/%E5%8A%9F%E8%83%BD7-src87e5110/Hualuo-v0.6.0-F7-signed.apk) | [87e5110](https://github.com/xf8410/hualuo-repo-tool/commit/87e5110) | 2026-10-02 | [Hualuo-v0.6.0-功能7-构建包-src87e5110](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36968989606) |
| 6 | C（c）·语法高亮与查看器着色 | — | [#547](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36968986290) | [功能6-src87e5110](https://github.com/xf8410/hualuo-repo-tool/releases/tag/%E5%8A%9F%E8%83%BD6-src87e5110) · [Hualuo-v0.6.0-F6-signed.apk](https://github.com/xf8410/hualuo-repo-tool/releases/download/%E5%8A%9F%E8%83%BD6-src87e5110/Hualuo-v0.6.0-F6-signed.apk) | [87e5110](https://github.com/xf8410/hualuo-repo-tool/commit/87e5110) | 2026-10-02 | [Hualuo-v0.6.0-功能6-构建包-src87e5110](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36968986290) |
| 5 | Go（go）·语法高亮与查看器着色 | — | [#546](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36968983099) | [功能5-src87e5110](https://github.com/xf8410/hualuo-repo-tool/releases/tag/%E5%8A%9F%E8%83%BD5-src87e5110) · [Hualuo-v0.6.0-F5-signed.apk](https://github.com/xf8410/hualuo-repo-tool/releases/download/%E5%8A%9F%E8%83%BD5-src87e5110/Hualuo-v0.6.0-F5-signed.apk) | [87e5110](https://github.com/xf8410/hualuo-repo-tool/commit/87e5110) | 2026-10-02 | [Hualuo-v0.6.0-功能5-构建包-src87e5110](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36968983099) |
| 4 | Rust（rust）·语法高亮与查看器着色 | — | [#545](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36968979906) | [功能4-src87e5110](https://github.com/xf8410/hualuo-repo-tool/releases/tag/%E5%8A%9F%E8%83%BD4-src87e5110) · [Hualuo-v0.6.0-F4-signed.apk](https://github.com/xf8410/hualuo-repo-tool/releases/download/%E5%8A%9F%E8%83%BD4-src87e5110/Hualuo-v0.6.0-F4-signed.apk) | [87e5110](https://github.com/xf8410/hualuo-repo-tool/commit/87e5110) | 2026-10-02 | [Hualuo-v0.6.0-功能4-构建包-src87e5110](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36968979906) |
| 3 | Python（python）·语法高亮与查看器着色 | — | [#544](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36968977236) | [功能3-src87e5110](https://github.com/xf8410/hualuo-repo-tool/releases/tag/%E5%8A%9F%E8%83%BD3-src87e5110) · [Hualuo-v0.6.0-F3-signed.apk](https://github.com/xf8410/hualuo-repo-tool/releases/download/%E5%8A%9F%E8%83%BD3-src87e5110/Hualuo-v0.6.0-F3-signed.apk) | [87e5110](https://github.com/xf8410/hualuo-repo-tool/commit/87e5110) | 2026-10-02 | [Hualuo-v0.6.0-功能3-构建包-src87e5110](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36968977236) |
| 2 | Java（java）·语法高亮与查看器着色 | — | [#543](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36968933514) | [功能2-src87e5110](https://github.com/xf8410/hualuo-repo-tool/releases/tag/%E5%8A%9F%E8%83%BD2-src87e5110) · [Hualuo-v0.6.0-F2-signed.apk](https://github.com/xf8410/hualuo-repo-tool/releases/download/%E5%8A%9F%E8%83%BD2-src87e5110/Hualuo-v0.6.0-F2-signed.apk) | [87e5110](https://github.com/xf8410/hualuo-repo-tool/commit/87e5110) | 2026-10-02 | [Hualuo-v0.6.0-功能2-构建包-src87e5110](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36968933514) |
| 1 | Kotlin（kotlin）·语法高亮与查看器着色 | — | [#542](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36968930679) | [功能1-src87e5110](https://github.com/xf8410/hualuo-repo-tool/releases/tag/%E5%8A%9F%E8%83%BD1-src87e5110) · [Hualuo-v0.6.0-F1-signed.apk](https://github.com/xf8410/hualuo-repo-tool/releases/download/%E5%8A%9F%E8%83%BD1-src87e5110/Hualuo-v0.6.0-F1-signed.apk) | [87e5110](https://github.com/xf8410/hualuo-repo-tool/commit/87e5110) | 2026-10-02 | [Hualuo-v0.6.0-功能1-构建包-src87e5110](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36968930679) |

## 三、对账区（闭合审计入口）

### 有包无账（Release 有功能 tag，功能账没这行——漏记账，要补）

- 无：功能包全部有账。

### 功能名 run 非 success（红 run=定位坐标，不许删；多数功能后来已绿）

共 225 条（涉及 112 个功能号）：

- 功能 1：run [320](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36872194629) 结论 cancelled
- 功能 1：run [321](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36872408907) 结论 cancelled
- 功能 2：run [322](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36872415037) 结论 cancelled
- 功能 3：run [323](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36872420782) 结论 cancelled
- 功能 4：run [324](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36872426541) 结论 cancelled
- 功能 5：run [325](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36872431882) 结论 cancelled
- 功能 6：run [326](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36872437082) 结论 cancelled
- 功能 7：run [327](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36872441904) 结论 cancelled
- 功能 8：run [328](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36872447277) 结论 cancelled
- 功能 9：run [329](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36872452741) 结论 cancelled
- 功能 1：run [331](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36872639724) 结论 cancelled
- 功能 2：run [332](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36872644970) 结论 failure
- 功能 3：run [333](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36872650386) 结论 failure
- 功能 4：run [334](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36872656223) 结论 failure
- 功能 5：run [335](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36872661591) 结论 failure
- 功能 6：run [336](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36872667458) 结论 failure
- 功能 7：run [337](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36872674012) 结论 failure
- 功能 8：run [338](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36872680371) 结论 failure
- 功能 9：run [339](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36872685933) 结论 failure
- 功能 1：run [340](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36872828867) 结论 cancelled
- 功能 1：run [341](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36872953523) 结论 failure
- 功能 1：run [343](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36897175405) 结论 failure
- 功能 2：run [344](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36897180492) 结论 failure
- 功能 3：run [345](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36897185427) 结论 failure
- 功能 4：run [346](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36897190761) 结论 failure
- 功能 5：run [347](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36897195945) 结论 failure
- 功能 6：run [348](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36897200952) 结论 failure
- 功能 7：run [349](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36897205769) 结论 failure
- 功能 8：run [350](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36897210402) 结论 failure
- 功能 9：run [351](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36897215012) 结论 failure
- 功能 10：run [352](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36897219930) 结论 failure
- 功能 11：run [353](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36897681701) 结论 failure
- 功能 12：run [354](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36897686818) 结论 failure
- 功能 13：run [355](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36897691566) 结论 cancelled
- 功能 14：run [356](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36897697199) 结论 failure
- 功能 15：run [357](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36897702696) 结论 failure
- 功能 16：run [358](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36897707606) 结论 cancelled
- 功能 17：run [359](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36897713044) 结论 cancelled
- 功能 18：run [360](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36897718076) 结论 cancelled
- 功能 19：run [361](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36897723277) 结论 failure
- 功能 20：run [362](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36897727913) 结论 failure
- 功能 13：run [363](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36898001470) 结论 cancelled
- 功能 16：run [364](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36898007361) 结论 failure
- 功能 17：run [365](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36898012869) 结论 failure
- 功能 18：run [366](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36898018766) 结论 cancelled
- 功能 13：run [367](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36898047249) 结论 cancelled
- 功能 18：run [368](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36898052470) 结论 cancelled
- 功能 21：run [369](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36898057896) 结论 cancelled
- 功能 22：run [370](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36898063465) 结论 cancelled
- 功能 23：run [371](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36898068574) 结论 failure
- 功能 24：run [372](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36898073649) 结论 failure
- 功能 13：run [373](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36898075202) 结论 cancelled
- 功能 18：run [374](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36898080266) 结论 cancelled
- 功能 21：run [375](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36898085947) 结论 cancelled
- 功能 22：run [376](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36898091085) 结论 cancelled
- 功能 13：run [377](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36898147672) 结论 cancelled
- 功能 18：run [378](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36898153482) 结论 cancelled
- 功能 13：run [379](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36898159357) 结论 cancelled
- 功能 18：run [380](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36898165885) 结论 cancelled
- 功能 13：run [381](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36898316544) 结论 cancelled
- 功能 18：run [382](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36898321632) 结论 cancelled
- 功能 13：run [383](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36898334761) 结论 cancelled
- 功能 18：run [384](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36898339931) 结论 cancelled
- 功能 13：run [385](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36898392988) 结论 cancelled
- 功能 18：run [386](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36898398997) 结论 cancelled
- 功能 21：run [387](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36898404808) 结论 cancelled
- 功能 22：run [388](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36898410218) 结论 cancelled
- 功能 13：run [389](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36898410765) 结论 cancelled
- 功能 18：run [390](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36898415820) 结论 cancelled
- 功能 21：run [391](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36898420868) 结论 cancelled
- 功能 22：run [392](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36898425865) 结论 cancelled
- 功能 25：run [393](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36898430876) 结论 cancelled
- 功能 13：run [394](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36898471759) 结论 cancelled
- 功能 18：run [395](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36898477623) 结论 cancelled
- 功能 21：run [397](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36898483306) 结论 cancelled
- 功能 13：run [398](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36898483475) 结论 cancelled
- 功能 22：run [399](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36898488211) 结论 cancelled
- 功能 18：run [400](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36898488534) 结论 cancelled
- 功能 21：run [401](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36898493278) 结论 cancelled
- 功能 25：run [402](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36898494140) 结论 cancelled
- 功能 22：run [403](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36898499015) 结论 cancelled
- 功能 25：run [404](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36898504903) 结论 cancelled
- 功能 13：run [405](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36898644277) 结论 cancelled
- 功能 18：run [406](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36898649585) 结论 cancelled
- 功能 21：run [407](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36898654381) 结论 cancelled
- 功能 22：run [408](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36898659126) 结论 cancelled
- 功能 13：run [409](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36898662134) 结论 failure
- 功能 25：run [410](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36898663815) 结论 cancelled
- 功能 18：run [411](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36898667285) 结论 failure
- 功能 26：run [412](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36898669054) 结论 failure
- 功能 21：run [413](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36898672437) 结论 failure
- 功能 22：run [414](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36898677045) 结论 failure
- 功能 25：run [415](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36898682388) 结论 failure
- 功能 13：run [417](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36899426009) 结论 failure
- 功能 18：run [418](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36899431883) 结论 failure
- 功能 21：run [419](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36899436624) 结论 failure
- 功能 22：run [420](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36899442183) 结论 failure
- 功能 25：run [421](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36899446928) 结论 failure
- 功能 26：run [422](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36899452886) 结论 failure
- 功能 27：run [423](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36899457967) 结论 failure
- 功能 28：run [424](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36899463223) 结论 failure
- 功能 29：run [425](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36899467983) 结论 failure
- 功能 30：run [426](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36899472915) 结论 failure
- 功能 31：run [428](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36957387768) 结论 failure
- 功能 32：run [429](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36957390628) 结论 failure
- 功能 33：run [430](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36957393498) 结论 failure
- 功能 34：run [431](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36957396227) 结论 failure
- 功能 35：run [432](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36957399136) 结论 failure
- 功能 36：run [433](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36957402339) 结论 failure
- 功能 37：run [434](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36957405772) 结论 failure
- 功能 38：run [435](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36957408897) 结论 failure
- 功能 39：run [436](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36957411755) 结论 failure
- 功能 40：run [437](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36957414587) 结论 failure
- 功能 13：run [438](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36957688874) 结论 failure
- 功能 18：run [439](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36957691729) 结论 failure
- 功能 21：run [440](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36957694721) 结论 failure
- 功能 22：run [441](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36957697234) 结论 failure
- 功能 25：run [442](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36957699989) 结论 failure
- 功能 26：run [443](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36957702916) 结论 failure
- 功能 27：run [444](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36957705690) 结论 failure
- 功能 28：run [445](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36957708547) 结论 failure
- 功能 29：run [446](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36957712369) 结论 failure
- 功能 30：run [447](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36957738113) 结论 failure
- 功能 41：run [448](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36957908499) 结论 failure
- 功能 42：run [449](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36957911601) 结论 failure
- 功能 43：run [450](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36957915549) 结论 failure
- 功能 44：run [451](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36958104408) 结论 failure
- 功能 45：run [452](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36958107153) 结论 failure
- 功能 46：run [453](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36958110306) 结论 failure
- 功能 47：run [454](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36958113013) 结论 failure
- 功能 48：run [455](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36958115629) 结论 failure
- 功能 49：run [456](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36958118242) 结论 failure
- 功能 50：run [457](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36958120902) 结论 failure
- 功能 51：run [458](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36958328081) 结论 failure
- 功能 52：run [459](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36958330721) 结论 failure
- 功能 53：run [460](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36958333755) 结论 failure
- 功能 54：run [461](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36958336855) 结论 failure
- 功能 55：run [462](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36958340490) 结论 failure
- 功能 56：run [463](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36958542243) 结论 failure
- 功能 57：run [464](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36958545339) 结论 failure
- 功能 58：run [465](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36958548203) 结论 failure
- 功能 59：run [466](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36958551117) 结论 failure
- 功能 60：run [467](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36958554102) 结论 failure
- 功能 61：run [468](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36958557011) 结论 failure
- 功能 62：run [469](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36958559930) 结论 failure
- 功能 63：run [470](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36958768726) 结论 failure
- 功能 64：run [471](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36958772402) 结论 failure
- 功能 65：run [472](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36958775423) 结论 failure
- 功能 66：run [473](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36958778956) 结论 failure
- 功能 67：run [474](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36958781971) 结论 failure
- 功能 68：run [475](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36958784840) 结论 failure
- 功能 69：run [476](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36958984822) 结论 failure
- 功能 70：run [477](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36958987883) 结论 failure
- 功能 71：run [478](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36958991270) 结论 failure
- 功能 72：run [479](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36958994498) 结论 failure
- 功能 73：run [480](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36958997664) 结论 failure
- 功能 74：run [481](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36959000974) 结论 failure
- 功能 75：run [482](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36959004131) 结论 failure
- 功能 76：run [483](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36959206943) 结论 failure
- 功能 77：run [484](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36959209754) 结论 failure
- 功能 78：run [485](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36959212641) 结论 failure
- 功能 79：run [486](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36959215466) 结论 failure
- 功能 80：run [487](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36959218399) 结论 failure
- 功能 81：run [488](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36959419309) 结论 failure
- 功能 82：run [489](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36959422743) 结论 failure
- 功能 83：run [490](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36959425540) 结论 failure
- 功能 84：run [491](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36959428545) 结论 failure
- 功能 85：run [492](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36959431360) 结论 failure
- 功能 86：run [493](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36959626896) 结论 failure
- 功能 87：run [494](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36959629805) 结论 failure
- 功能 88：run [495](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36959633061) 结论 failure
- 功能 89：run [496](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36959635778) 结论 failure
- 功能 90：run [497](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36959638963) 结论 failure
- 功能 91：run [498](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36959641559) 结论 failure
- 功能 92：run [499](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36959644549) 结论 failure
- 功能 93：run [500](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36959840440) 结论 failure
- 功能 94：run [501](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36959843210) 结论 failure
- 功能 95：run [502](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36959846239) 结论 failure
- 功能 96：run [503](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36959849043) 结论 failure
- 功能 97：run [504](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36959852209) 结论 failure
- 功能 98：run [505](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36959854927) 结论 failure
- 功能 99：run [506](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36960054279) 结论 failure
- 功能 100：run [507](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36960057231) 结论 failure
- 功能 101：run [508](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36960059858) 结论 failure
- 功能 102：run [509](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36960062335) 结论 failure
- 功能 103：run [510](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36960064990) 结论 failure
- 功能 104：run [511](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36960261360) 结论 failure
- 功能 105：run [512](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36960264007) 结论 failure
- 功能 106：run [513](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36960266544) 结论 failure
- 功能 107：run [514](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36960269493) 结论 failure
- 功能 108：run [515](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36960272419) 结论 failure
- 功能 109：run [516](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36960275663) 结论 failure
- 功能 110：run [517](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36960278753) 结论 failure
- 功能 111：run [518](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36960281467) 结论 failure
- 功能 112：run [519](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36960284789) 结论 failure
- 功能 14：run [521](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36968221925) 结论 failure
- 功能 15：run [522](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36968224606) 结论 failure
- 功能 16：run [523](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36968227249) 结论 failure
- 功能 17：run [524](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36968230125) 结论 failure
- 功能 19：run [525](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36968233086) 结论 failure
- 功能 20：run [526](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36968236031) 结论 failure
- 功能 23：run [527](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36968242089) 结论 failure
- 功能 24：run [528](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36968244996) 结论 failure
- 功能 100：run [529](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36968253827) 结论 failure
- 功能 101：run [530](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36968256968) 结论 failure
- 功能 1：run [531](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36968606765) 结论 failure
- 功能 2：run [532](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36968613505) 结论 failure
- 功能 3：run [533](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36968616989) 结论 failure
- 功能 4：run [534](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36968622851) 结论 failure
- 功能 5：run [535](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36968626720) 结论 failure
- 功能 6：run [536](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36968629303) 结论 failure
- 功能 7：run [537](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36968633460) 结论 failure
- 功能 8：run [538](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36968636219) 结论 failure
- 功能 9：run [539](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36968639782) 结论 failure
- 功能 10：run [540](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36968642455) 结论 failure
- 功能 1：run [542](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36968930679) 结论 failure
- 功能 2：run [543](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36968933514) 结论 failure
- 功能 3：run [544](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36968977236) 结论 failure
- 功能 4：run [545](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36968979906) 结论 failure
- 功能 5：run [546](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36968983099) 结论 failure
- 功能 6：run [547](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36968986290) 结论 failure
- 功能 7：run [548](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36968989606) 结论 failure
- 功能 8：run [549](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36968997567) 结论 failure
- 功能 9：run [550](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36969002807) 结论 failure
- 功能 10：run [551](https://github.com/xf8410/hualuo-repo-tool/actions/runs/36969008985) 结论 failure

### 常规自检包（样例，最新 5 个；全量见 Releases 页）

- run [691](https://github.com/xf8410/hualuo-repo-tool/releases/tag/v0.6.1-r691-src61571d7) · `v0.6.1-r691-src61571d7` · 2026-10-08
- run [689](https://github.com/xf8410/hualuo-repo-tool/releases/tag/v0.6.1-r689-src5507937) · `v0.6.1-r689-src5507937` · 2026-10-08
- run [688](https://github.com/xf8410/hualuo-repo-tool/releases/tag/v0.6.1-r688-src7b72fa6) · `v0.6.1-r688-src7b72fa6` · 2026-10-08
- run [687](https://github.com/xf8410/hualuo-repo-tool/releases/tag/v0.6.1-r687-src73664b1) · `v0.6.1-r687-src73664b1` · 2026-10-08
- run [682](https://github.com/xf8410/hualuo-repo-tool/releases/tag/v0.6.1-r682-srce3f570d) · `v0.6.1-r682-srce3f570d` · 2026-10-08

## 四、怎么用

1. 找某个功能的安装包：查第二节表，点 Release 列的 APK 链接直接下。
2. 审计某功能的测试证据：点证据列进 run，下载「构建包」artifact（日志/ 里逐测试 XML，run 309 起有）。
3. 本表过期了：仓根跑 `python3 tools/gen-artifact-ledger.py`，整文件覆盖后随任意 PR 提交。
4. CI 不自动改这份文件（红线一：构建时不改源码）——它的保鲜靠「发功能包后顺手重跑生成器」。

