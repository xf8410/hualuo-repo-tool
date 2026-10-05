package com.hualuo.engine.github

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 分支与 release 客户端（2026-10-05 全套刀）：git refs 域的写读件。
 *
 * 为什么从 [GitHubRepoClient] 拆出来：那边的职责是「读仓库内容」（列仓/浏览/读文件/
 * 分支/提交/CI），这边的职责是「改引用与读发布」——建分支、删分支、列 release。
 * 揉在一个类里那个文件已经压过 999 行红线（CI 红线三），职责也确实是两件事：
 * 读件不动仓库状态，这里的建/删分支会。分开之后 [GitHubActionTool] 的闸门件
 * 只拿这一个句柄，写路径一目了然。
 *
 * 删分支不用 [githubHttpPatchJson] 底座而是直发 DELETE：GitHub 对删引用只回
 * 204 空体，底座的 JSON 解析对空体是浪费；204 之外一律翻译成人话。
 */
class GitHubRefClient {

    /** 建分支的回执：新引用与它钉住的 sha。 */
    data class BranchCreated(val ref: String, val sha: String)

    /**
     * 建分支（POST git/refs）：从 [fromRef]（分支名/tag/完整 sha）复制出 [branch]。
     * 引用已存在时 GitHub 回 422——翻成中文「这条分支已经在」，是事实不是失败。
     */
    fun createBranch(repo: String, branch: String, fromRef: String, token: String?): BranchCreated {
        val sha = resolveRefSha(repo, fromRef, token)
            ?: throw PrRequestFailed(422, "读不到 $repo 的 $fromRef：起点引用不存在（或令牌没权限看这个仓）")
        val payload = "{\"ref\":\"refs/heads/$branch\",\"sha\":\"$sha\"}"
        val result = githubHttpPostJson("$GITHUB_API_ROOT/repos/$repo/git/refs", token, payload)
        if (result.status == 422) {
            throw PrRequestFailed(422, "$repo 里 refs/heads/$branch 已经存在：换个分支名，或先用 github_list_branches 看现状")
        }
        if (result.status != 201) {
            throw PrRequestFailed(result.status, briefErr(result.body, "（建分支 $branch）"))
        }
        val obj = runCatching { Json { ignoreUnknownKeys = true }.parseToJsonElement(result.body) }
            .getOrNull() as? JsonObject
            ?: throw PrRequestFailed(result.status, "GitHub 回的建分支回执读不懂（建分支 $branch）")
        val ref = (obj["ref"] as? JsonPrimitive)?.content ?: ""
        val newSha = (obj["object"] as? JsonObject)
            ?.let { (it["sha"] as? JsonPrimitive)?.content } ?: ""
        return BranchCreated(ref, newSha)
    }

    /** 删分支（DELETE git/refs/heads/{branch}）；分支不存在或受保护时 422 翻成中文。 */
    fun deleteBranch(repo: String, branch: String, token: String?) {
        val conn = java.net.URL("$GITHUB_API_ROOT/repos/$repo/git/refs/heads/$branch").openConnection()
            as java.net.HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.requestMethod = "DELETE"
        conn.setRequestProperty("accept", "application/vnd.github+json")
        conn.setRequestProperty("user-agent", "hualuo-repo-tool")
        if (!token.isNullOrBlank()) conn.setRequestProperty("authorization", "Bearer $token")
        val status = conn.responseCode
        val body = (if (status in 200..299) conn.inputStream else conn.errorStream)?.let { readBounded(it, 4096) }
        if (status != 204) {
            val detail = body?.text.orEmpty().replace('\n', ' ').take(200)
            if (status == 422) {
                throw PrRequestFailed(status, "$repo 里没有 refs/heads/$branch（或它受保护不能删）")
            }
            throw PrRequestFailed(status, "${detail}（删分支 $branch）")
        }
    }

    /** release 摘要（工具页/模型看的关键字段）。 */
    data class ReleaseSummary(
        val tagName: String,
        val name: String,
        val draft: Boolean,
        val prerelease: Boolean,
        val publishedAt: String,
        val htmlUrl: String,
        val assetsCount: Int,
    )

    /** 列 release（每页最多 [limit] 条，1-50）。 */
    fun listReleases(repo: String, limit: Int, token: String?): List<ReleaseSummary> {
        val capped = limit.coerceIn(1, 50)
        val result = githubHttpGet("$GITHUB_API_ROOT/repos/$repo/releases?per_page=$capped", token)
        if (result.status != 200) {
            throw PrRequestFailed(result.status, briefErr(result.body, "（列 release）"))
        }
        val arr = runCatching { Json { ignoreUnknownKeys = true }.parseToJsonElement(result.body) }
            .getOrNull() as? JsonArray
            ?: return emptyList()
        val releases = ArrayList<ReleaseSummary>()
        for (element in arr) {
            val obj = element as? JsonObject ?: continue
            val assets = obj["assets"] as? JsonArray
            releases.add(
                ReleaseSummary(
                    tagName = strField(obj, "tag_name"),
                    name = strField(obj, "name"),
                    draft = boolField(obj, "draft"),
                    prerelease = boolField(obj, "prerelease"),
                    publishedAt = strField(obj, "published_at"),
                    htmlUrl = strField(obj, "html_url"),
                    assetsCount = assets?.size ?: 0,
                ),
            )
        }
        return releases
    }

    /** 解析一个引用（分支名/tag/40 位 sha）到完整 sha（建分支的起点对账）。 */
    private fun resolveRefSha(repo: String, ref: String, token: String?): String? {
        val isSha = Regex("^[0-9a-fA-F]{40}$").matches(ref)
        if (isSha) return ref.lowercase()
        val result = githubHttpGet("$GITHUB_API_ROOT/repos/$repo/git/ref/heads/$ref", token)
        if (result.status != 200) {
            // 分支不通再试 tag
            val tagResult = githubHttpGet("$GITHUB_API_ROOT/repos/$repo/git/ref/tags/$ref", token)
            if (tagResult.status != 200) return null
            val tagObj = runCatching { Json { ignoreUnknownKeys = true }.parseToJsonElement(tagResult.body) }
                .getOrNull() as? JsonObject ?: return null
            return (tagObj["object"] as? JsonObject)
                ?.let { (it["sha"] as? JsonPrimitive)?.content }
        }
        val obj = runCatching { Json { ignoreUnknownKeys = true }.parseToJsonElement(result.body) }
            .getOrNull() as? JsonObject ?: return null
        return (obj["object"] as? JsonObject)
            ?.let { (it["sha"] as? JsonPrimitive)?.content }
    }

    private fun briefErr(raw: String, suffix: String): String {
        val flat = raw.replace('\n', ' ').replace('\r', ' ').trim()
        return if (flat.length <= 300) "$flat$suffix" else flat.take(300) + "…（已截断）$suffix"
    }

    private fun strField(obj: JsonObject, key: String): String =
        (obj[key] as? JsonPrimitive)?.content ?: ""

    private fun boolField(obj: JsonObject, key: String): Boolean =
        (obj[key] as? JsonPrimitive)?.content == "true"
}
