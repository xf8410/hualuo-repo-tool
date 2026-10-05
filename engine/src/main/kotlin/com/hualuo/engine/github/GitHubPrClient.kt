package com.hualuo.engine.github

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * PR 客户端（M4 第一件）：建 PR 与合 PR。语义对齐旧 Agora GitHubPullRequestToolProvider：
 *  - **SHA 钉死**（merge）：合之前带 expectedHeadSha，GitHub 比对不上就拒——分支在确认卡
 *    摆出来之后又进了新提交时，绝不合错版本；
 *  - **fail-closed**：合并响应里 merged!=true 一律当失败（405 已合并/409 冲突带原话）；
 *  - 令牌只进请求头；错误文本原样透传（零脱敏纪律）。
 *
 * 确认闸门不在这一层——引擎件只做真请求与真解析，点头那道在工具族（GitHubPrTool）。
 */
class GitHubPrClient(
    private val postJson: (String, String?, String) -> GitHubHttpResult = ::githubHttpPostJson,
    private val putJson: (String, String?, String) -> GitHubHttpResult = ::githubHttpPutJson,
    private val fetch: (String, String?, String?, Int) -> GitHubHttpResult = ::githubHttpGet,
    private val patchJson: (String, String?, String) -> GitHubHttpResult = ::githubHttpPatchJson,
) {

    /** 建好的 PR（给人与模型看的关键字段，不带噪声）。 */
    data class CreatedPull(
        val number: Long,
        val state: String,
        val draft: Boolean,
        val headRef: String,
        val headSha: String,
        val baseRef: String,
        val htmlUrl: String,
    )

    /** 合并的结果。 */
    data class MergeOutcome(val merged: Boolean, val sha: String?, val message: String)

    /** 建 PR。参数在调用方（工具族）校验过，这里只做请求与解析；失败带对方原话。 */
    fun createPullRequest(
        repo: String,
        head: String,
        base: String,
        title: String,
        body: String,
        draft: Boolean,
        token: String?,
    ): CreatedPull {
        val json = Json { ignoreUnknownKeys = true }
        val payload = buildString {
            append("{\"title\":").append(q(title))
            append(",\"head\":").append(q(head))
            append(",\"base\":").append(q(base))
            if (body.isNotEmpty()) append(",\"body\":").append(q(body))
            append(",\"draft\":").append(draft).append("}")
        }
        val result = postJson("$GITHUB_API_ROOT/repos/$repo/pulls", token, payload)
        if (result.status != 201) {
            throw PrRequestFailed(result.status, briefOf(result.body, repoSuffix = "（建 PR）"))
        }
        val obj = json.parseToJsonElement(result.body).jsonObject
        val headObj = obj["head"]?.jsonObject ?: JsonObject(emptyMap())
        val baseObj = obj["base"]?.jsonObject ?: JsonObject(emptyMap())
        return CreatedPull(
            number = obj.longOf("number"),
            state = obj.strOf("state"),
            draft = obj.boolOf("draft"),
            headRef = headObj.strOf("ref"),
            headSha = headObj.strOf("sha"),
            baseRef = baseObj.strOf("ref"),
            htmlUrl = obj.strOf("html_url"),
        )
    }

    /**
     * 合 PR（SHA 钉死）：[expectedHeadSha] 是确认卡上那个人点头时看到的提交。
     * GitHub 在 head 已前移时会返回 409，这里把它翻成中文带原话的失败——不合错版本。
     */
    fun mergePullRequest(
        repo: String,
        number: Long,
        expectedHeadSha: String,
        method: String,
        commitTitle: String,
        token: String?,
    ): MergeOutcome {
        val payload = buildString {
            append("{\"sha\":").append(q(expectedHeadSha))
            append(",\"merge_method\":").append(q(method))
            if (commitTitle.isNotEmpty()) {
                append(",\"commit_title\":").append(q(commitTitle))
            }
            append("}")
        }
        val result = putJson("$GITHUB_API_ROOT/repos/$repo/pulls/$number/merge", token, payload)
        if (result.status != 200) {
            throw PrRequestFailed(result.status, briefOf(result.body, repoSuffix = "（合 PR #$number）"))
        }
        val obj = Json { ignoreUnknownKeys = true }.parseToJsonElement(result.body).jsonObject
        val merged = obj.boolOf("merged")
        return MergeOutcome(
            merged = merged,
            sha = (obj["sha"] as? kotlinx.serialization.json.JsonPrimitive)?.content,
            message = obj.strOf("message"),
        )
    }

    /** 取一个 PR 的当前 head sha（工具族在合并前对账用；404 带原话）。 */
    fun pullHeadSha(repo: String, number: Long, token: String?): String? {
        val result = fetch("$GITHUB_API_ROOT/repos/$repo/pulls/$number", token, null, GITHUB_MAX_BODY_CHARS)
        if (result.status != 200) {
            throw PrRequestFailed(result.status, briefOf(result.body, repoSuffix = "（读 PR #$number）"))
        }
        val obj = Json { ignoreUnknownKeys = true }.parseToJsonElement(result.body).jsonObject
        return (obj["head"] as? JsonObject)?.strOf("sha")?.takeIf { it.isNotEmpty() }
    }

    /** 仓库的默认分支（建 PR 缺 base 时用；读不到回 null 由调用方出声）。 */
    fun repoDefaultBranch(repo: String, token: String?): String? {
        val result = fetch("$GITHUB_API_ROOT/repos/$repo", token, null, GITHUB_MAX_BODY_CHARS)
        if (result.status != 200) {
            throw PrRequestFailed(result.status, briefOf(result.body, repoSuffix = "（读仓库信息）"))
        }
        val obj = Json { ignoreUnknownKeys = true }.parseToJsonElement(result.body).jsonObject
        return obj.strOf("default_branch").takeIf { it.isNotEmpty() }
    }


    // ── PR 现状件（2026-10-05 补全刀：合 PR 前的对账、任务不再卡在「看不见 PR 长啥样」） ──

    /** PR 摘要（列表与详情共用关键字段，不给噪声）。 */
    data class PullSummary(
        val number: Long,
        val title: String,
        val state: String,
        val draft: Boolean,
        val headRef: String,
        val headSha: String,
        val baseRef: String,
        val author: String,
        val updatedAt: String,
    )

    /** PR 详情：摘要 + 正文 + 可合性（模型在合之前对账用）。 */
    data class PullDetail(
        val summary: PullSummary,
        val body: String,
        val mergeable: Boolean?,
        val merged: Boolean,
        val htmlUrl: String,
        val commentsCount: Int,
    )

    /** 列 PR（state=open/closed/all）；每页 [limit] 条（1-50）。 */
    fun listPullRequests(repo: String, state: String, limit: Int, token: String?): List<PullSummary> {
        val capped = limit.coerceIn(1, 50)
        val result = fetch(
            "$GITHUB_API_ROOT/repos/$repo/pulls?state=$state&per_page=$capped",
            token, null, GITHUB_MAX_BODY_CHARS,
        )
        if (result.status != 200) {
            throw PrRequestFailed(result.status, briefOf(result.body, repoSuffix = "（列 PR）"))
        }
        val arr = runCatching { Json { ignoreUnknownKeys = true }.parseToJsonElement(result.body) }
            .getOrNull() as? kotlinx.serialization.json.JsonArray
            ?: return emptyList()
        val pulls = ArrayList<PullSummary>()
        for (element in arr) {
            val obj = element as? JsonObject ?: continue
            pulls.add(obj.toPullSummary())
        }
        return pulls
    }

    /** 读一个 PR 的详情（合 PR 前的对账件：head sha、mergeable、正文都在这里）。 */
    fun readPullRequest(repo: String, number: Long, token: String?): PullDetail {
        val result = fetch("$GITHUB_API_ROOT/repos/$repo/pulls/$number", token, null, GITHUB_MAX_BODY_CHARS)
        if (result.status != 200) {
            throw PrRequestFailed(result.status, briefOf(result.body, repoSuffix = "（读 PR #$number）"))
        }
        val obj = Json { ignoreUnknownKeys = true }.parseToJsonElement(result.body).jsonObject
        val mergeable = when ((obj["mergeable"] as? kotlinx.serialization.json.JsonPrimitive)?.content) {
            "true" -> true
            "false" -> false
            else -> null
        }
        return PullDetail(
            summary = obj.toPullSummary(),
            body = obj.strOf("body"),
            mergeable = mergeable,
            merged = obj.boolOf("merged"),
            htmlUrl = obj.strOf("html_url"),
            commentsCount = obj.longOf("comments").toInt(),
        )
    }

    /** 关 PR（PATCH state=closed；只关不合）。 */
    fun closePullRequest(repo: String, number: Long, token: String?): PullSummary {
        val result = patchJson("$GITHUB_API_ROOT/repos/$repo/pulls/$number", token, "{\"state\":\"closed\"}")
        if (result.status != 200) {
            throw PrRequestFailed(result.status, briefOf(result.body, repoSuffix = "（关 PR #$number）"))
        }
        return Json { ignoreUnknownKeys = true }.parseToJsonElement(result.body).jsonObject.toPullSummary()
    }

    /**
     * 给 PR 加评论（走 issues/{n}/comments——GitHub 的 PR 评论与 issue 评论同一个接口）。
     * 正文封顶 2 万字符在工具族闸门之前校验；这里只做请求与解析。
     */
    fun commentPullRequest(repo: String, number: Long, body: String, token: String?): String {
        val payload = "{\"body\":${q(body)}}"
        val result = postJson("$GITHUB_API_ROOT/repos/$repo/issues/$number/comments", token, payload)
        if (result.status != 201) {
            throw PrRequestFailed(result.status, briefOf(result.body, repoSuffix = "（评论 PR #$number）"))
        }
        val obj = Json { ignoreUnknownKeys = true }.parseToJsonElement(result.body).jsonObject
        return obj.strOf("html_url")
    }

    // ── Issue 件（电脑版对齐刀：issue 全套；读两件无闸、写两件过闸在 GitHubActionTool） ──

    /** Issue 摘要。 */
    data class IssueSummary(
        val number: Long,
        val title: String,
        val state: String,
        val author: String,
        val updatedAt: String,
        val commentsCount: Int,
        val htmlUrl: String,
    )

    /** 列 issue（state=open/closed/all）。 */
    fun listIssues(repo: String, state: String, limit: Int, token: String?): List<IssueSummary> {
        val capped = limit.coerceIn(1, 50)
        val result = fetch(
            "$GITHUB_API_ROOT/repos/$repo/issues?state=$state&per_page=$capped",
            token, null, GITHUB_MAX_BODY_CHARS,
        )
        if (result.status != 200) {
            throw PrRequestFailed(result.status, briefOf(result.body, repoSuffix = "（列 issue）"))
        }
        val arr = runCatching { Json { ignoreUnknownKeys = true }.parseToJsonElement(result.body) }
            .getOrNull() as? kotlinx.serialization.json.JsonArray
            ?: return emptyList()
        val issues = ArrayList<IssueSummary>()
        for (element in arr) {
            val obj = element as? JsonObject ?: continue
            // issues 接口会把 PR 也混进来（GitHub 的老规矩）：pull_request 字段在的就是 PR，剔除
            if (obj["pull_request"] != null) continue
            issues.add(obj.toIssueSummary())
        }
        return issues
    }

    /** 读 issue 详情 + 评论（评论最多带 10 条）。 */
    data class IssueDetail(val summary: IssueSummary, val body: String, val comments: List<String>)

    fun readIssue(repo: String, number: Long, token: String?): IssueDetail {
        val result = fetch("$GITHUB_API_ROOT/repos/$repo/issues/$number", token, null, GITHUB_MAX_BODY_CHARS)
        if (result.status != 200) {
            throw PrRequestFailed(result.status, briefOf(result.body, repoSuffix = "（读 issue #$number）"))
        }
        val obj = Json { ignoreUnknownKeys = true }.parseToJsonElement(result.body).jsonObject
        val commentsResult = fetch(
            "$GITHUB_API_ROOT/repos/$repo/issues/$number/comments?per_page=10",
            token, null, GITHUB_MAX_BODY_CHARS,
        )
        val comments = ArrayList<String>()
        if (commentsResult.status == 200) {
            val arr = runCatching { Json { ignoreUnknownKeys = true }.parseToJsonElement(commentsResult.body) }
                .getOrNull() as? kotlinx.serialization.json.JsonArray
            if (arr != null) {
                for (element in arr) {
                    val c = element as? JsonObject ?: continue
                    val who = (c["user"] as? JsonObject)?.strOf("login").orEmpty()
                    val text = c.strOf("body")
                    if (text.isNotEmpty()) comments.add("$who：$text")
                }
            }
        }
        return IssueDetail(obj.toIssueSummary(), obj.strOf("body"), comments)
    }

    /** 建 issue（写件：工具族闸门点头后才走到这里）。 */
    fun createIssue(repo: String, title: String, body: String, token: String?): IssueSummary {
        val payload = buildString {
            append("{\"title\":").append(q(title))
            if (body.isNotEmpty()) append(",\"body\":").append(q(body))
            append("}")
        }
        val result = postJson("$GITHUB_API_ROOT/repos/$repo/issues", token, payload)
        if (result.status != 201) {
            throw PrRequestFailed(result.status, briefOf(result.body, repoSuffix = "（建 issue）"))
        }
        return Json { ignoreUnknownKeys = true }.parseToJsonElement(result.body).jsonObject.toIssueSummary()
    }

    /** 给 issue 加评论（写件：闸门点头后才走）。 */
    fun commentIssue(repo: String, number: Long, body: String, token: String?): String {
        val payload = "{\"body\":${q(body)}}"
        val result = postJson("$GITHUB_API_ROOT/repos/$repo/issues/$number/comments", token, payload)
        if (result.status != 201) {
            throw PrRequestFailed(result.status, briefOf(result.body, repoSuffix = "（评论 issue #$number）"))
        }
        val obj = Json { ignoreUnknownKeys = true }.parseToJsonElement(result.body).jsonObject
        return obj.strOf("html_url")
    }

    /** 从 PR JSON 对象解摘要（列表与详情共用，防两处解析漂移）。 */
    private fun JsonObject.toPullSummary(): PullSummary {
        val headObj = this["head"]?.jsonObject ?: JsonObject(emptyMap())
        val baseObj = this["base"]?.jsonObject ?: JsonObject(emptyMap())
        return PullSummary(
            number = longOf("number"),
            title = strOf("title"),
            state = strOf("state"),
            draft = boolOf("draft"),
            headRef = headObj.strOf("ref"),
            headSha = headObj.strOf("sha"),
            baseRef = baseObj.strOf("ref"),
            author = (this["user"] as? JsonObject)?.strOf("login").orEmpty(),
            updatedAt = strOf("updated_at"),
        )
    }

    /** 从 issue JSON 对象解摘要（列表、详情、建后回执共用）。 */
    private fun JsonObject.toIssueSummary(): IssueSummary = IssueSummary(
        number = longOf("number"),
        title = strOf("title"),
        state = strOf("state"),
        author = (this["user"] as? JsonObject)?.strOf("login").orEmpty(),
        updatedAt = strOf("updated_at"),
        commentsCount = longOf("comments").toInt(),
        htmlUrl = strOf("html_url"),
    )

    private fun q(s: String): String = kotlinx.serialization.json.JsonPrimitive(s).toString()

    private fun briefOf(raw: String, repoSuffix: String): String {
        // 对方错误体原文透传（零脱敏）；只折行压长，超长带账
        val flat = raw.replace('\n', ' ').replace('\r', ' ').trim()
        return if (flat.length <= 300) "$flat$repoSuffix"
        else flat.take(300) + "…（已截断，原文 ${flat.length} 字符）$repoSuffix"
    }

    private fun JsonObject.strOf(key: String): String =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""

    private fun JsonObject.longOf(key: String): Long =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() ?: 0L

    private fun JsonObject.boolOf(key: String): Boolean =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"
}

/** PR 请求失败：状态码 + 对方原话（带出路由调用方拼）。 */
class PrRequestFailed(val status: Int, val detail: String) : Exception("GitHub 状态 $status：$detail")
