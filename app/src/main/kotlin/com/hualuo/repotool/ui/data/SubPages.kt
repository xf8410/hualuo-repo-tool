package com.hualuo.repotool.ui.data

import com.hualuo.repotool.ui.model.IconKey
import com.hualuo.repotool.ui.model.SubField
import com.hualuo.repotool.ui.model.SubPage

// 设置页二级字段表：照抄 ui/v13.html 的 SUB 表（原型逐行翻译，演示数据）。
// 真设置项（写进设置文件的那种）在 SettingsCatalog.kt 的 RealSubPages 里，同 key 时真表优先
// （见 subPage()），别把真项往这里搬——两份表同时存在正是「界面上写着能改、实际没接线」的病根。
//
// 网页搜索已经在 RealSubPages 里接真电（面板 + 五家选择 + 密钥 + 实例地址 + 条数），
// 所以这一张演示表里**不再有**它：留着就是同一页两份字段表。
//
// 家规：图标只写 IconKey 键名，图形字符不进源码（闸门 NoEmojiInSourceTest 连注释一起扫，
// 所以下面提到旧写法时用「对勾」「警告三角」「箭头」这些词，不贴字符本身）。
// 原文案里三处用符号当语气的写法改成了文字：
//   - 「工具对勾 视觉对勾」改为「支持工具与视觉」；
//   - 「裁剪时界面出声（警告三角 行）」改为「（警告行）」；
//   - 接力与观测说明里的箭头改为顿号与逗号。
// 意思不变，只是不再拿字符当图形用。

val SubPages: Map<String, SubPage> = mapOf(
    "provider" to SubPage("提供商", listOf(
        SubField.Row("bai2 网关", "OpenAI 兼容 · 已配置 ›"),
        SubField.Row("官方 API", "OpenAI / Anthropic · 已配置 ›"),
        SubField.Row("本地 ollama", "127.0.0.1:11434 · 在线 ›"),
        SubField.Row("＋ 添加提供商", "自定义 OpenAI 兼容端点"),
        SubField.Note("密钥只存本机，明文（拍板 D-10）；导出备份会带上密钥，别外传"),
    )),
    "model" to SubPage("模型", listOf(
        SubField.Switch("（清单来自提供商页已接入模型）", true),
        SubField.Switch("（清单来自提供商页已接入模型）", true),
        SubField.Switch("（清单来自提供商页已接入模型）", true),
        SubField.Switch("（清单来自提供商页已接入模型）", true),
        SubField.Switch("（清单来自提供商页已接入模型）", true),
        SubField.Input("别名（聊天框胶囊显示名）", "留空=模型原名"),
        SubField.Note("上下文上限从模型端探测，探测不到就标灰「未知」，不许手填冒充"),
    )),
    "prompt" to SubPage("系统指令", listOf(
        SubField.Row("家规 · 全局", "结论必须带证据；丢东西要出声 ›"),
        SubField.Row("仓库工 · 会话级", "改动走 workbench 分支，CI 绿了才提 PR ›"),
        SubField.Row("＋ 新建指令"),
        SubField.Note("全局指令每条对话都带；会话级只在指定对话生效"),
    )),
    "gen" to SubPage("生成参数", listOf(
        SubField.Slider("温度", 0.0, 2.0, 0.7, 0.1),
        SubField.Slider("top_p", 0.0, 1.0, 0.95, 0.05),
        SubField.Seg("带多少历史", listOf("20 条", "50 条", "按 token 装满"), 2),
        SubField.Note("历史「按条数」会浪费大上下文模型，默认按 token 装到真值上限"),
    )),
    "trim" to SubPage("历史裁剪", listOf(
        SubField.Seg("策略", listOf("按 token", "按条数"), 0),
        SubField.Input("单回合上限 token", "128000"),
        SubField.Switch("裁剪时界面出声（警告行）", true),
        SubField.Note("这条开关默认锁死，关掉等于允许静默丢历史——红线"),
    )),
    "imagegen" to SubPage("图像生成", listOf(
        SubField.Seg("服务商", listOf("OpenAI", "ComfyUI 本地", "Pollinations 免费"), 0),
        SubField.Seg("尺寸", listOf("1:1", "16:9", "9:16"), 0),
        SubField.Switch("生成结果自动作为附件登记", true),
    )),
    "github" to SubPage("GitHub 工作台", listOf(
        SubField.Row("登录状态", "xf8410 · 令牌有效 ›"),
        SubField.Input("默认仓库", "xf8410/hualuo-repo-tool"),
        SubField.Switch("记忆文件同步到私有仓", true),
        SubField.Row("Actions 监视", "1 个 run 在盯 ›", gotoKey = "ghactions"),
        SubField.Note("写操作只进 workbench/* 分支，main 靠 PR 合"),
    )),
    "courier" to SubPage("文件投递", listOf(
        SubField.Input("目标仓库", "xf8410/hualuo-courier"),
        SubField.Slider("切片大小 MB", 8.0, 64.0, 32.0, 4.0),
        SubField.Switch("上传前显示总大小和卷数，等你确认", true),
        SubField.Note("断了自动续传，按 sha256 对账，不重复传"),
    )),
    "uma" to SubPage("赛马娘工作台", listOf(
        SubField.Sec("内置 SO 连接"),
        SubField.Action(IconKey.ActionPlay, "启动监听与本应用浮窗", "直接读取 127.0.0.1:18765；赛马娘保持前台，不经过浏览器或其他 App"),
        SubField.Action(IconKey.ActionCycle, "自动分析开关", "关键状态变化后复用内置后台生成引擎；最短间隔 20 秒"),
        SubField.Action(IconKey.ActionSparkle, "立即分析", "读取一致快照，并让默认模型使用全部 uma_* 工具分析"),
        SubField.Action(IconKey.ActionRefresh, "立即刷新", "只请求当前 /summary，不调用模型"),
        SubField.Action(IconKey.ActionStop, "停止工作台", "停止后台监听、通信观测并移除游戏浮窗"),
        SubField.Sec("Session 导出"),
        SubField.Head("保存完整 Session ZIP", "输入 Session ID 后由 Android 系统文件选择器选择保存目录和文件名；不会固定写入下载目录。"),
        SubField.BigInput("Session ID（例：1786133409049-22481）"),
        SubField.Button("选择位置并保存", iconKey = IconKey.ActionDownload),
        SubField.Sec("通信协议观测"),
        SubField.Action(IconKey.ActionObserve, "在游戏浮窗中控制", "浮窗底部可直接开始或停止观测。SO 未连接时进入准备状态，游戏启动后自动开启；临时断线后也会自动恢复。"),
        SubField.Sec("怎么读取"),
        SubField.Head("在对话中直接说", "说「读取最近的赛马娘通信端点并按顺序整理」，模型调 uma_protocol_metadata；读业务状态调 uma_get_snapshot、uma_event_observations 等。"),
        SubField.Sec("数据范围"),
        SubField.Head("完整通信数据交给模型", "协议观测返回完整的 path、header、cookie、token、payload 和 hex。本地采集容量由 hlpatch 单独管理。"),
        SubField.Note("红线：一切对游戏只读，不写内存不发包；18767 冻结待拍板"),
    )),
    "datactl" to SubPage("数据控制", listOf(
        SubField.Row("立即导出", "全部对话与附件清单打包成一个文件 ›"),
        SubField.Row("从备份导入", "合并模式，同 id 跳过不覆盖 ›"),
        SubField.Seg("自动备份", listOf("关", "每天", "每周"), 1),
        SubField.Input("备份目录", "Download/hualuo-backups/"),
    )),
    "storage" to SubPage("存储占用", listOf(
        SubField.Row("对话", "1.9 GB ›"),
        SubField.Row("附件", "0.5 GB ›"),
        SubField.Row("缓存（可清）", "0.1 GB · 清理 ›"),
        SubField.Note("备份不加密（拍板 D-10），存放位置自己负责"),
    )),
    "appearance" to SubPage("外观", listOf(
        SubField.Seg("主题", listOf("亮色", "暗色", "跟随系统"), 2),
        SubField.Seg("主色", listOf("蓝", "青", "紫", "橙"), 0),
        SubField.Switch("跟随壁纸动态取色", false),
        SubField.Note("语义色（成功绿/错误红）不随主色变，防花"),
    )),
)