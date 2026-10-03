package com.hualuo.repotool.ui.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hualuo.engine.github.GitHubCiClient
import com.hualuo.engine.github.GitHubRun

/**
 * 仓库 CI 那块瞬时态：最近的 workflow runs、坏条目数、拉取忙灯、错误话、更新检查结论。
 *
 * 独立成件的原因与 [WebSearchRunState] 同款：它天生是**瞬时**的（不落盘，重开从空开始），
 * 而 [AppUiState] 已经贴着红线三（单文件不许过 999 行），这类旁支不该再往里塞。
 * 字段名对上层保持原样（AppUiState 里的 ciBusy / ciRuns / ciError 等只是转发），
 * 界面层一行都不用改。
 *
 * 仓库与令牌在设置「GitHub 工作台」里配，令牌只进请求头（引擎件老规矩）。
 * 拉取在后台线程跑（大会计 IO 不进主线程）；失败与坏条目都摆在明面上，不冒充成功。
 */
class RepoCiState(
    private val loadToken: () -> String?,
    private val loadRepo: () -> String,
    private val versionLabel: () -> String,
) {

    private val client = GitHubCiClient()

    var busy by mutableStateOf(false)
        private set
    var runs by mutableStateOf(emptyList<GitHubRun>())
        private set
    /** 列表里读不出来的条目数：摆出来，不悄悄扔。 */
    var badEntries by mutableStateOf(0)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var repoLabel by mutableStateOf("")
        private set
    /** 「检查更新」的一句话结论；null = 还没查过。 */
    var updateNote by mutableStateOf<String?>(null)
        private set

    /** 拉默认分支最近的 workflow runs。失败/坏条目都摆在明面上，不冒充成功。 */
    fun refresh() {
        if (busy) return
        val repo = loadRepo()
        val token = loadToken()
        busy = true
        error = null
        repoLabel = repo
        Thread({
            val snapshot = runCatching { client.latestRuns(repo, token) }.getOrElse {
                busy = false
                error = "拉不动 GitHub（${it.message ?: "出错了"}）"
                return@Thread
            }
            runs = snapshot.runs
            badEntries = snapshot.badEntries
            error = snapshot.error
            busy = false
        }, "hualuo-ci").start()
    }

    /** 进页时才拉；已有数据或正在拉就不重复。 */
    fun refreshIfStale() {
        if (runs.isEmpty() && !busy) refresh()
    }

    /** 拿当前版本对 GitHub 最新发布版：有新版/已最新/没发布过/查不到，四态各说各话。 */
    fun checkUpdate() {
        if (busy) return
        val repo = loadRepo()
        val token = loadToken()
        busy = true
        Thread({
            val result = runCatching { client.latestRelease(repo, token) }.getOrElse {
                busy = false
                updateNote = "查不动（${it.message ?: "出错了"}）"
                return@Thread
            }
            busy = false
            updateNote = when {
                result.error != null -> "查不到：${result.error}"
                result.notFound -> "GitHub 上还没有发布版，跳过对比"
                else -> {
                    val tag = result.release?.tag ?: "?"
                    val current = versionLabel()
                    if (tag == current.trim()) "已是最新（$tag）" else "有新版：$tag（当前 $current）"
                }
            }
        }, "hualuo-update").start()
    }
}