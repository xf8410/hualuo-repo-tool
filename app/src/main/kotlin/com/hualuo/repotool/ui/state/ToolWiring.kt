package com.hualuo.repotool.ui.state

import com.hualuo.engine.sandbox.ProotSession
import com.hualuo.engine.sandbox.SandboxManager
import com.hualuo.engine.search.ProviderSearchClient
import com.hualuo.engine.search.SearchConfig
import com.hualuo.engine.toolcalls.GitHubToolFamily
import com.hualuo.engine.toolcalls.GitHubWriteTool
import com.hualuo.engine.toolcalls.SandboxConfirmer
import com.hualuo.engine.toolcalls.SandboxToolFamily
import com.hualuo.engine.toolcalls.ToolRegistry
import com.hualuo.engine.toolcalls.WriteConfirmer
import java.io.File

/**
 * 对话里模型可调工具的组装口（0.9.0 起加沙盒族）。
 *
 * **读类十件**（列仓、看别人的仓、浏览目录、读文件、搜代码、分支、提交历史、CI 三层）：
 * 直接进表，模型随对话可用。
 *
 * **GitHub 写类一件**（github_update_file，改码/新建）：只有拿到 [confirmer] 才注册——
 * 不给闸门就不存在这个工具（默认拒写，不是默认放行）。给闸门时：模型提议先摆成
 * 确认卡，用户点头才真走 PUT（sha 对账与 409 冲突那条老路照旧）。
 *
 * **PR 与动作族（2026-10-05 全套刀）**：建 PR / 合 PR / 建分支 / 删分支 / 建 issue /
 * 评论 issue / 关 PR / 评论 PR，件件过确认闸门——模型提议摆卡，人点头才动。加这批的
 * 原因：此前模型连「开分支干活」都做不到，任务一卡就是死结（机主原话「避免任务卡住」）。
 *
 * **沙盒族（0.9.0 刀）**：status / list_packages 两件只读直进；
 * run_command / install / remove 三件执行类只有拿到 [sandboxConfirmer] 才注册
 * （对齐 560 功能目录 414「Shell 命令审批」——不给闸门，模型在对话里就碰不到执行）。
 * 沙盒引擎实例随构建创建：rootfs 与缓存都在应用私有目录，装过一次终身覆盖升级。
 *
 * **网页族**：搜索走 [ProviderSearchClient]，配置（哪家、密钥、实例地址、条数）在
 * **每一次调用那一刻**从设置现场读（[webSearchConfig] 那个 lambda），改了下一句就生效；
 * 设置开关关掉 = 工具从清单里消失（不是「看得见但点不动」）。
 *
 * 令牌与默认仓库都在**执行那一刻**从设置现场读（两个 lambda 进引擎件，不缓存、不复制）：
 * 设置页改了令牌，下一句就生效，不用重启。令牌只进请求头（引擎件老规矩），
 * 绝不进任何结果文本与报错。
 *
 * **工具中心面板（2026-10-05 修摆设刀⑤）**：装配好的整表顺手登记进
 * [ToolRegistryRuntime]（与 [ModelSettingsRuntime] 同一套形状），工具页据此报真账——
 * 「这个应用到底注册了几件工具、此刻开着几件」直接从这张表问，不再维护第二份
 * 「族名 -> 该族有几件」对照表（那张表迟早漂移，漂了又是「界面上写着能调、实际没有」）。
 * 登记放在**装配口的出口**而不是 AppUiState 里：装配点与显示点不必互相持有引用，
 * 工具页也不必知道这张表是谁装配的。
 */
fun buildGithubToolRegistry(
    persist: UiPersistence,
    writeConfirmer: WriteConfirmer? = null,
    sandboxConfirmer: SandboxConfirmer? = null,
    sandboxRootDir: File? = null,
    prConfirmer: com.hualuo.engine.toolcalls.PrConfirmer? = null,
    // GitHub 动作族（建分支/删分支/建 issue/评论/关 PR/评论 PR）的确认闸门；
    // null = 六件全部不注册（与写件、PR 件同一条纪律：默认拒）
    actionConfirmer: com.hualuo.engine.toolcalls.ActionConfirmer? = null,
    memoryStore: com.hualuo.engine.memory.MemoryStore? = null,
    sessionStore: com.hualuo.engine.store.SessionStore? = null,
    webSearchEnabled: (() -> Boolean)? = null,
    webSearchConfig: (() -> SearchConfig)? = null,
    skillStore: com.hualuo.engine.memory.MemoryStore? = null,
    imageGenConfig: (() -> com.hualuo.engine.toolcalls.ImageGenConfig?)? = null,
    imageGenPersist: ((ByteArray, String) -> String)? = null,
    videoUrlSession: (() -> com.hualuo.engine.api.ProviderSession?)? = null,
    watchInboxDir: File? = null,
    watchFramesDir: File? = null,
    visionSession: (() -> com.hualuo.engine.api.ProviderSession?)? = null,
    observeClientProvider: (() -> com.hualuo.engine.observe.ObserveClient?)? = null,
    observeLink: com.hualuo.engine.observe.ObserveState? = null,
): ToolRegistry = GitHubToolFamily.build(
    loadToken = { persist.load(UiKeys.GITHUB_TOKEN) },
    defaultRepo = { persist.load(UiKeys.GITHUB_REPO) },
).also { registry ->
    if (writeConfirmer != null) {
        GitHubWriteTool.register(
            registry = registry,
            loadToken = { persist.load(UiKeys.GITHUB_TOKEN) },
            defaultRepo = { persist.load(UiKeys.GITHUB_REPO) },
            repoClient = com.hualuo.engine.github.GitHubRepoClient(),
            confirmer = writeConfirmer,
        )
    }
    if (prConfirmer != null) {
        com.hualuo.engine.toolcalls.GitHubPrTool.register(
            registry = registry,
            loadToken = { persist.load(UiKeys.GITHUB_TOKEN) },
            defaultRepo = { persist.load(UiKeys.GITHUB_REPO) },
            prClient = com.hualuo.engine.github.GitHubPrClient(),
            confirmer = prConfirmer,
        )
    }
    // GitHub 动作族（2026-10-05 全套刀）：建分支是「任务不卡住」的钥匙件——模型没它连
    // 干活的分支都开不了；删分支/关 PR 是不好回滚的件。六件全过闸门（confirmer
    // null = 整族不存在），确认卡是通用键值对卡（GitHubActionCard），模型摆什么人核什么。
    if (actionConfirmer != null) {
        com.hualuo.engine.toolcalls.GitHubActionTool.register(
            registry = registry,
            loadToken = { persist.load(UiKeys.GITHUB_TOKEN) },
            defaultRepo = { persist.load(UiKeys.GITHUB_REPO) },
            repoClient = com.hualuo.engine.github.GitHubRepoClient(),
            refClient = com.hualuo.engine.github.GitHubRefClient(),
            prClient = com.hualuo.engine.github.GitHubPrClient(),
            confirmer = actionConfirmer,
        )
    }
    // 记忆族（M4 第二刀）：不注入 memoryStore 一件不注册（默认拒）
    com.hualuo.engine.toolcalls.MemoryTool.register(registry, memoryStore)
    // 对话检索族（M4 第三刀）：吃会话仓本体——会话库没建成（store=null）检索工具就不存在，
    // 「搜不到」比「工具在但永远空手」诚实
    com.hualuo.engine.toolcalls.RagTool.register(registry, sessionStore)
    // 网页族（M4 第五刀）：配置现问现答（换一家、改密钥、填实例地址，下一句就生效）；
    // 设置开关关掉=清单里消失（运行时可见性，翻回立刻回来）
    com.hualuo.engine.toolcalls.WebTool.register(
        registry,
        ProviderSearchClient(config = { webSearchConfig?.invoke() ?: SearchConfig() }),
        visibleIf = webSearchEnabled,
        defaultNumResults = { webSearchConfig?.invoke()?.cappedNumResults ?: 5 },
    )
    // 技能族（M4 第六刀）：复用 MemoryStore（skill_db，无活动记忆文件）；不注入不注册
    com.hualuo.engine.toolcalls.SkillTool.register(registry, skillStore)
    // 服务端视频 URL 分析（看视频刀第二件）：给会话来源才注册；协议不符执行侧如实拒
    if (videoUrlSession != null) {
        com.hualuo.engine.toolcalls.VideoUrlTool.register(registry, videoUrlSession)
    }
    // 图像生成（M4 第七刀）：config+persist 都给才注册；钥匙没配=不可见（可见性现问现答）
    if (imageGenConfig != null && imageGenPersist != null) {
        com.hualuo.engine.toolcalls.ImageGenTool.register(
            registry,
            loadConfig = imageGenConfig,
            poster = { url, body, bearer -> com.hualuo.engine.toolcalls.ImageGenTool.defaultPoster(url, body, bearer) },
            downloadBytes = { url -> com.hualuo.engine.toolcalls.ImageGenTool.defaultDownloader(url) },
            persist = imageGenPersist,
        )
    }
    // 看视频（第二刀）：录屏导入时已抽帧缓存，对话模型调 list_videos/watch_video 拿**文字**——
    // 主对话模型不用自带视觉；眼睛模型没配（visionSession 拿不到会话）=两件不注册
    if (watchInboxDir != null && watchFramesDir != null && visionSession != null) {
        com.hualuo.engine.toolcalls.VideoTool.register(
            registry,
            manifestsDir = watchInboxDir,
            framesDir = watchFramesDir,
            visionSession = visionSession,
        )
    }
    // SO 观测桥族（560 清单 361-400 域）：uma_* 只读工具，桥不在=整族不注册（gated）。
    // client 每次现造（observeClientProvider 里现场读 ObserveUiState.baseUrl），改地址下一发生效；
    // 单并发/冷却由 ObserveState 把门——对端是嵌入式 SO，一人一格。
    observeLink?.let { link ->
        com.hualuo.engine.observe.UmaTool.register(
            registry,
            clientProvider = { observeClientProvider?.invoke() },
            state = link,
        )
    }
    if (sandboxConfirmer != null && sandboxRootDir != null) {
        val root = sandboxRootDir
        SandboxToolFamily.register(
            registry = registry,
            manager = SandboxManager(
                rootfsDir = File(root, "rootfs"),
                workDir = File(root, "work"),
                sharedDir = File(root, "shared"),
                sessionProvider = { rootfs, binds -> ProotSession(rootfsDir = rootfs, sharedDir = File(root, "shared"), bindMounts = binds) },
            ),
            confirmer = sandboxConfirmer,
        )
    }
}.also { assembled ->
    // 工具中心面板的取数口（修摆设刀⑤）：整表登记，工具页报数不再另抄一份清单。
    // 装配失败不在这里兜——构造期抛错由崩溃观察器取到，屏上照实是「还没装配」。
    ToolRegistryRuntime.install(assembled)
}