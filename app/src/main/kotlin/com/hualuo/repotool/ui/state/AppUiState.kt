package com.hualuo.repotool.ui.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hualuo.engine.github.GitHubCiClient
import com.hualuo.engine.github.GitHubRun
import com.hualuo.engine.github.normalizeGitHubRepo
import com.hualuo.engine.search.SearchOutcome
import com.hualuo.engine.search.WebSearchClient
import com.hualuo.engine.search.WebSearchResult
import com.hualuo.engine.store.SessionStore
import com.hualuo.repotool.backup.BackupGateway
import com.hualuo.repotool.ui.data.DemoComposerThumbs
import com.hualuo.repotool.ui.data.DemoConversations
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
    val label: String,
    val uri: String,
    val isTree: Boolean,
)

/** 全局界面状态（v13.1 的 JS 变量一对一翻译）。 */
class AppUiState(
    private val persist: UiPersistence = UiPersistence.None,
    private val store: SessionStore? = null,
    private val writeGate: WriteConfirmGate? = null,
    private val memoryStore: com.hualuo.engine.memory.MemoryStore? = null,
    private val skillStore: com.hualuo.engine.memory.MemoryStore? = null,
    private val imageGenConfig: (() -> com.hualuo.engine.toolcalls.ImageGenConfig?)? = null,
    private val imageGenPersist: ((ByteArray, String) -> String)? = null,
    val watchInboxDir: File? = null,
    val watchFramesDir: File? = null,
) {

    var tab: NavTab by saved(UiKeys.TAB, readTab(), { it.name })
    var input: String by saved(UiKeys.DRAFT, persist.load(UiKeys.DRAFT) ?: "", { it })
    var currentModel: String by saved(UiKeys.MODEL, persist.load(UiKeys.MODEL) ?: DEFAULT_MODEL, { it })
    var thinkOn: Boolean by saved(UiKeys.THINK_ON, readBool(UiKeys.THINK_ON, true), { it.toString() })
    var thinkLevel: Int by saved(UiKeys.THINK_LEVEL, readInt(UiKeys.THINK_LEVEL, 2), { it.toString() })
    var webSearchOn: Boolean by saved(UiKeys.WEB_SEARCH_ON, readBool(UiKeys.WEB_SEARCH_ON, true), { it.toString() })
    var shellOn: Boolean by saved(UiKeys.SHELL_ON, readBool(UiKeys.SHELL_ON, false), { it.toString() })
    var codeExecOn: Boolean by saved(UiKeys.CODE_EXEC_ON, readBool(UiKeys.CODE_EXEC_ON, false), { it.toString() })
    var relayOn: Boolean by saved(UiKeys.RELAY_ON, readBool(UiKeys.RELAY_ON, false), { it.toString() })
    var lockToConversation: Boolean by saved(UiKeys.LOCK_TO_CONVERSATION, readBool(UiKeys.LOCK_TO_CONVERSATION, false), { it.toString() })

    fun flushPersistence(): String? = persist.flush()
    fun persistenceMessages(): List<String> = persist.drainMessages()

    private val flagOverrides = mutableStateMapOf<String, Boolean>()
    fun flag(key: String, defaultOn: Boolean = false): Boolean = flagOverrides[key] ?: readBool(key, defaultOn)
    fun setFlag(key: String, value: Boolean) {
        flagOverrides[key] = value
        persist.save(key, value.toString())
    }

    private val textOverrides = mutableStateMapOf<String, String>()
    var settingsRevision by mutableStateOf(0L)
        private set
    fun text(key: String, default: String = ""): String = textOverrides[key] ?: persist.load(key) ?: default
    fun setText(key: String, value: String) {
        if (text(key) == value) return
        textOverrides[key] = value
        persist.save(key, value)
        settingsRevision += 1
        if (key == UiKeys.GITHUB_TOKEN) githubLogin.invalidate()
    }

    val githubLogin = GithubLoginState(
        persist = persist,
        toast = { msg -> toast(msg) },
        bumpRevision = { settingsRevision += 1 },
    )

    val chat = ChatRuntime(
        persist,
        autoRetryCostly = { flag(RETRY_COSTLY_KEY, RETRY_COSTLY_DEFAULT) },
        store = store,
        maxHistoryTurns = { readInt(UiKeys.MAX_HISTORY, ChatRuntime.MAX_HISTORY_TURNS).coerceIn(1, 500) },
        systemPrompt = {
            val base = persist.load(ChatRuntime.KEY_SYSTEM_PROMPT)?.trim().orEmpty()
            val catalog = skillStore?.let { com.hualuo.engine.toolcalls.SkillTool.catalog(it) }.orEmpty()
            when {
                catalog.isEmpty() -> base
                base.isEmpty() -> catalog
                else -> base + "\n\n" + catalog
            }
        },
        toolRegistry = buildGithubToolRegistry(
            persist,
            writeGate,
            memoryStore = memoryStore,
            sessionStore = store,
            webSearchEnabled = { webSearchOn },
            skillStore = skillStore,
            imageGenConfig = imageGenConfig,
            imageGenPersist = imageGenPersist,
            // URL 视频与录屏帧共用“眼睛模型”，不绑定主对话模型；主模型可以是纯文本模型。
            videoUrlSession = ::visionSessionOrDefault,
            watchInboxDir = watchInboxDir,
            watchFramesDir = watchFramesDir,
            visionSession = ::visionSessionOrDefault,
        ),
    )

    private fun visionSessionOrDefault(): com.hualuo.engine.api.ProviderSession? {
        val id = persist.load(UiKeys.VISION_MODEL)?.trim().orEmpty()
        if (id.isEmpty()) return null
        return ModelSettingsRuntime.current()?.sessionFor(id)
    }

    val busy: Boolean get() = chat.busy

    val repo = RepoWorkbenchState(
        loadToken = { persist.load(UiKeys.GITHUB_TOKEN)?.trim()?.takeIf { it.isNotEmpty() } },
        toast = { msg -> toast(msg) },
    )

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
        chat.send(text, currentModel)
        if (chat.lastTrimmed > 0) toast("上下文装不下：砍了 ${chat.lastTrimmed} 条旧话才发（上限在「历史裁剪」里调）")
        input = ""
    }

    var searchQuery by mutableStateOf("")
    var searchBusy by mutableStateOf(false)
        private set
    var searchResults by mutableStateOf(emptyList<WebSearchResult>())
        private set
    var searchNote by mutableStateOf<String?>(null)
        private set

    fun runWebSearch() {
        if (searchBusy) return
        val query = searchQuery.trim()
        if (query.isEmpty()) {
            toast("先在框里写要搜什么")
            return
        }
        searchBusy = true
        searchNote = null
        Thread({
            val outcome = WebSearchClient().search(query)
            searchBusy = false
            when (outcome) {
                is SearchOutcome.Ok -> {
                    searchResults = outcome.results
                    searchNote = "搜到 ${outcome.results.size} 条（${outcome.query}）"
                }
                is SearchOutcome.Failed -> {
                    searchResults = emptyList()
                    searchNote = outcome.reason
                }
            }
        }, "hualuo-web-search").start()
    }

    val video = VideoUnderstandingState(watchInboxDir = watchInboxDir)

    var apkChecking by mutableStateOf(false)
    var apkReport by mutableStateOf<String?>(null)

    private val ciClient = GitHubCiClient()
    var ciBusy by mutableStateOf(false)
        private set
    var ciRuns by mutableStateOf(emptyList<GitHubRun>())
        private set
    var ciBadEntries by mutableStateOf(0)
        private set
    var ciError by mutableStateOf<String?>(null)
        private set
    var ciRepoLabel by mutableStateOf(DEFAULT_GITHUB_REPO)
        private set
    var updateNote by mutableStateOf<String?>(null)
        private set

    private fun githubToken(): String? = persist.load(UiKeys.GITHUB_TOKEN)?.trim()?.takeIf { it.isNotEmpty() }

    fun refreshRepoCi() {
        if (ciBusy) return
        val repo = persist.load(UiKeys.GITHUB_REPO)?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_GITHUB_REPO
        val token = githubToken()
        ciBusy = true
        ciError = null
        ciRepoLabel = repo
        Thread({
            val snapshot = runCatching { ciClient.latestRuns(repo, token) }.getOrElse {
                ciBusy = false
                ciError = "拉不动 GitHub（${it.message ?: "出错了"}）"
                return@Thread
            }
            ciRuns = snapshot.runs
            ciBadEntries = snapshot.badEntries
            ciError = snapshot.error
            ciBusy = false
        }, "hualuo-ci").start()
    }

    fun refreshRepoCiIfStale() {
        if (ciRuns.isEmpty() && !ciBusy) refreshRepoCi()
    }

    fun checkUpdate() {
        if (ciBusy) return
        val repo = persist.load(UiKeys.GITHUB_REPO)?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_GITHUB_REPO
        val token = githubToken()
        ciBusy = true
        Thread({
            val result = runCatching { ciClient.latestRelease(repo, token) }.getOrNull()
            ciBusy = false
            updateNote = when {
                result == null -> "查不动（内部异常）"
                result.error != null -> "查不到：${result.error}"
                result.notFound -> "GitHub 上还没有发布版，跳过对比"
                else -> {
                    val tag = result.release?.tag ?: "?"
                    if (tag == versionLabel.trim()) "已是最新（$tag）" else "有新版：$tag（当前 ${versionLabel}）"
                }
            }
        }, "hualuo-update").start()
    }

    var courierPicks by mutableStateOf(emptyList<CourierPick>())
        private set
    var courierBusy by mutableStateOf(false)
        private set
    var courierNote by mutableStateOf<String?>(null)
        private set

    fun addCourierPicks(picks: List<CourierPick>) {
        if (picks.isEmpty()) return
        courierPicks = courierPicks + picks
    }
    fun clearCourierPicks() { courierPicks = emptyList() }
    fun requestCourierPick(tree: Boolean) {
        if (courierBusy) {
            toast("正在投递，先等这批跑完")
            return
        }
        pendingDataAction = if (tree) ACTION_COURIER_PICK_TREE else ACTION_COURIER_PICK_FILES
    }
    fun courierRepo(): String? = normalizeGitHubRepo(text(UiKeys.COURIER_REPO).trim())
    fun courierBranch(): String = text(UiKeys.COURIER_BRANCH).trim().ifBlank { "main" }
    fun courierToken(): String = text(UiKeys.COURIER_TOKEN).trim().ifBlank { text(UiKeys.GITHUB_TOKEN).trim() }
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
    fun beginCourier() {
        courierBusy = true
        courierNote = null
    }
    fun finishCourier(note: String) {
        courierBusy = false
        courierNote = note
    }

    var pendingDataAction by mutableStateOf<String?>(null)
        private set
    fun requestDataAction(key: String) { pendingDataAction = key }
    fun clearPendingDataAction() { pendingDataAction = null }

    fun applyImportedBackup(backup: BackupGateway.ImportedBackup): String {
        if (!backup.formatOk) return "这不是本应用导出的备份包（格式或版本对不上）：没有导入任何东西"
        var applied = 0
        backup.settingsText.orEmpty().takeIf { it.isNotEmpty() }?.let { settingsText ->
            val props = java.util.Properties()
            val loadFailure = runCatching { props.load(java.io.StringReader(settingsText)) }.exceptionOrNull()
            if (loadFailure != null) return "备份里的设置读不懂：设置没动、会话 ${backup.sessionsImported} 份已入库"
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
            if (issues.isNotEmpty()) append("；设置提示：").append(issues.joinToString("；"))
            if (flushFailure != null) append("；设置没存上：$flushFailure")
        }
    }

    fun applyAgoraImport(outcome: BackupGateway.AgoraImportOutcome): String {
        if (!outcome.recognized || outcome.plan == null) return outcome.error ?: "旧备份没认出来：没有导入任何东西"
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
        plan.shellOn?.let { put(UiKeys.SHELL_ON, it.toString()) }
        settingsRevision += 1
        val issues = persistenceMessages()
        val flushFailure = flushPersistence()
        return buildString {
            append("旧 Agora 备份导入完成：设置 $applied 项、会话 ${outcome.sessionsImported} 份")
            if (issues.isNotEmpty()) append("；设置提示：").append(issues.joinToString("；"))
            if (flushFailure != null) append("；设置没存上：$flushFailure")
        }
    }

    var versionLabel by mutableStateOf("")
    var drawerOpen by mutableStateOf(false)
    var convQuery by mutableStateOf("")
    var selecting by mutableStateOf(false)
    var selectedIds by mutableStateOf(setOf<String>())
    var convs by mutableStateOf(DemoConversations)
    var confirmOpen by mutableStateOf(false)
    var confirmText by mutableStateOf("")
    var confirmAction: (() -> Unit)? = null
    var micOn by mutableStateOf(false)
    var addMenuOpen by mutableStateOf(false)
    var loopBarOn by mutableStateOf(true)
    var queueBarOn by mutableStateOf(true)
    var thumbs by mutableStateOf(DemoComposerThumbs)
    var modelSheetOpen by mutableStateOf(false)
    var toolSheetOpen by mutableStateOf(false)
    var taskSheetKey by mutableStateOf<String?>(null)
    var modelQuery by mutableStateOf("")
    var settingsOpen by mutableStateOf(false)
    var settingsQuery by mutableStateOf("")
    var subStack by mutableStateOf(listOf<String>())
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
        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
    }
    fun addThumb(icon: String) { thumbs = thumbs + icon }
    fun removeThumb(index: Int) { thumbs = thumbs.filterIndexed { i, _ -> i != index } }
    fun newConversation() {
        if (store == null) {
            val c = Conv("c" + (convs.size + 1) + "-" + System.currentTimeMillis(), "新会话 · 刚刚", "刚刚")
            convs = listOf(c) + convs
            selecting = false
            selectedIds = emptySet()
            closeSheets()
            toast("已新建会话（演示数据）")
            return
        }
        val id = runCatching { store.create(currentModel) }.getOrNull()
        if (id == null) {
            toast("新会话没建成（盘上出事了）：还接着当前会话聊，字不会丢")
            closeSheets()
            return
        }
        chat.startFreshSession(id)
        convs = listOf(Conv(id, "（未命名）", fmtConvMeta(System.currentTimeMillis()))) + convs
        selecting = false
        selectedIds = emptySet()
        closeSheets()
        toast("已新建会话")
    }
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
            ids.forEach { id -> runCatching { store?.delete(id) } }
            if (ids.contains(chat.sessionId)) chat.startFreshSession(null)
            convs = convs.filterNot { it.id in ids }
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
        subStack = if (subStack.isNotEmpty()) subStack.dropLast(1) else emptyList()
        if (subStack.isEmpty() && settingsOpen && !subStack.isNotEmpty()) settingsOpen = false
    }

    private inner class Saved<T>(private val key: String, initial: T, private val encode: (T) -> String) {
        private val state = mutableStateOf(initial)
        operator fun getValue(thisRef: Any?, property: KProperty<*>): T = state.value
        operator fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
            if (state.value == value) return
            state.value = value
            persist.save(key, encode(value))
        }
    }
    private fun <T> saved(key: String, initial: T, encode: (T) -> String): Saved<T> = Saved(key, initial, encode)
    private fun readTab(): NavTab = persist.load(UiKeys.TAB)?.let { NavTab.entries.firstOrNull { tab -> tab.name == it } } ?: NavTab.Chat
    private fun readBool(key: String, default: Boolean): Boolean = when (persist.load(key)?.trim()?.lowercase()) {
        "true", "1", "yes", "on" -> true
        "false", "0", "no", "off" -> false
        else -> default
    }
    private fun readInt(key: String, default: Int): Int = persist.load(key)?.trim()?.toIntOrNull() ?: default
    private fun fmtConvMeta(ms: Long): String = SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(ms))

    init {
        val s = store ?: return
        val listing = runCatching { s.list() }.getOrNull()
        if (listing == null) {
            toast("会话目录读不了：列表这次空着，历史文件一个没动")
        } else {
            convs = listing.heads.map { (id, head) -> Conv(id, head.title.ifBlank { "（未命名）" }, fmtConvMeta(head.createdAtMs)) }
            if (listing.unreadable > 0) toast("有 ${listing.unreadable} 个会话文件读不出头，没摆进列表（文件原样保留）")
            listing.heads.firstOrNull()?.let { (id, _) ->
                val note = chat.restoreFromStore(id)
                if (note?.badLines ?: 0 > 0) toast("已接上次会话；另有 ${note?.badLines} 行读不出，已跳过")
                if (note == null) toast("上次的会话文件读不到了：列表还在，正文没接上")
            }
        }
    }

    companion object {
        const val DEFAULT_MODEL = "qwen3.8-flash"
        const val DEFAULT_GITHUB_REPO = "xf8410/hualuo-repo-tool"
        const val MAX_PROMPT_CHARS = 50_000
        const val ACTION_COURIER_PICK_FILES = "courier_pick_files"
        const val ACTION_COURIER_PICK_TREE = "courier_pick_tree"
        const val ACTION_COURIER_DELIVER = "courier_deliver"
        fun buildCourierPrefix(nowMs: Long): String = "courier/" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(nowMs)) + "/"
    }
}
