package com.hualuo.engine.github

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 仓库工作台只读客户端的纯 JVM 契约测试：清单解析、路径编码、raw 读文件、
 * 二进制/截断/404/令牌缺失各说各话。fetch 全部注入，绝不碰真网。
 */
class GitHubRepoClientTest {

    private class FakeFetch : (String, String?, String?) -> GitHubHttpResult {
        val urls = ArrayList<String>()
        val tokens = ArrayList<String?>()
        val accepts = ArrayList<String?>()
        var next = GitHubHttpResult(200, "[]")

        override fun invoke(url: String, token: String?, accept: String?): GitHubHttpResult {
            urls += url
            tokens += token
            accepts += accept
            return next
        }
    }

    private fun client(fetch: FakeFetch) = GitHubRepoClient(fetch)

    @Test
    fun listMyReposWithoutTokenDoesNotTouchNetwork() {
        val fetch = FakeFetch()
        val list = client(fetch).listMyRepos(null)

        assertTrue(list.repos.isEmpty())
        assertNotNull(list.error)
        assertTrue(list.error!!.contains("令牌"))
        assertEquals(0, fetch.urls.size)
    }

    @Test
    fun listMyReposParsesSummariesAndCountsBadEntries() {
        val fetch = FakeFetch()
        fetch.next = GitHubHttpResult(
            200,
            "[{\"full_name\":\"xf8410/hualuo-repo-tool\",\"private\":true,\"description\":\"app\"," +
                "\"default_branch\":\"main\",\"updated_at\":\"2026-09-17T07:14:44Z\",\"size\":1234}," +
                "{\"full_name\":\"xf8410/umaai-rs\",\"private\":false,\"description\":null," +
                "\"default_branch\":\"master\",\"updated_at\":\"\",\"size\":0}," +
                "{\"no_name_here\":true}]",
        )
        val list = client(fetch).listMyRepos("tok")

        assertEquals(2, list.repos.size)
        assertEquals(1, list.badEntries)
        assertNull(list.error)
        val first = list.repos.first()
        assertEquals("xf8410/hualuo-repo-tool", first.fullName)
        assertTrue(first.isPrivate)
        assertEquals("main", first.defaultBranch)
        assertEquals(1234L, first.sizeKb)
        assertFalse(list.repos[1].isPrivate)
        assertEquals("master", list.repos[1].defaultBranch)
        assertTrue(fetch.urls.first().contains("/user/repos?per_page="))
        assertTrue(fetch.urls.first().contains("affiliation=owner"))
    }

    @Test
    fun listMyReposMaps403ToTokenAdvice() {
        val fetch = FakeFetch()
        fetch.next = GitHubHttpResult(403, "forbidden")
        val list = client(fetch).listMyRepos("tok")

        assertTrue(list.repos.isEmpty())
        assertTrue(list.error!!.contains("403"))
        assertTrue(list.error!!.contains("令牌"))
    }

    @Test
    fun listUserReposRejectsBadOwnerBeforeFetching() {
        val fetch = FakeFetch()
        val list = client(fetch).listUserRepos("a/b", null)

        assertTrue(list.repos.isEmpty())
        assertNotNull(list.error)
        assertEquals(0, fetch.urls.size)
    }

    @Test
    fun listUserRepos404SaysNoSuchUser() {
        val fetch = FakeFetch()
        fetch.next = GitHubHttpResult(404, "not found")
        val list = client(fetch).listUserRepos("ghost", null)

        assertTrue(list.error!!.contains("404"))
        assertTrue(list.error!!.contains("用户"))
    }

    @Test
    fun browseSortsDirsFirstAndCountsBadEntries() {
        val fetch = FakeFetch()
        fetch.next = GitHubHttpResult(
            200,
            "[{\"name\":\"z.kt\",\"path\":\"z.kt\",\"type\":\"file\",\"size\":10}," +
                "{\"name\":\"docs\",\"path\":\"docs\",\"type\":\"dir\",\"size\":0}," +
                "{\"weird\":1}]",
        )
        val browse = client(fetch).browse("xf8410/hualuo-repo-tool", "", null, "tok")

        assertNull(browse.error)
        assertEquals(1, browse.badEntries)
        assertEquals(2, browse.entries.size)
        assertTrue(browse.entries.first().isDir)
        assertEquals("docs", browse.entries.first().name)
        assertEquals("z.kt", browse.entries.last().name)
        assertTrue(fetch.urls.first().endsWith("/repos/xf8410/hualuo-repo-tool/contents"))
    }

    @Test
    fun browseEncodesEachPathSegmentKeepingSlashes() {
        val fetch = FakeFetch()
        fetch.next = GitHubHttpResult(200, "[]")
        client(fetch).browse("o/r", "src main/com/demo", null, null)

        val url = fetch.urls.first()
        assertTrue(url.contains("/contents/src%20main/com/demo"))
        assertFalse(url.contains("+"))
    }

    @Test
    fun browseBadRepoShapeStaysOffline() {
        val fetch = FakeFetch()
        val browse = client(fetch).browse("just-a-name", "", null, null)

        assertTrue(browse.error!!.contains("owner/name"))
        assertEquals(0, fetch.urls.size)
    }

    @Test
    fun browse404SaysDirectoryOrBranchWrong() {
        val fetch = FakeFetch()
        fetch.next = GitHubHttpResult(404, "nope")
        val browse = client(fetch).browse("o/r", "no/such/dir", null, null)

        assertTrue(browse.error!!.contains("404"))
    }

    @Test
    fun readFileUsesRawAcceptAndReturnsText() {
        val fetch = FakeFetch()
        fetch.next = GitHubHttpResult(200, "hello repo")
        val file = client(fetch).readFile("o/r", "README.md", null, "tok")

        assertNull(file.error)
        assertEquals("hello repo", file.text)
        assertEquals(10, file.charCount)
        assertFalse(file.truncated)
        assertEquals(GITHUB_ACCEPT_RAW, fetch.accepts.first())
        assertTrue(fetch.urls.first().endsWith("/contents/README.md"))
    }

    @Test
    fun readFileSendsRefQueryWhenGiven() {
        val fetch = FakeFetch()
        fetch.next = GitHubHttpResult(200, "x")
        client(fetch).readFile("o/r", "a b.kt", "dev branch", null)

        val url = fetch.urls.first()
        assertTrue(url.contains("/contents/a%20b.kt?ref=dev%20branch"))
    }

    @Test
    fun readFileFlagsBinaryAndGivesNoText() {
        val fetch = FakeFetch()
        fetch.next = GitHubHttpResult(200, "abc\u0000def")
        val file = client(fetch).readFile("o/r", "bin.dat", null, null)

        assertNull(file.text)
        assertTrue(file.error!!.contains("二进制"))
    }

    @Test
    fun readFilePropagatesTruncation() {
        val fetch = FakeFetch()
        fetch.next = GitHubHttpResult(200, "head of a big file", truncated = true)
        val file = client(fetch).readFile("o/r", "big.log", null, null)

        assertEquals("head of a big file", file.text)
        assertTrue(file.truncated)
    }

    @Test
    fun readFile404SaysFileOrBranchWrong() {
        val fetch = FakeFetch()
        fetch.next = GitHubHttpResult(404, "nope")
        val file = client(fetch).readFile("o/r", "gone.kt", null, null)

        assertNull(file.text)
        assertTrue(file.error!!.contains("404"))
    }
}
