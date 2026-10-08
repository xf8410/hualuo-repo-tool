package com.hualuo.repotool.ui.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hualuo.engine.github.GitHubRun
import com.hualuo.engine.github.normalizeGitHubRepo
import com.hualuo.engine.search.WebSearchResult
import com.hualuo.engine.store.SessionStore
import com.hualuo.repotool.backup.BackupGateway
import com.hualuo.repotool.ui.data.GEN_TEMPERATURE_DEFAULT
import com.hualuo.repotool.ui.data.GEN_TEMPERATURE_KEY
import com.hualuo.repotool.ui.data.GEN_TOP_P_DEFAULT
import com.hualuo.repotool.ui.data.GEN_TOP_P_KEY
import com.hualuo.repotool.ui.data.RETRY_COSTLY_DEFAULT
import com.hualuo.repotool.ui.data.RETRY_COSTLY_KEY
import com.hualuo.repotool.ui.model.Conv
import com.hualuo.repotool.ui.model.NavTab
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.reflect.KProperty

/** 文件投递批里的一条：用户选进来的一个文件或一棵目录树。 */
data class CourierPick(
    /** 屏上显示用的短标签（选文件时是系统选择器给的路径尾段，收集真名在投递时做）。 */

    val label: String,
    /** content URI 的字符串形式。状态层不认识安卓的 Uri 类，转回去是根界面那层的事。 */
    val uri: String,
    /** true = 一棵目录树（OpenDocumentTree 的结果），投递时递归收集。 */
    val isTree: Boolean,
)

/**
 * 全局界面状态（v13.1 的 JS 变量一对一翻译）。
 *
 * 现在的分工：
 *  - 已接真电的字段（tab、input、currentModel、六个工具开关、lockToConversation）走 persist：
 *    初值从设置里读，改了就记一笔，落盘时机由界面攒着 flush。
 *  - 设置页里新加的开关走 flag 与 setFlag：键名由数据表带过来，不占字段位。
 *  - 设置页里的真文本走 text 与 setText：同样按键名，落盘按 settingsRevision 去抖。
 *  - 回合流真消息住 chat（ChatRuntime）：发送走真网络，busy 也从它读，不再单独一个演示布尔；
 *    历史上限与系统指令同刀接进（砍数出声、system 排最前）。
 *  - **会话库（M2 接线）**：store 不为 null 时，启动**同步**接上最近一次会话（没有异步首读，
 *    白屏和「多进几次才出来」没有土壤）、抽屉列表来自真库、新建/删除都动真文件；
 *    store 为 null（纯 JVM 测试、或会话库没建成）时一切照演示版走，行为不变。
 *  - **GitHub 登录（2026-09-22 补）**：整体住 [githubLogin]（[GithubLoginState]，进程级）；
 *    拿令牌验证 /user 并记登录态与权限；令牌一改登录当场作废（防撒谎态）。
 *  - **仓库工作台（浏览 + 改码）**：整体住 [repo]（[RepoWorkbenchState] 状态舱，
 *    999 行红线拆出来的：清单/浏览/分支/提交历史/文件预览与改码提交）。
 *  - **仓库CI（只读）**：runs 与最新发布版现场拉，失败/坏条目出声不冒充；
 *    瞬时态住 [ci]（[RepoCiState]），仓库与令牌在设置「GitHub 工作台」里配，令牌只进请求头。
 *  - **工具页真电（网页搜索）**：瞬时态住 [webSearchRun]，用哪家由 [webSearch] 设置舱
 *    现读（默认免费档 DuckDuckGo，也可换 Brave/Serper/Tavily/SearXNG）；真结果、失败出声不冒充。
 *  - **长任务页真电（文件投递）**：选文件/选目录只发动作请求（[pendingDataAction] 桥上走），
 *    收集与分卷投递在根界面的后台线程（CourierDelivery）；目标仓/分支/令牌在设置「文件投递」。
 *  - **工具族（0.7.0 刀②）**：GitHub 读类十件注册进 [chat]（列仓、看别人的仓、浏览目录、
 *    读文件、搜代码、分支、提交历史、CI 三层）；令牌与默认仓库在执行那一刻从设置现场读
 *    （[buildGithubToolRegistry]），改了下一句生效。
 *  - **写类闸门（0.7.0 刀③）**：[writeGate] 非空才注册 github_update_file（不给闸门就不存在
 *    这个工具，默认拒写）；模型提议先摆确认卡，用户点头才真走 PUT。
 *  - **备份（数据控制）**：按钮只发出动作请求（[pendingDataAction]），系统文件选择器在
 *    RootScreen 那层开；导入的设置**必须**经 [applyImportedBackup] / [applyAgoraImport]
 *    走活通道进——绕过活通道直接写文件，会被下一次 flush 用旧值盖掉（两份事实的老病）。
 *  - 传 UiPersistence.None（默认）时行为与接线前逐字一致，纯 JVM 测试就这么跑。
 *
 * 键名进过真机就不许改（改了老设置读不到），清单在 UiKeys、SettingsCatalog 与 ChatRuntime。
 * 委托一律和声明写在同一行：属性声明在语法上本身就是完整的，把 by 挪到下一行有被当成分句结束的风险，不赌。
 */
class AppUiState(
    private val persist: UiPersistence = UiPersistence.None,
    private val store: SessionStore? = null,
    /** 模型设置句柄（聊天空态检测用）：null=纯 JVM 测试。 */
    val modelSettings: ModelSettingsState? = null,
    /** 写类工具的确认闸门；null = 不注册写工具（默认拒写的另一半）。 */
    private val writeGate: WriteConfirmGate? = null,
    /** PR 工具（建/合）的确认闸门；null = 不注册 PR 两件（默认拒）。 */
    private val prGate: PrConfirmGate? = null,
    /** GitHub 动作族（建分支/删分支/建 issue/评论/关 PR/评论 PR）的确认闸门；
     *  null = 六件全部不注册（默认拒）。 */
    private val actionGate: GitHubActionGate? = null,
    /** 沙盒确认闸（终端页实装刀）：null=不注册沙盒族（默认拒跑命令的另一半）。 */
    private val sandboxGate: SandboxGate? = null,
    /** 沙盒根目录（rootfs/work/shared 都在这下面）；null=不给沙盒工具族落盘位。 */
    private val sandboxRootDir: java.io.File? = null,
    /** 记忆库；null = 不注册记忆工具族（同闸门纪律）。 */
    private val memoryStore: com.hualuo.engine.memory.MemoryStore? = null,
    /** 技能库；null = 不注册技能工具族、系统提示词不拼技能目录。 */
    private val skillStore: com.hualuo.engine.memory.MemoryStore? = null,
    /** 图像生成配置读取（钥匙没配返回不可用配置）；null = 不注册图像生成。 */
    private val imageGenConfig: (() -> com.hualuo.engine.toolcalls.ImageGenConfig?)? = null,
    /** 图像字节落盘（字节、文件名前缀）到保存路径；null = 不注册图像生成。 */
    private val imageGenPersist: ((ByteArray, String) -> String)? = null,
    /** 看视频的库目录（录屏本体+manifest）；null = 不注册看视频工具族。 */
    val watchInboxDir: File? = null,
    /** 帧缓存目录（导入时抽好的 JPEG）。 */
    val watchFramesDir: File? = null,
) {

    // ── 已接持久化 ──────────────────────────────────────────────────────────

    /** SO 观测桥状态舱（560 清单 361-400 域；状态机在引擎件，这里只存收场）。
     *  放在 toolRegistry 构造调用之前：构造时 observeClientProvider 闭包要捕获它。 */
    val observe = ObserveUiState(persist)

    /** 查看器状态舱（全语言/进制/全格式上传；流式分块，红线三拆件）。 */
    val viewer = ViewerUiState(persist)

    /**
     * 网页搜索设置舱：选哪家、那家的密钥、自托管实例地址、默认条数。
     *
     * 取值存值都走本类的 text/setText（界面那条活通道），所以自动落盘与重组天然跟着走；
     * 执行那一刻由它现读现拼配置（[WebSearchState.config]），设置改完下一句就生效。
     */
    val webSearch = WebSearchState(
        load = { key -> text(key) },
        save = { key, value -> setText(key, value) },
    )

    /** 工具页那张卡的瞬时态（搜索词/忙灯/结果/收场话）；用哪家现场问 [webSearch]。 */
    /** 报告三件套（报告页实装刀）：数据分析/汇报文档/PPT 大纲——真 AI 生成，瞬时态。 */
    val reports = ReportRunState(
        toast = ::toast,
        currentModel = { currentModel },
        sessionFor = { id -> ModelSettingsRuntime.current()?.sessionFor(id) },
    )

    val webSearchRun = WebSearchRunState(
        configProvider = { webSearch.config() },
        providerLabel = { webSearch.providerLabel() },
        toast = { msg -> toast(msg) },
    )

    /** 仓库CI瞬时态（runs/忙灯/错误/更新检查结论）；仓库与令牌现读设置。 */
    val ci = RepoCiState(
        loadToken = { githubToken() },
        loadRepo = { defaultGitHubRepo() },
        versionLabel = { versionLabel },
    )

    /** 底栏停在第几页。存枚举名，读不懂就回回合流页。 */
    var tab: NavTab by saved(UiKeys.TAB, readTab(), { it.name })

    /** 输入框草稿（进程被杀、切去别的 App 再回来，不该丢字）。 */
    var input: String by saved(UiKeys.DRAFT, persist.load(UiKeys.DRAFT) ?: "", { it })

    /** 当前模型。空串=还没选（首次启动不再硬塞 DEFAULT_MODEL——没配钥匙的默认是摆设：
     *  每次请求都会失败。未选态在发送口明示拦下并指路）。 */
    var currentModel: String by saved(UiKeys.MODEL, persist.load(UiKeys.MODEL) ?: "", { it })

    var thinkOn: Boolean by saved(UiKeys.THINK_ON, readBool(UiKeys.THINK_ON, true), { it.toString() })

    var thinkLevel: Int by saved(UiKeys.THINK_LEVEL, readInt(UiKeys.THINK_LEVEL, 2), { it.toString() })

    var webSearchOn: Boolean by saved(UiKeys.WEB_SEARCH_ON, readBool(UiKeys.WEB_SEARCH_ON, true), { it.toString() })

    var shellOn: Boolean by saved(UiKeys.SHELL_ON, readBool(UiKeys.SHELL_ON, false), { it.toString() })

    var codeExecOn: Boolean by saved(UiKeys.CODE_EXEC_ON, readBool(UiKeys.CODE_EXEC_ON, false), { it.toString() })

    var relayOn: Boolean by saved(UiKeys.RELAY_ON, readBool(UiKeys.RELAY_ON, false), { it.toString() })

    var lockToConversation: Boolean by saved(
        UiKeys.LOCK_TO_CONVERSATION,
        readBool(UiKeys.LOCK_TO_CONVERSATION, false),
        { it.toString() },
    )

    /** 把攒着的改动落盘。返回 null 表示没问题；返回字符串是失败原因，界面必须 toast 出来。 */
    fun flushPersistence(): String? = persist.flush()

    /** 取走设置层累计的坏消息（非法值、半个表情等），取走即清空。 */
    fun persistenceMessages(): List<String> = persist.drainMessages()

    // ── 设置页的真开关（按键名，不占字段位） ────────────────────────────────

    /**
     * 本次界面里被改过的开关。只由 setFlag 填，读的时候不写：
     * getOrPut 会在组合期间写状态，那是重组抖动的常见来源，别给后面的人埋。
     */
    private val flagOverrides = mutableStateMapOf<String, Boolean>()

    /** 读一个真开关：本次改过的优先，其次设置文件，最后调用方给的默认值。 */
    fun flag(key: String, defaultOn: Boolean = false): Boolean =
        flagOverrides[key] ?: readBool(key, defaultOn)

    /** 改一个真开关：既更新界面状态（会重组），又记进设置等落盘。 */
    fun setFlag(key: String, value: Boolean) {
        flagOverrides[key] = value
        persist.save(key, value.toString())
        // 崩溃留档开关（关于页）：当场喂内存值——崩溃回调里只读内存，不碰盘
        if (key == UiKeys.CRASH_KEEP_LOCAL) com.hualuo.repotool.CrashObserver.keepLocal = value
    }

    // ── 设置页的真文本（按键名；提供商地址密钥这类） ────────────────────────

    /**
     * 本次改过、还没落盘的文本：输入框每敲一下只写内存，
     * 真落盘由界面按 [settingsRevision] 攒着去抖——「一个字写一次盘」是绝对不许的。
     */
    private val textOverrides = mutableStateMapOf<String, String>()

    /**
     * 每次 setText 给这个版本号加一：自动保存的看护方只看这一个数，
     * 不逐个盯每张输入框——以后文本键增减，看护点也不用跟着改。
     */
    var settingsRevision by mutableStateOf(0L)
        private set

    /** 读一个真文本设置：本次改过的优先，其次设置文件，最后调用方给的默认值。 */
    fun text(key: String, default: String = ""): String =
        textOverrides[key] ?: persist.load(key) ?: default

    /**
     * 改一个真文本：界面立刻更新、内存记一笔（不碰盘），修订号推进等去抖落盘。
     *
     * 特殊关照：改的是 github.token 时，把 [githubLogin] 里那次验证**当场作废**——
     * 防「屏上还写着已登录为旧身份、手里其实已经换了新钥匙」的撒谎态
     * （用户实报这条时点名的关切；作废是幂等的，没登录过就什么都不做）。
     */
    fun setText(key: String, value: String) {
        if (text(key) == value) return
        textOverrides[key] = value
        persist.save(key, value)
        settingsRevision += 1
        if (key == UiKeys.GITHUB_TOKEN) {
            githubLogin.invalidate()
        }
    }

    // ── GitHub 登录（2026-09-22 补） ────────────────────────────────────────

    /**
     * GitHub 登录状态舱：令牌验证（/user）、登录名与权限清单、退出登录。
     * 挂进程（本状态层由应用单例持有），转屏/切出不丢。通知出口与修订号与其它件同源。
     */
    val githubLogin = GithubLoginState(
        persist = persist,
        toast = { msg -> toast(msg) },
        bumpRevision = { settingsRevision += 1 },
    )

    // ── 回合流真运行层 ──────────────────────────────────────────────────────

    /**
     * 真说过的话与生成槽都住这里（契约见 ChatRuntime），会话仓与上下文喂养同刀接进；
     * 工具族（0.7.0 刀②③）同刀接进：GitHub 读类十件直进，写类一件只有 [writeGate]
     * 非空才注册（默认拒写）。三个真开关全部具名传：尾随 lambda 会绑到 ChatRuntime 的
     * 最后一个参数（clock），拿开关去尾随就是拿 Boolean 冒充 Long——CI 编译段抓到过，别再犯。
     */
    /** 会话循环调度器（loop 页实装刀）：进程级，onSettled 驱动。 */
    val loopCtl = LoopController(state = { this })

    val chat = ChatRuntime(
        persist,
        autoRetryCostly = { flag(RETRY_COSTLY_KEY, RETRY_COSTLY_DEFAULT) },
        store = store,
        maxHistoryTurns = { readInt(UiKeys.MAX_HISTORY, ChatRuntime.MAX_HISTORY_TURNS).coerceIn(1, 500) },
        temperature = { readDouble(GEN_TEMPERATURE_KEY, GEN_TEMPERATURE_DEFAULT)?.coerceIn(0.0, 2.0) },
        topP = { readDouble(GEN_TOP_P_KEY, GEN_TOP_P_DEFAULT)?.coerceIn(0.0, 1.0) },
        systemPrompt = {
            val base = persist.load(ChatRuntime.KEY_SYSTEM_PROMPT)?.trim().orEmpty()
            // 技能目录拼在系统提示词后（对齐旧仓 GenerationRequestBuilder 的 available_skills
            // 注入）：只报名字+描述，正文模型按需 read；空库不占一个字
            val catalog = skillStore?.let { com.hualuo.engine.toolcalls.SkillTool.catalog(it) }.orEmpty()
            when {
                catalog.isEmpty() -> base
                base.isEmpty() -> catalog
                else -> base + "\n\n" + catalog
            }.let { withCatalog ->
                // 语言页「AI 回复跟随界面语言」：真进系统指令（默认开——中文用户装完即对味）
                if (persist.load(UiKeys.LANG_AI_REPLY)?.equals("false") == true) withCatalog
                else if (withCatalog.isEmpty()) "请用简体中文回复。" else withCatalog + "\n请用简体中文回复。"
            }
        },
        toolRegistry = buildGithubToolRegistry(
            persist,
            writeGate,
            prConfirmer = prGate,
            actionConfirmer = actionGate,
            // 终端页开关真生效（SHELL_ENABLED 默认关——沙盒族五件开着就要装 rootfs，
            // 用户明示打开才注册；闸门与目录都到位才真接）
            sandboxConfirmer = if (persist.load(UiKeys.SHELL_ENABLED)?.equals("true") == true) sandboxGate else null,
            sandboxRootDir = if (persist.load(UiKeys.SHELL_ENABLED)?.equals("true") == true) sandboxRootDir else null,
            memoryStore = memoryStore,
            sessionStore = store,
            webSearchEnabled = { webSearchOn },
            webSearchConfig = { webSearch.config() },
            skillStore = skillStore,
            imageGenConfig = imageGenConfig,
            imageGenPersist = imageGenPersist,
            videoUrlSession = { ModelSettingsRuntime.current()?.sessionFor(currentModel) },
            watchInboxDir = watchInboxDir,
            watchFramesDir = watchFramesDir,
            visionSession = ::visionSessionOrDefault,
            observeClientProvider = {
                com.hualuo.engine.observe.ObserveClient(
                    observe.baseUrl,
                    com.hualuo.engine.api.UrlConnTransport(),
                )
            },
            observeLink = observe.link,
        ),
        // 会话循环（loop 页实装刀）：生成落定回调里驱动 LoopController（含按停语义）。
        // 注意这里读的是局部 ui 引用而不是 chat 属性本身——onSettled 在 chat 构造参数里，
        // 直接引用 chat 会造成构造期递归类型解析（CI 逮过：recursive problem + unresolved sessionId）。
        onSettled = { cancelled -> loopCtl.onSettled(cancelled) },
    )

    /**
     * 看视频的「眼睛」会话：设置里填的眼睛模型 id（provider:model）。
     * 没填/解析不出 = null（工具两件不注册）。与主对话用什么模型无关——
     * 纯文本主模型调 watch_video，眼睛读帧出文字给它。
     */
    private fun visionSessionOrDefault(): com.hualuo.engine.api.ProviderSession? {
        val id = persist.load(UiKeys.VISION_MODEL)?.trim().orEmpty()
        if (id.isEmpty()) return null
        return ModelSettingsRuntime.current()?.sessionFor(id)
    }

    /** 输入区发送钮的忙灯：真在跑才亮，不再是个能手动点着玩的演示布尔。 */
    val busy: Boolean get() = chat.busy

    // ── 仓库工作台（状态舱在 RepoWorkbenchState：浏览/分支/历史/改码） ──────

    /**
     * 仓库工作台的状态舱（999 行红线拆出来的）：只吃两样外界——
     * 设置里的 GitHub 令牌与这台 toast 喇叭，其余全部自持。
     */
    val repo = RepoWorkbenchState(
        loadToken = { persist.load(UiKeys.GITHUB_TOKEN)?.trim()?.takeIf { it.isNotEmpty() } },
        toast = { msg -> toast(msg) },
    )

    /**
     * 发送钮的统一入口（Composer 只管叫，规矩收在状态层一处，纯 JVM 可测）：
     *  - 空草稿不空发，出声说明；
     *  - 超过 [MAX_PROMPT_CHARS] 字符拒发——单条超大粘贴是把上下文窗口顶爆的最快方式。
     *    这道闸按字符管「单条」，历史护栏按条数管「总量」，各补各的盲区；
     *  - 过了闸才交 [chat.send]；**砍了历史必须当场出声**（家规：砍数上屏）；
     *  - 发出去草稿清空（清动作本身也记设置文件，防重开冒草稿）。
     */
    fun sendCurrentInput() {
        val text = input
        if (text.isBlank()) {
            toast("没内容可发")
            return
        }
        if (text.length > MAX_PROMPT_CHARS) {
            toast("这条 ${text.length} 字，超了单条上限 $MAX_PROMPT_CHARS：拆开发送或先精简，别拿大粘贴赌对方的窗口")
            return
        }
        if (currentModel.isBlank()) {
            toast("还没选模型（点输入栏上方的模型徽标，或设置里配一家提供商）——字先留在草稿里不丢")
            return
        }
        chat.send(text, currentModel)
        if (chat.lastTrimmed > 0) {
            toast("上下文装不下：砍了 ${chat.lastTrimmed} 条旧话才发（上限在「历史裁剪」里调）")
        }
        // 标题生成页的开关真生效：还没名字的会话，拿首条消息前 20 字起名（规则起名，离线可用；
        // 模型起名差一轮请求接线，接上后这条升级成模型起名）
        autoTitleFromFirstMessage(text)
        input = ""
    }

    /** 首条消息自动起名（标题生成页实装刀）：开关开 + 会话还没标题才动；写库 + 刷列表。 */
    private fun autoTitleFromFirstMessage(firstMessage: String) {
        if (persist.load(UiKeys.TITLE_AUTO)?.equals("false") == true) return
        val s = store ?: return
        val id = chat.sessionId ?: return
        val conv = convs.firstOrNull { it.id == id } ?: return
        if (conv.title != "（未命名）") return
        val title = firstMessage.trim().replace("\\s+".toRegex(), " ").take(20)
        if (title.isBlank()) return
        runCatching { s.rename(id, title) }.onSuccess {
            convs = convs.map { if (it.id == id) it.copy(title = title) else it }
        }
    }

    // ── 工具页真电：网页搜索（瞬时态在 [webSearchRun]，用哪家由 [webSearch] 现读） ──

    /** 工具页搜索框里的词（委托给瞬时态件，界面读写的还是这个字段名）。 */
    var searchQuery: String
        get() = webSearchRun.query
        set(value) {
            webSearchRun.query = value
        }

    /** 正在搜：按钮与提示行都看它。 */
    val searchBusy: Boolean get() = webSearchRun.busy

    /** 最近一次的搜索结果（真数据，引擎件清净过）。 */
    val searchResults: List<WebSearchResult> get() = webSearchRun.results

    /** 最近一次搜索的收场话（成功报条数与走了哪家，失败给理由）；null = 还没搜过。 */
    val searchNote: String? get() = webSearchRun.note

    /**
     * 工具页「搜一下」：真网络、真结果、失败出声不冒充（家规）。
     * 收场一律写回 [searchResults] 与 [searchNote]，成功的旧结果不偷偷留着顶数——
     * 失败就明示失败。用哪家现问 [webSearch]：换一家、填密钥、填实例地址，下一句就生效。
     */
    fun runWebSearch() = webSearchRun.run()

    /** 视频库状态舱（编排细节在 VideoUnderstandingState，红线三拆件）。 */
    val video = VideoUnderstandingState(watchInboxDir = watchInboxDir)

    // ── APK 检查（560 清单 121-160 域；引擎全测，这里只存文本收场） ──

    var apkChecking by mutableStateOf(false)
    var apkReport by mutableStateOf<String?>(null)

    // ── 仓库CI（只读；瞬时态在 [ci]，界面层字段名保持原样） ────────────────

    val ciBusy: Boolean get() = ci.busy
    val ciRuns: List<GitHubRun> get() = ci.runs
    val ciBadEntries: Int get() = ci.badEntries
    val ciError: String? get() = ci.error
    val ciRepoLabel: String get() = ci.repoLabel
    val updateNote: String? get() = ci.updateNote

    /** 进页时才拉；已有数据或正在拉就不重复。 */
    fun refreshRepoCiIfStale() = ci.refreshIfStale()

    fun refreshRepoCi() = ci.refresh()

    fun checkUpdate() = ci.checkUpdate()

    private fun githubToken(): String? =
        persist.load(UiKeys.GITHUB_TOKEN)?.trim()?.takeIf { it.isNotEmpty() }

    private fun defaultGitHubRepo(): String =
        persist.load(UiKeys.GITHUB_REPO)?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_GITHUB_REPO

    // ── 长任务页真电：文件投递（courier；SAF 与投递执行在 RootScreen/CourierDelivery） ──

    /**
     * 已选进投递批的条目。只活在本进程、不落盘：SAF 授权跟着进程走，进程死了
     * 重开一遍才能靠得住，把 URI 装进设置文件是假安心。想清空点「清空已选」。
     */
    var courierPicks by mutableStateOf(emptyList<CourierPick>())
        private set

    /** 投递是否在跑：按钮看它禁点，桥上看它拒绝重复发车。 */
    var courierBusy by mutableStateOf(false)
        private set

    /** 最近一次投递的收场话（含收集报告与落点）；null = 本进程还没投过。 */
    var courierNote by mutableStateOf<String?>(null)
        private set

    /** 根界面把选择器的结果交进来（一次选定的可以是一个或多个）。 */
    fun addCourierPicks(picks: List<CourierPick>) {
        if (picks.isEmpty()) return
        courierPicks = courierPicks + picks
    }

    /** 清空已选批（不影响仓里已经投出去的卷）。 */
    fun clearCourierPicks() {
        courierPicks = emptyList()
    }

    /**
     * 长任务页「选文件」「选目录」：只发动作请求，系统选择器走跟备份同款桥
     * （纯 JVM 状态层不认识 ActivityResult）。
     */
    fun requestCourierPick(tree: Boolean) {
        if (courierBusy) {
            toast("正在投递，先等这批跑完")
            return
        }
        pendingDataAction = if (tree) ACTION_COURIER_PICK_TREE else ACTION_COURIER_PICK_FILES
    }

    /** 目标仓：设置「文件投递」里配，粘整条仓库链接也认（与仓库CI 同一套 normalize）。没配对返回 null。 */
    fun courierRepo(): String? = normalizeGitHubRepo(text(UiKeys.COURIER_REPO).trim())

    /** 目标分支：没配按 main 走（投自家仓的默认分支是最常见的一格省略）。 */
    fun courierBranch(): String = text(UiKeys.COURIER_BRANCH).trim().ifBlank { "main" }

    /** 令牌：文件投递那格留空就借用「GitHub 工作台」的令牌（同一把钥匙不逼人填两遍）。 */
    fun courierToken(): String =
        text(UiKeys.COURIER_TOKEN).trim().ifBlank { text(UiKeys.GITHUB_TOKEN).trim() }

    /**
     * 长任务页「开始投递」：先过三道闸（批里有货、仓写法对、令牌在手），过了才发动作请求——
     * 真正的收集与分卷投递在 RootScreen 的后台线程（状态层不认识 ContentResolver）。
     * 缺哪道闸就指名道姓出声，绝不空发请求去撞网络。
     */
    fun requestCourierDeliver() {
        if (courierBusy) return
        if (courierPicks.isEmpty()) {
            toast("还没选要投的东西：先点「选文件」或「选目录」")
            return
        }
        if (courierRepo() == null) {
            toast("目标仓库没配对：去设置「文件投递」填 owner/name（粘整条仓库链接也认）")
            return
        }
        if (courierToken().isEmpty()) {
            toast("令牌不在手：私有仓库投递要令牌，去设置「文件投递」或「GitHub 工作台」填")
            return
        }
        pendingDataAction = ACTION_COURIER_DELIVER
    }

    /** 投递发车（接线层在后台线程里叫）：忙灯亮、旧收场话清掉——旧账不许挂着顶数。 */
    fun beginCourier() {
        courierBusy = true
        courierNote = null
    }

    /** 投递收场（接线层叫）：忙灯灭、结论入账。成功失败都走这里，不许静默。 */
    fun finishCourier(note: String) {
        courierBusy = false
        courierNote = note
    }

    // ── 备份（数据控制；动作桥接与文件选择器在 RootScreen） ─────────────────

    /**
     * 数据控制页与文件投递共用的动作桥（export/import/import_agora、courier_pick_files、
     * courier_pick_tree、courier_deliver）。设置子页的按钮只发请求，
     * 系统文件选择器（SAF）归 RootScreen 开——纯 JVM 状态层不认识安卓的 ActivityResult。
     */
    var pendingDataAction by mutableStateOf<String?>(null)
        private set

    fun requestDataAction(key: String) {
        pendingDataAction = key
    }

    fun clearPendingDataAction() {
        pendingDataAction = null
    }

    /**
     * 把导入的本家备份应用进**活通道**：设置逐键 save（活通道是唯一事实，绕过它直接写文件
     * 会被下一次 flush 用旧值盖掉）；会话已由网关落盘，这里只把账报出来。
     * 返回一句话给 toast；修订号推一格 + 立即落盘，界面与盘上同时吃到新值。
     *
     * 属性解析必须喂 StringReader：Properties.load(InputStream) 按 ISO-8859-1 解码，
     * 中文值全会变乱码——CI 测试段抓过（run 35099409305），别改回字节流。
     *
     * 导入完成后 [githubLogin] 从设置里重读登录态（备份里带就接上，没带就回到未登录）——
     * 绝不拿旧内存值冒充导入结果。
     */
    fun applyImportedBackup(backup: BackupGateway.ImportedBackup): String {
        if (!backup.formatOk) {
            return buildString {
                append("这不是本应用导出的备份包（格式或版本对不上）：没有导入任何东西")
                if (backup.warnings.isNotEmpty()) append("；").append(backup.warnings.joinToString("；"))
            }
        }
        var applied = 0
        val settingsText = backup.settingsText ?: ""
        if (settingsText.isNotEmpty()) {
            val props = java.util.Properties()
            val loadFailure = runCatching { props.load(java.io.StringReader(settingsText)) }
                .exceptionOrNull()
            if (loadFailure != null) {
                // 设置坏了不挡会话：会话账必须照样报全
                return "备份里的设置读不懂（${loadFailure.message ?: "格式不对"}）：" +
                    "设置没动、会话 ${backup.sessionsImported} 份已入库" +
                    backup.warnings.joinToString("；", prefix = "；")
            }
            for (name in props.stringPropertyNames()) {
                persist.save(name, props.getProperty(name))
                applied += 1
            }
        }
        githubLogin.reloadFromSettings()
        settingsRevision += 1
        val issues = persistenceMessages()
        val flushFailure = flushPersistence()
        return buildString {
            append("导入完成：设置 $applied 项、会话 ${backup.sessionsImported} 份")
            if (backup.sessionsSkipped > 0) append("、重名会话跳过 ${backup.sessionsSkipped} 份（原来的保留）")
            if (backup.warnings.isNotEmpty()) append("；").append(backup.warnings.joinToString("；"))
            if (issues.isNotEmpty()) append("；设置提示：").append(issues.joinToString("；"))
            if (flushFailure != null) append("；设置没存上：").append(flushFailure)
        }
    }

    /**
     * 旧 Agora 包（.agora）的应用：会话已由网关落盘（agora- 前缀、重名跳过），
     * 这里把兑换单里的设置逐键送进**活通道**，带不动的账（媒体/任务/模板变量/解不开的旧密文）
     * 原样报出来。返回一句话给 toast；修订号推一格 + 立即落盘。
     */
    fun applyAgoraImport(outcome: BackupGateway.AgoraImportOutcome): String {
        if (!outcome.recognized || outcome.plan == null) {
            return outcome.error ?: "旧备份没认出来：没有导入任何东西"
        }
        val plan = outcome.plan
        var applied = 0
        fun put(key: String, value: String?) {
            if (value != null) {
                persist.save(key, value)
                applied += 1
            }
        }
        put(ChatRuntime.KEY_NAME, plan.providerName)
        put(ChatRuntime.KEY_BASE_URL, plan.baseUrl)
        put(ChatRuntime.KEY_API_KEY, plan.apiKey)
        put(UiKeys.MODEL, plan.selectedModel)
        put(ChatRuntime.KEY_SYSTEM_PROMPT, plan.systemPrompt)
        plan.thinkingOn?.let { put(UiKeys.THINK_ON, it.toString()) }
        plan.thinkingLevel?.let { put(UiKeys.THINK_LEVEL, it.toString()) }
        plan.codeExecOn?.let { put(UiKeys.CODE_EXEC_ON, it.toString()) }
        plan.webSearchOn?.let { put(UiKeys.WEB_SEARCH_ON, it.toString()) }
        // 网页搜索四格（提供商 / 各家密钥 / 自托管实例地址）同走活通道，旧包没带的不写
        applied += applyWebSearchFromAgora(persist, plan)
        plan.shellOn?.let { put(UiKeys.SHELL_ON, it.toString()) }
        settingsRevision += 1
        val issues = persistenceMessages()
        val flushFailure = flushPersistence()
        return buildString {
            append("旧 Agora 备份导入完成：设置 $applied 项、会话 ${outcome.sessionsImported} 份")
            if (outcome.sessionsSkipped > 0) append("、重名会话跳过 ${outcome.sessionsSkipped} 份（原来的保留）")
            if (plan.notes.isNotEmpty()) append("；").append(plan.notes.joinToString("；"))
            if (issues.isNotEmpty()) append("；设置提示：").append(issues.joinToString("；"))
            if (flushFailure != null) append("；设置没存上：").append(flushFailure)
        }
    }

    // ── 仍是演示态的字段 ────────────────────────────────────────────────────

    /** 版本串由入口注入（BuildConfig 读自 version.properties 单源），界面里不许写死。 */
    var versionLabel by mutableStateOf("")

    /** 沙盒根目录路径（沙盒状态卡真探用）：没给就是空串，卡上明示未接。 */
    val sandboxRootPath: String get() = sandboxRootDir?.absolutePath ?: ""

    /** CI 最近 run（设置页监视卡用）：后台线程调用（内部真联网，别在主线程碰）。
     *  仓与 token 按既有规则读设置；本仓 Actions 只有 run 没有的字段，界面拿什么显示什么。 */
    fun latestCiRuns(limit: Int = 5): com.hualuo.engine.github.GitHubCiSnapshot =
        com.hualuo.engine.github.GitHubCiClient().latestRuns(defaultGitHubRepo(), githubToken(), limit)

    /** 记忆库只读口（设置页记忆账卡用）：没有库（未建/降级）返回 null。 */
    fun memoryFiles(): List<com.hualuo.engine.memory.MemoryStore.MemoryFileInfo>? = memoryStore?.listFiles()

    /** 活动记忆原文（进每次生成的上下文那份）；无库或未写返回空串。 */
    fun activeMemory(): String = memoryStore?.readActiveMemory().orEmpty()

    /** 删一条记忆（设置页记忆账卡，deleteFile 连 meta 一起清）。结果给人话：成功=null，失败=原因。 */
    fun deleteMemory(name: String): String? =
        memoryStore?.let { store ->
            runCatching { store.deleteFile(name) }.fold(
                onSuccess = { null },
                onFailure = { it.message ?: "删除失败（原因不明）" },
            )
        } ?: "记忆库没建起来（本机降级态），删不了"

    /** 有没有一家能用的模型（聊天空态的引导依据）：提供商配好且至少一个非自定义可用。 */
    fun anyModelReady(): Boolean = modelSettings
        ?.settings?.providers?.any { modelSettings.isConfigured(it.id) } == true

    // 抽屉（store 接上后这里是真库列表；没接库才落回演示数据）
    var drawerOpen by mutableStateOf(false)
    var convQuery by mutableStateOf("")
    var selecting by mutableStateOf(false)
    var selectedIds by mutableStateOf(setOf<String>())
    var convs by mutableStateOf(emptyList<Conv>())  // 空表起步（演示数据已撤）：真库接上后 init 里填
    var confirmOpen by mutableStateOf(false)
    var confirmText by mutableStateOf("")
    var confirmAction: (() -> Unit)? = null

    // 输入区的瞬时态（不该持久化）
    var micOn by mutableStateOf(false)
    var addMenuOpen by mutableStateOf(false)
    /** 循环条可见性：跟 LoopController.enabled 走（开着才显示）。 */
    var loopBarOn by mutableStateOf(false)
    /** 队列条：排队机制未实装，默认不摆（留着位置，实装后开）。 */
    var queueBarOn by mutableStateOf(false)
    var thumbs by mutableStateOf(emptyList<String>())  // 输入条缩略位空置（无功能不占位）

    // 原位弹层（模型、工具、任务详情互斥，同原型 closeAll）
    var modelSheetOpen by mutableStateOf(false)
    var toolSheetOpen by mutableStateOf(false)
    var taskSheetKey by mutableStateOf<String?>(null)
    var modelQuery by mutableStateOf("")

    // 设置
    var settingsOpen by mutableStateOf(false)
    var settingsQuery by mutableStateOf("")

    /** 子页栈：空表示停在设置主页；栈顶是当前二级页 key（对应原型 SUBSTACK）。 */
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

    fun addThumb(icon: String) {
        thumbs = thumbs + icon
    }

    fun removeThumb(index: Int) {
        thumbs = thumbs.filterIndexed { i, _ -> i != index }
    }

    fun newConversation() {
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

    /** 图像转述（caption 页实装刀）：瞬时态；失败明示，不留旧文顶数。 */
    var captionBusy by mutableStateOf(false)
        private set
    var captionResult by mutableStateOf<String?>(null)
        private set
    var captionError by mutableStateOf<String?>(null)
        private set

    /** 真调视觉模型转述一张图（caption 页实装刀）。
     *  兼容性护栏（本轮主题）：①无视觉模型就明示不可用不发起；②读图/编码/请求全 runCatching，
     *  任何失败落到 captionError 给人话；③超 4MB 图直接拒（内存与请求体双保护）。
     *  不进会话库不占会话列表——工具产物一次性查看。 */
    fun runCaption(bytes: ByteArray, mime: String) {
        if (captionBusy) return
        if (bytes.isEmpty()) {
            captionError = "图是空的"
            captionResult = null
            return
        }
        if (bytes.size > 4 * 1024 * 1024) {
            captionError = "图超过 4MB（${bytes.size / 1024} KiB）：先压缩再转述（内存与请求体双保护）"
            captionResult = null
            return
        }
        val session = visionSessionOrDefault()
        if (session == null) {
            captionError = "视觉模型没配（设置里「视觉模型」填 provider:id），配好再来"
            captionResult = null
            return
        }
        captionBusy = true
        captionError = null
        captionResult = null
        Thread({
            val outcome = runCatching {
                val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                val prompt = persist.load(UiKeys.CAPTION_PROMPT)?.trim().orEmpty()
                    .ifBlank { "把这张图的内容完整转述成文字：说清图里的文字、数字、界面布局与显著物体。" }
                when (val r = com.hualuo.engine.vision.VisionExec.ask(
                    session,
                    com.hualuo.engine.api.UrlConnTransport(),
                    listOf(b64),
                    prompt,
                )) {
                    is com.hualuo.engine.vision.VisionExec.Outcome.Ok -> r.text
                    is com.hualuo.engine.vision.VisionExec.Outcome.Failed -> "转述失败：${r.reason}"
                }
            }.getOrElse { "转述失败：${it.message ?: "原因不明"}" }
            captionResult = outcome.ifBlank { "模型回了空文本（换个模型或重试）" }
            captionBusy = false
        }, "hualuo-caption").start()
    }

    /** 对话搜索（chatsearch 页实装刀）：关键词逐会话逐行真搜，返回（标题, id, 命中行）。
     *  后台线程调用（IO）；一次最多回 50 条，界面端再截 20。 */
    fun searchConversations(query: String): List<Triple<String, String, String>> {
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

    /** 抽屉点某条会话：同步读那份 JSONL 摆上屏；读不出就出声，绝不摆空壳。 */
    fun openConversation(id: String) {
        if (store == null) return
        if (id == chat.sessionId) {
            closeSheets()
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

    fun askDeleteSelected() {
        val n = selectedIds.size
        if (n == 0) return
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

    // ── 持久化小件 ──────────────────────────────────────────────────────────

    /**
     * 「改了就记一笔」的状态位：值没变不记（免得白写盘），变了才既更新界面状态、又交给 persist。
     * 界面读它照样是快照状态，重组行为与 by mutableStateOf 一致。
     */
    private inner class Saved<T>(
        private val key: String,
        initial: T,
        private val encode: (T) -> String,
    ) {
        private val state = mutableStateOf(initial)

        operator fun getValue(thisRef: Any?, property: KProperty<*>): T = state.value

        operator fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
            if (state.value == value) return
            state.value = value
            persist.save(key, encode(value))
        }
    }

    private fun <T> saved(key: String, initial: T, encode: (T) -> String): Saved<T> =
        Saved(key, initial, encode)

    private fun readTab(): NavTab {
        val stored = persist.load(UiKeys.TAB) ?: return NavTab.Chat
        return NavTab.entries.firstOrNull { it.name == stored } ?: NavTab.Chat
    }

    private fun readBool(key: String, default: Boolean): Boolean =
        when (persist.load(key)?.trim()?.lowercase()) {
            null -> default
            "true", "1", "yes", "on" -> true
            "false", "0", "no", "off" -> false
            else -> default
        }

    private fun readInt(key: String, default: Int): Int =
        persist.load(key)?.trim()?.toIntOrNull() ?: default

    /** 生成参数读数：存过用存值，没存过用默认——滑块显示什么请求就带什么（与 Agora 一致）。 */
    private fun readDouble(key: String, default: Double): Double? =
        persist.load(key)?.trim()?.toDoubleOrNull() ?: default

    private fun fmtConvMeta(ms: Long): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(ms))

    /**
     * 会话库接线（启动，全同步）。刻意写成嵌套 if 而不是 init 里 return：init 块里的 return
     * 语义各版本 Kotlin 有分歧，不值得赌；嵌套清楚照样读得懂。
     * 放在类尾：convs 等属性的委托都已初始化，「先用后声明」的初始化顺序坑不存在。
     * 接成功了不出声（信任靠「字还在」建立，不靠开场白）；失败必须出声。
     */
    init {
        val s = store
        if (s != null) {
            val listing = runCatching { s.list() }.getOrNull()
            if (listing == null) {
                toast("会话目录读不了：列表这次空着，历史文件一个没动")
            } else {
                convs = listing.heads.map { (id, head) ->
                    Conv(id, head.title.ifBlank { "（未命名）" }, fmtConvMeta(head.createdAtMs))
                }
                if (listing.unreadable > 0) {
                    toast("有 ${listing.unreadable} 个会话文件读不出头，没摆进列表（文件原样保留）")
                }
                val latest = listing.heads.firstOrNull()
                if (latest != null) {
                    val note = chat.restoreFromStore(latest.first)
                    when {
                        note == null -> toast("上次的会话文件读不到了：列表还在，正文没接上")
                        note.badLines > 0 -> toast("已接上次会话（${note.count} 条）；另有 ${note.badLines} 行读不出，已跳过")
                        note.headMissing -> toast("已接上次会话（${note.count} 条）；这份会话头损坏，标题时间失真")
                        else -> Unit
                    }
                }
            }
        }
    }

    companion object {
        /** 没设置过时的默认模型（真接线后由模型清单决定，这里只是不空着）。 */

        /** 仓库CI 默认看的仓库（设置「GitHub 工作台」里可改）。 */
        const val DEFAULT_GITHUB_REPO = "xf8410/hualuo-repo-tool"

        /**
         * 单条消息字符上限。选 5 万的理由：几万字的整篇粘贴对几乎所有对话模型都还在
         * 窗口内，但一 MB 级的整文件直塞必炸——闸门拦的是「事故」不是「长文」。
         */
        const val MAX_PROMPT_CHARS = 50_000

        /** 文件投递的动作键（pendingDataAction 桥上走）：RootScreen 认这三个。 */
        const val ACTION_COURIER_PICK_FILES = "courier_pick_files"
        const val ACTION_COURIER_PICK_TREE = "courier_pick_tree"
        const val ACTION_COURIER_DELIVER = "courier_deliver"

        /** 投递目标前缀：courier/时间戳/，斜杠结尾（引擎件的规矩，前缀由调用方给）。 */
        fun buildCourierPrefix(nowMs: Long): String =
            "courier/" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(nowMs)) + "/"
    }
}