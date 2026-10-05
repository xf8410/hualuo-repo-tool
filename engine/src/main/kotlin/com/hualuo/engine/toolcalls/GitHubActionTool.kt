package com.hualuo.engine.toolcalls

import com.hualuo.engine.github.GitHubPrClient
import com.hualuo.engine.github.GitHubRepoClient
import com.hualuo.engine.github.PrRequestFailed
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 通用动作提议（摆给人核对）：建/删分支、建 issue、评论 issue、关 PR、评论 PR 共用一张卡。
 * 字段故意做成「动作名 + 键值表 + 正文预览」——不用给每个动作单造一张 Proposal 类，
 * 卡片按 [fields] 逐行摆出来，人看得全，模型说得清。
 */
data class GitHubActionProposal(
    /** 动作名（卡片标题直接用）：建分支 / 删分支 / 建 issue / 评论 issue / 关 PR / 评论 PR。 */
    val action: String,
    val repo: String,
    /** 键值对逐行上卡（键固定中文、值原样）；分支名、PR 号、标题都走这里。 */
    val fields: List<Pair<String, String>>,
    /** 有正文的动作（评论/issue 说明）给开头一段核对；没有给空串。 */
    val bodyPreview: String,
    /** 正文总字符数（卡上出账，防「看着短其实巨长」）。 */
    val bodyChars: Int,
)

/**
 * 动作闸门：与 [WriteConfirmer] / [PrConfirmer] 同一条铁律——
 * **没拿到明确点头（拒绝、超时、没人管）一律当「没同意」，什么都不做**。
 */
fun interface ActionConfirmer {
    fun confirm(proposal: GitHubActionProposal): Boolean
}

/**
 * GitHub 动作工具族（2026-10-05 补全刀）：把电脑版 AI 对话里 GitHub 那套动作补齐到手机——
 *
 * 六件全部是**写件**，全部过 [ActionConfirmer] 闸门（不给闸门就一件都不注册，默认拒执行）：
 *  - github_create_branch：从某分支/某 sha 复制出新分支——「任务不卡住」的钥匙件。
 *    以前模型只能往已有分支写文件，想开新线干活就得停下等人，现在提议->点头->分支就位；
 *  - github_delete_branch：删分支（破坏件，闸门点头才删；受保护分支 GitHub 自己会拒）；
 *  - github_create_issue：建 issue；
 *  - github_add_issue_comment：给 issue 评论；
 *  - github_close_pull_request：关 PR（只关不合，合走 github_merge_pull_request）；
 *  - github_add_pull_request_comment：给 PR 评论。
 *
 * 读件（列 PR、读 PR、列 issue、读 issue、搜仓库、列 release）不在这一族——
 * 它们在 GitHubToolFamily（读类无闸门直进）。
 *
 * 与 WriteTool/PrTool 的分工：那边各管各的专用卡（写文件看正文 diff、合 PR 钉 sha），
 * 这边共用一张通用卡（动作 + 键值表 + 正文预览）——六件小动作不需要六张专用卡，
 * 但「点头才执行」的纪律一件不少。
 */
object GitHubActionTool {

    /** 评论正文封顶（对齐 WriteTool 的 MAX_WRITE_CHARS 同量级）。 */
    const val MAX_BODY_CHARS = 20_000
    const val MAX_TITLE_CHARS = 200

    /** 分支名的轻量校验：不装 Git 全家桶，只拦最常见的坏写法。 */
    private fun requireBranchName(name: String) {
        if (name.isBlank()) throw IllegalArgumentException("分支名是空的")
        if (name.startsWith("-") || name.endsWith(".lock") || name.endsWith("/")) {
            throw IllegalArgumentException("分支名「$name」是 Git 不收的写法（开头横线/结尾 .lock 或斜杠）")
        }
        if (name.contains("..") || name.contains("~") || name.contains("^") || name.contains(":") ||
            name.contains("?") || name.contains("*") || name.contains("[") || name.contains("\\") ||
            name.contains("//") || name.contains("@{") || name.any { it.isWhitespace() }
        ) {
            throw IllegalArgumentException("分支名「$name」含 Git 不收的字符（.. ~ ^ : ? * [ \\ 双斜杠 @{ 或空格）")
        }
        if (name.length > 200) throw IllegalArgumentException("分支名 ${name.length} 字符，超了 200")
    }

    fun register(
        registry: ToolRegistry,
        loadToken: () -> String?,
        defaultRepo: () -> String? = { null },
        repoClient: GitHubRepoClient = GitHubRepoClient(),
        refClient: com.hualuo.engine.github.GitHubRefClient = com.hualuo.engine.github.GitHubRefClient(),
        prClient: GitHubPrClient = GitHubPrClient(),
        confirmer: ActionConfirmer? = null,
    ): ToolRegistry {
        if (confirmer == null) return registry // 不给闸门就整族不存在（默认拒执行）

        // ── 建分支：任务不卡住的钥匙件 ──────────────────────────────────────
        registry.register(
            ToolSpec(
                name = "github_create_branch",
                description = "在仓库里建一条新分支（需要用户确认）。从哪条分支或哪个 sha 复制出来，" +
                    "提议先摆确认卡，用户点头才真建。要开新线干活（改码、试验）用它，别挤在默认分支上",
                parametersJson = """{"type":"object","properties":{""" +
                    """"repo":{"type":"string","description":"owner/name；缺省用设置里的默认仓库"},""" +
                    """"branch":{"type":"string","description":"新分支名（Git 规矩：不含空格、.. ~ ^ : ? * [ \\ 这些字符）"},""" +
                    """"from_ref":{"type":"string","description":"起点：分支名或 40 位 sha（缺省=仓库默认分支）"}},""" +
                    """"required":["branch"]}""",
            ),
        ) { argumentsJson ->
            val args = argsOf(argumentsJson)
            val repo = repoArg(args, defaultRepo)
            val branch = reqStr(args, "branch", "新分支名")
            requireBranchName(branch)
            val fromRef = optStr(args, "from_ref")?.takeIf { it.isNotBlank() }
                ?: repoClient.defaultBranchOf(repo, token(loadToken))
                ?: return@register "读不到 $repo 的默认分支（令牌没填或网络不通）：把 from_ref 参数写明确再试。终态：未建。"
            val approved = confirmer.confirm(
                GitHubActionProposal(
                    action = "建分支",
                    repo = repo,
                    fields = listOf("新分支" to branch, "从" to fromRef),
                    bodyPreview = "",
                    bodyChars = 0,
                ),
            )
            if (!approved) {
                return@register "用户没有确认建分支（或等待超时）：什么都没建。终态：未建。"
            }
            val created = refClient.createBranch(repo, branch, fromRef, token(loadToken))
            "已建分支 ${created.ref}（钉在 ${created.sha.take(8)}，起点 $fromRef）。" +
                "下一步可以往这条分支写文件（github_update_file 的 branch 参数填 $branch）。终态：建分支成功。"
        }

        // ── 删分支：破坏件，闸门把死 ────────────────────────────────────────
        registry.register(
            ToolSpec(
                name = "github_delete_branch",
                description = "删掉仓库里的一条分支（需要用户确认；破坏性动作，点了才删）。" +
                    "受保护的分支 GitHub 自己会拒",
                parametersJson = """{"type":"object","properties":{""" +
                    """"repo":{"type":"string","description":"owner/name；缺省用设置里的默认仓库"},""" +
                    """"branch":{"type":"string","description":"要删的分支名"}},""" +
                    """"required":["branch"]}""",
            ),
        ) { argumentsJson ->
            val args = argsOf(argumentsJson)
            val repo = repoArg(args, defaultRepo)
            val branch = reqStr(args, "branch", "要删的分支名")
            requireBranchName(branch)
            val approved = confirmer.confirm(
                GitHubActionProposal(
                    action = "删分支",
                    repo = repo,
                    fields = listOf("删掉" to "refs/heads/$branch", "提醒" to "删了就没了，除非重新建"),
                    bodyPreview = "",
                    bodyChars = 0,
                ),
            )
            if (!approved) {
                return@register "用户没有确认删分支（或等待超时）：什么都没删。终态：未删。"
            }
            refClient.deleteBranch(repo, branch, token(loadToken))
            "已删 refs/heads/$branch。终态：删分支成功。"
        }

        // ── 建 issue ────────────────────────────────────────────────────────
        registry.register(
            ToolSpec(
                name = "github_create_issue",
                description = "在仓库里建一个 issue（需要用户确认）。标题必填，说明可空；" +
                    "提议先摆确认卡，用户点头才真建",
                parametersJson = """{"type":"object","properties":{""" +
                    """"repo":{"type":"string","description":"owner/name；缺省用设置里的默认仓库"},""" +
                    """"title":{"type":"string","description":"issue 标题（1-200 字符）"},""" +
                    """"body":{"type":"string","description":"issue 说明（可空，最多 2 万字符）"}},""" +
                    """"required":["title"]}""",
            ),
        ) { argumentsJson ->
            val args = argsOf(argumentsJson)
            val repo = repoArg(args, defaultRepo)
            val title = reqStr(args, "title", "issue 标题，1-200 字符").trim()
            if (title.length !in 1..MAX_TITLE_CHARS) {
                return@register "标题 ${title.length} 字符，合法区间 1-$MAX_TITLE_CHARS。终态：未建。"
            }
            val body = optStr(args, "body") ?: ""
            if (body.length > MAX_BODY_CHARS) {
                return@register "说明 ${body.length} 字符，超了上限 $MAX_BODY_CHARS：删减后重试。终态：未建。"
            }
            val approved = confirmer.confirm(
                GitHubActionProposal(
                    action = "建 issue",
                    repo = repo,
                    fields = listOf("标题" to title),
                    bodyPreview = body.take(600),
                    bodyChars = body.length,
                ),
            )
            if (!approved) {
                return@register "用户没有确认建 issue（或等待超时）：什么都没建。终态：未建。"
            }
            val issue = prClient.createIssue(repo, title, body, token(loadToken))
            "已建 issue #${issue.number}：${issue.title}；${issue.htmlUrl}。终态：建 issue 成功。"
        }

        // ── issue 评论 ──────────────────────────────────────────────────────
        registry.register(
            ToolSpec(
                name = "github_add_issue_comment",
                description = "给一个 issue 加评论（需要用户确认）。正文先摆确认卡，用户点头才发",
                parametersJson = """{"type":"object","properties":{""" +
                    """"repo":{"type":"string","description":"owner/name；缺省用设置里的默认仓库"},""" +
                    """"number":{"type":"integer","description":"issue 号"},""" +
                    """"body":{"type":"string","description":"评论正文（1-2 万字符）"}},""" +
                    """"required":["number","body"]}""",
            ),
        ) { argumentsJson ->
            val args = argsOf(argumentsJson)
            val repo = repoArg(args, defaultRepo)
            val number = optLong(args, "number")
                ?: return@register "缺参数 number（issue 号，数字）。终态：未发。"
            val body = reqStr(args, "body", "评论正文")
            if (body.isBlank()) return@register "评论正文是空的。终态：未发。"
            if (body.length > MAX_BODY_CHARS) {
                return@register "评论 ${body.length} 字符，超了上限 $MAX_BODY_CHARS。终态：未发。"
            }
            val approved = confirmer.confirm(
                GitHubActionProposal(
                    action = "评论 issue",
                    repo = repo,
                    fields = listOf("issue" to "#$number"),
                    bodyPreview = body.take(600),
                    bodyChars = body.length,
                ),
            )
            if (!approved) {
                return@register "用户没有确认发这条评论（或等待超时）：什么都没发。终态：未发。"
            }
            val url = prClient.commentIssue(repo, number, body, token(loadToken))
            "评论已发到 issue #$number：$url。终态：评论成功。"
        }

        // ── 关 PR（只关不合） ───────────────────────────────────────────────
        registry.register(
            ToolSpec(
                name = "github_close_pull_request",
                description = "关掉一个 PR（需要用户确认；只关不合，合请用 github_merge_pull_request）。" +
                    "用户点头才真关",
                parametersJson = """{"type":"object","properties":{""" +
                    """"repo":{"type":"string","description":"owner/name；缺省用设置里的默认仓库"},""" +
                    """"number":{"type":"integer","description":"PR 号"}},""" +
                    """"required":["number"]}""",
            ),
        ) { argumentsJson ->
            val args = argsOf(argumentsJson)
            val repo = repoArg(args, defaultRepo)
            val number = optLong(args, "number")
                ?: return@register "缺参数 number（PR 号，数字）。终态：未关。"
            val approved = confirmer.confirm(
                GitHubActionProposal(
                    action = "关 PR",
                    repo = repo,
                    fields = listOf("PR" to "#$number", "语义" to "只关不合（分支保留）"),
                    bodyPreview = "",
                    bodyChars = 0,
                ),
            )
            if (!approved) {
                return@register "用户没有确认关 PR（或等待超时）：什么都没关。终态：未关。"
            }
            val pull = prClient.closePullRequest(repo, number, token(loadToken))
            "PR #$number 已关（状态 ${pull.state}）。终态：关 PR 成功。"
        }

        // ── PR 评论 ─────────────────────────────────────────────────────────
        registry.register(
            ToolSpec(
                name = "github_add_pull_request_comment",
                description = "给一个 PR 加评论（需要用户确认）。正文先摆确认卡，用户点头才发",
                parametersJson = """{"type":"object","properties":{""" +
                    """"repo":{"type":"string","description":"owner/name；缺省用设置里的默认仓库"},""" +
                    """"number":{"type":"integer","description":"PR 号"},""" +
                    """"body":{"type":"string","description":"评论正文（1-2 万字符）"}},""" +
                    """"required":["number","body"]}""",
            ),
        ) { argumentsJson ->
            val args = argsOf(argumentsJson)
            val repo = repoArg(args, defaultRepo)
            val number = optLong(args, "number")
                ?: return@register "缺参数 number（PR 号，数字）。终态：未发。"
            val body = reqStr(args, "body", "评论正文")
            if (body.isBlank()) return@register "评论正文是空的。终态：未发。"
            if (body.length > MAX_BODY_CHARS) {
                return@register "评论 ${body.length} 字符，超了上限 $MAX_BODY_CHARS。终态：未发。"
            }
            val approved = confirmer.confirm(
                GitHubActionProposal(
                    action = "评论 PR",
                    repo = repo,
                    fields = listOf("PR" to "#$number"),
                    bodyPreview = body.take(600),
                    bodyChars = body.length,
                ),
            )
            if (!approved) {
                return@register "用户没有确认发这条评论（或等待超时）：什么都没发。终态：未发。"
            }
            val url = prClient.commentPullRequest(repo, number, body, token(loadToken))
            "评论已发到 PR #$number：$url。终态：评论成功。"
        }

        return registry
    }

    // ── 参数小件（与 GitHubPrTool 同形；不复用它 private 的，免跨族耦合） ──────

    private fun argsOf(argumentsJson: String): JsonObject = try {
        kotlinx.serialization.json.Json.parseToJsonElement(argumentsJson) as JsonObject
    } catch (_: Exception) {
        JsonObject(emptyMap())
    }

    private fun optStr(args: JsonObject, key: String): String? =
        (args[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }

    private fun reqStr(args: JsonObject, key: String, hint: String): String =
        optStr(args, key) ?: throw IllegalArgumentException("缺参数 $key（$hint）")

    private fun optLong(args: JsonObject, key: String): Long? =
        (args[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

    private fun repoArg(args: JsonObject, defaultRepo: () -> String?): String {
        val given = optStr(args, "repo")
        if (!given.isNullOrEmpty()) return given
        val fallback = defaultRepo()?.trim().orEmpty()
        if (fallback.isNotEmpty()) return fallback
        throw IllegalArgumentException("缺参数 repo（owner/name；设置里也没填默认仓库可兜底）")
    }

    private fun token(load: () -> String?): String? =
        load()?.trim()?.takeIf { it.isNotEmpty() }
}
