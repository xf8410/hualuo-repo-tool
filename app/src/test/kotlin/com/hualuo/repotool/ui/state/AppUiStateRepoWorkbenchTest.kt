package com.hualuo.repotool.ui.state

import com.hualuo.engine.github.GitHubEntry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 仓库工作台状态层的纯 JVM 测试：只钉**不发网就能判**的闸门与账本动作——
 * 写法闸拦在撞网之前、目录/文件分家、回退栈、清场、分支/编辑的离线闸。
 * 真网络路径由引擎件测试看住。
 */
class AppUiStateRepoWorkbenchTest {

    @Test
    fun myReposRefreshWithoutTokenSetsNoteNotBusy() {
        val s = AppUiState()
        s.refreshMyRepos()

        assertFalse(s.myReposBusy)
        assertNotNull(s.myReposNote)
        assertTrue(s.myReposNote!!.contains("令牌"))
        assertTrue(s.myRepos.isEmpty())
    }

    @Test
    fun browseOtherRepoRejectsBadQueryWithVoice() {
        val s = AppUiState()
        s.otherRepoQuery = "just-a-name"
        s.browseOtherRepo()

        assertTrue(s.browseRepo.isEmpty())
        assertTrue(s.toastVisible)
    }

    @Test
    fun browseOtherRepoRejectsEmptyQueryWithVoice() {
        val s = AppUiState()
        s.browseOtherRepo()

        assertTrue(s.browseRepo.isEmpty())
        assertTrue(s.toastVisible)
    }

    @Test
    fun browseDownOnFileEntryIsIgnored() {
        val s = AppUiState()
        s.browseDown(GitHubEntry("a.kt", "a.kt", false, 1L))

        assertTrue(s.browseTrail.isEmpty())
        assertTrue(s.browsePath.isEmpty())
        assertFalse(s.browseBusy)
    }

    @Test
    fun browseUpAtRootIsNoop() {
        val s = AppUiState()
        s.browseUp()

        assertTrue(s.browseTrail.isEmpty())
        assertFalse(s.browseBusy)
    }

    @Test
    fun exitBrowseClearsTheWholeBrowseState() {
        val s = AppUiState()
        s.exitBrowse()

        assertTrue(s.browseRepo.isEmpty())
        assertTrue(s.browsePath.isEmpty())
        assertTrue(s.browseTrail.isEmpty())
        assertTrue(s.browseEntries.isEmpty())
        assertTrue(s.fileViewPath.isEmpty())
        assertFalse(s.branchPickerOpen)
        assertFalse(s.commitsOpen)
    }

    @Test
    fun closeFileViewClearsThePreview() {
        val s = AppUiState()
        s.closeFileView()

        assertTrue(s.fileViewPath.isEmpty())
        assertTrue(s.fileViewText == null)
        assertFalse(s.fileViewBusy)
        assertFalse(s.editingOpen)
    }

    @Test
    fun toggleBranchPickerWithoutBrowseStaysOffline() {
        val s = AppUiState()
        s.toggleBranchPicker()

        assertTrue(s.branchPickerOpen)
        // 没在浏览仓库：闸住不发网，清单账本说人话
        assertFalse(s.branchListBusy)
        assertNotNull(s.branchListNote)
        s.toggleBranchPicker()
        assertFalse(s.branchPickerOpen)
    }

    @Test
    fun switchBranchWithoutBrowseIsNoop() {
        val s = AppUiState()
        s.switchBranch("dev")

        assertFalse(s.branchListBusy)
        assertFalse(s.browseBusy)
        assertFalse(s.commitsBusy)
    }

    @Test
    fun toggleCommitsWithoutBrowseStaysOffline() {
        val s = AppUiState()
        s.toggleCommits()

        assertTrue(s.commitsOpen)
        assertFalse(s.commitsBusy)
        s.toggleCommits()
        assertFalse(s.commitsOpen)
    }

    @Test
    fun startEditingWithoutContentIsBlocked() {
        val s = AppUiState()
        s.startEditing()

        assertFalse(s.editingOpen)
    }

    @Test
    fun commitEditWithoutMessageIsBlockedOffline() {
        val s = AppUiState()
        s.editingMessage = "   "
        s.commitEdit()

        // 编辑器没开：闸在第一道，什么请求都不发
        assertFalse(s.editBusy)
    }

    @Test
    fun commitEditWithClosedEditorIsNoop() {
        val s = AppUiState()
        s.commitEdit()

        assertFalse(s.editBusy)
    }

    @Test
    fun cancelEditingClearsTheDraft() {
        val s = AppUiState()
        s.cancelEditing()

        assertFalse(s.editingOpen)
        assertTrue(s.editingText.isEmpty())
        assertTrue(s.editingMessage.isEmpty())
    }
}
