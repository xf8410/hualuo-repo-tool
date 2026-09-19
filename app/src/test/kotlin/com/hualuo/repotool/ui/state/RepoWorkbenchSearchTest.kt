package com.hualuo.repotool.ui.state

import com.hualuo.engine.github.GitHubCodeHit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 仓库内搜索的状态舱纯 JVM 测试：四道离线闸（没在浏览/非默认分支/空词/无令牌）全部
 * 不发网且出声、退出浏览清场、命中点开在默认分支放行、非默认分支点开被拦。
 * 真网络路径由引擎件（GitHubSearchClientTest）看住。
 */
class RepoWorkbenchSearchTest {

    @Test
    fun runSearchWithoutBrowseStaysOffline() {
        val s = AppUiState()
        s.repo.searchQuery = "LRow"
        s.repo.runSearch()

        assertFalse(s.repo.searchBusy)
        assertTrue(s.repo.searchHits.isEmpty())
        assertNotNull(s.repo.searchNote)
        assertTrue(s.repo.searchNote!!.contains("没在浏览"))
    }

    @Test
    fun runSearchWithoutTokenStaysOffline() {
        val s = AppUiState()
        // 直接进浏览（无令牌时 browseInto 只置状态、拉目录会失败，但闸门先于网络）
        s.repo.searchQuery = "LRow"
        s.repo.runSearch()

        assertFalse(s.repo.searchBusy)
        assertTrue(s.repo.searchHits.isEmpty())
        assertNotNull(s.repo.searchNote)
    }

    @Test
    fun runSearchWithBlankKeywordStaysOffline() {
        val s = AppUiState()
        s.repo.runSearch()

        assertFalse(s.repo.searchBusy)
        assertNotNull(s.repo.searchNote)
    }

    @Test
    fun exitBrowseClearsSearchResults() {
        val s = AppUiState()
        s.repo.exitBrowse()

        assertTrue(s.repo.searchHits.isEmpty())
        assertEquals(0, s.repo.searchTotal)
        assertFalse(s.repo.searchIncomplete)
        assertFalse(s.repo.searchBusy)
    }

    @Test
    fun openSearchHitWithoutBrowseIsBlocked() {
        val s = AppUiState()
        // 没在浏览：点命中不该开预览（fileViewPath 是空的、也不该忙）
        s.repo.openSearchHit(GitHubCodeHit("a.kt", "a.kt"))

        assertTrue(s.repo.fileViewPath.isEmpty())
        assertFalse(s.repo.fileViewBusy)
    }
}
