package com.hualuo.engine.github

import java.net.URLEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** 一个仓库的界面字段（就展示这些）。 */
data class GitHubRepoSummary(
    val fullName: String,
    val isPrivate: Boolean,
    val description: String,
    val defaultBranch: String,
    val updatedAt: String,
    val sizeKb: Long,
)

/** 仓库清单回执：error 非 null 时 repos 必为空；读不动的条目在 badEntries 里数出来。 */
data class GitHubRepoList(val repos: List<GitHubRepoSummary>, val badEntries: Int, val error: String?)

/** 浏览目录的一条：目录在前排序由 client 做。 */
data class GitHubEntry(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val sizeBytes: Long,
)

/** 浏览回执：error 非 null 时 entries 必为空。 */
data class GitHubBrowse(val entries: List<GitHubEntry>, val badEntries: Int, val error: String?)

/**
 * 读一个文件的回执：text 为 null 就是没有内容可给（二进制/太大/出错），理由在 error；
 * truncated = 内容被有界读截断（只读了前一段，改动接口在 B 段另接）。
 */
data class GitHubFileContent(
    val path: String,
    val text: String?,
    val charCount: Int,
    val truncated: Boolean,
    val error: String?,
)

/**
 * 仓库工作台的只读客户端（纯 JVM；fetch 缝隙注入，JVM 测试不碰网络）。
 *
 *  - 清单：自己的仓走 /user/repos（要令牌才看得到私有），别人的公开仓走 /users/{u}/repos；
 *  - 浏览：contents API 逐目录列（路径逐段编码，空格不会变加号）；
 *  - 读文件：Accept: raw 直接拿原文（不过 base64），有界读 512K，二进制（含 NUL）不给预览；
 *  - 令牌只进请求头；失败一律人话（状态码，绝无令牌），绝不拿半份清单冒充成功。
 */
class GitHubRepoClient(
    private val fetch: (String, String?, String?) -> GitHubHttpResult = ::githubHttpGet,
) {

    /** 自己的仓库（含私有）。令牌必填——没令牌就不发请求，先出声。 */
    fun listMyRepos(token: String?, limit: Int = 30): GitHubRepoList {
        if (token.isNullOrBlank()) {
            return GitHubRepoList(emptyList(), 0, "看自己的仓库要令牌：去设置「GitHub 工作台」填（看别人的不用）")
        }
        val result = fetch(
            "$GITHUB_API_ROOT/user/repos?per_page=${limit.coerceIn(1, 100)}&sort=updated&affiliation=owner",
            token,
            null,
        )
        if (result.status != 200) return GitHubRepoList(emptyList(), 0, httpIssue(result))
        return parseRepoList(result)
    }

    /** 别人的公开仓库（令牌可空）。用户名只认一段。 */
    fun listUserRepos(owner: String, token: String?, limit: Int = 30): GitHubRepoList {
        val clean = owner.trim().trimEnd('/')
        if (clean.isEmpty() || clean.any { it == '/' || it == '?' || it == '#' || it.isWhitespace() }) {
            return GitHubRepoList(emptyList(), 0, "用户名写法不对：只要一段（如 xf8410）")
        }
        val result = fetch(
            "$GITHUB_API_ROOT/users/$clean/repos?per_page=${limit.coerceIn(1, 100)}&sort=updated",
            token,
            null,
        )
        if (result.status == 404) return GitHubRepoList(emptyList(), 0, "GitHub 说没这个用户（404）：核对一下用户名")
        if (result.status != 200) return GitHubRepoList(emptyList(), 0, httpIssue(result))
        return parseRepoList(result)
    }

    /** 浏览一个目录：[path] 空串 = 仓库根。目录在前、按名排序。 */
    fun browse(repo: String, path: String, ref: String?, token: String?): GitHubBrowse {
        val full = normalizeGitHubRepo(repo)
            ?: return GitHubBrowse(emptyList(), 0, "仓库写法不对：要 owner/name（现在是「$repo」）")
        val encoded = encodeContentsPath(path)
        val refQuery = if (ref.isNullOrBlank()) "" else "?ref=" + encodeSegment(ref)
        val url = "$GITHUB_API_ROOT/repos/$full/contents" + (if (encoded.isEmpty()) "" else "/$encoded") + refQuery
        val result = fetch(url, token, null)
        if (result.status == 404) {
            return GitHubBrowse(emptyList(), 0, "GitHub 说没这个目录（404）：路径或分支不对")
        }
        if (result.status != 200) return GitHubBrowse(emptyList(), 0, httpIssue(result))
        val array = runCatching { json.parseToJsonElement(result.body) }.getOrNull() as? JsonArray
            ?: return GitHubBrowse(emptyList(), 0, "GitHub 回的内容读不懂（200 但不是目录清单）")
        val entries = ArrayList<GitHubEntry>()
        var bad = 0
        for (element in array) {
            if (element !is JsonObject) { bad += 1; continue }
            val name = (element["name"] as? JsonPrimitive)?.contentOrNull
            val entryPath = (element["path"] as? JsonPrimitive)?.contentOrNull
            val type = (element["type"] as? JsonPrimitive)?.contentOrNull
            val size = (element["size"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0L
            if (name.isNullOrEmpty() || entryPath.isNullOrEmpty() || (type != "file" && type != "dir")) {
                bad += 1
                continue
            }
            entries += GitHubEntry(name, entryPath, type == "dir", size)
        }
        val sorted = entries.sortedWith(compareByDescending<GitHubEntry> { it.isDir }.thenBy { it.name.lowercase() })
        return GitHubBrowse(sorted, bad, null)
    }

    /**
     * 读一个文件的原文（Accept: raw，不过 base64）。有界读 512K 字符——超了给前一段
     * 并把 truncated 说出来；含 NUL 判二进制，不给预览（字节不进内存也不硬转码）。
     */
    fun readFile(repo: String, path: String, ref: String?, token: String?): GitHubFileContent {
        val full = normalizeGitHubRepo(repo)
            ?: return GitHubFileContent(path, null, 0, false, "仓库写法不对：要 owner/name（现在是「$repo」）")
        val cleanPath = path.trim().trim('/')
        if (cleanPath.isEmpty()) return GitHubFileContent(path, null, 0, false, "文件路径是空的")
        val encoded = encodeContentsPath(cleanPath)
        val refQuery = if (ref.isNullOrBlank()) "" else "?ref=" + encodeSegment(ref)
        val result = fetch("$GITHUB_API_ROOT/repos/$full/contents/$encoded$refQuery", token, GITHUB_ACCEPT_RAW)
        if (result.status == 404) {
            return GitHubFileContent(cleanPath, null, 0, false, "GitHub 说没这个文件（404）：路径或分支不对")
        }
        if (result.status != 200) {
            return GitHubFileContent(cleanPath, null, 0, false, httpIssue(result))
        }
        if (result.body.contains('\u0000')) {
            return GitHubFileContent(cleanPath, null, 0, false, "这是二进制文件：App 里不给内容预览（改动的接口在 B 段另接）")
        }
        return GitHubFileContent(cleanPath, result.body, result.body.length, result.truncated, null)
    }

    private fun parseRepoList(result: GitHubHttpResult): GitHubRepoList {
        val array = runCatching { json.parseToJsonElement(result.body) }.getOrNull() as? JsonArray
            ?: return GitHubRepoList(emptyList(), 0, "GitHub 回的内容读不懂（200 但不是仓库清单）")
        val repos = ArrayList<GitHubRepoSummary>()
        var bad = 0
        for (element in array) {
            if (element !is JsonObject) { bad += 1; continue }
            val fullName = (element["full_name"] as? JsonPrimitive)?.contentOrNull
            if (fullName.isNullOrEmpty()) { bad += 1; continue }
            repos += GitHubRepoSummary(
                fullName = fullName,
                isPrivate = (element["private"] as? JsonPrimitive)?.booleanOrNull ?: false,
                description = (element["description"] as? JsonPrimitive)?.contentOrNull ?: "",
                defaultBranch = (element["default_branch"] as? JsonPrimitive)?.contentOrNull ?: "main",
                updatedAt = (element["updated_at"] as? JsonPrimitive)?.contentOrNull ?: "",
                sizeKb = (element["size"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0L,
            )
        }
        return GitHubRepoList(repos, bad, null)
    }

    /** 失败的人话：只说状态码与该干什么，原文不抄。 */
    private fun httpIssue(result: GitHubHttpResult): String = when {
        result.status == 0 -> "连不上 GitHub：${brief(result.body)}"
        result.status == 401 -> "GitHub 不认这把令牌（401）：去设置里核对或换新令牌"
        result.status == 403 -> "GitHub 不给看（403）：私有仓库要令牌，或者令牌权限不够"
        result.status == 404 -> "GitHub 说没这个仓库（404）：去设置里核对仓库名"
        else -> "GitHub 回了 ${result.status}，稍后再试"
    }

    private fun brief(text: String): String {
        val flat = text.replace('\n', ' ').replace('\r', ' ').trim()
        return if (flat.length <= 160) flat else flat.take(160) + "…（已截断）"
    }

    /** contents 路径逐段编码：斜杠保留当分隔符，空格变 %20（不是加号——路径里的 + 是真加号）。 */
    private fun encodeContentsPath(path: String): String =
        path.trim().trim('/').split('/').filter { it.isNotEmpty() }.joinToString("/") { encodeSegment(it) }

    private fun encodeSegment(segment: String): String =
        URLEncoder.encode(segment, "UTF-8").replace("+", "%20")

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
