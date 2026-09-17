package com.hualuo.repotool.ui.repo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hualuo.repotool.ui.components.CardTitle
import com.hualuo.repotool.ui.components.HCard
import com.hualuo.repotool.ui.components.LRow
import com.hualuo.repotool.ui.model.Tone
import com.hualuo.repotool.ui.state.AppUiState
import com.hualuo.repotool.ui.theme.Accent
import com.hualuo.repotool.ui.theme.Bg
import com.hualuo.repotool.ui.theme.Ink
import com.hualuo.repotool.ui.theme.SubInk
import com.hualuo.repotool.ui.theme.WarnAmber

/**
 * 仓库CI页（v13 #p-repo）：**真数据**——GitHub Actions 最近几条 run + 检查更新，
 * 外加仓库工作台：自己的仓清单（要令牌）、别人的公开仓、contents 逐级浏览、
 * 分支切换、提交历史（维护记录）、文件原文预览、**改码提交**（sha 对账、冲突出声不硬盖）。
 *
 * 数据通道：进页拉一次（已有数据不重复拉），「刷新」行手动重拉；
 * 失败（403 提示去填令牌 / 404 提示核仓库名 / 连不上）与坏条目都摆在明面上，
 * 不拿演示卡冒充 CI 状态——那正是旧版「演示卡撒谎」的同款病。
 */
@Composable
fun RepoScreen(state: AppUiState) {
    // 进页拉一次；LaunchedEffect(Unit) 每次进这个 tab 只跑一遍，不刷屏
    LaunchedEffect(Unit) {
        state.refreshRepoCiIfStale()
        state.refreshMyReposIfStale()
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(8.dp))
        HCard {
            CardTitle(state.ciRepoLabel)
            when {
                state.ciBusy && state.ciRuns.isEmpty() -> Text(
                    "正在拉 CI 记录…",
                    fontSize = 13.sp,
                    color = SubInk,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                state.ciRuns.isEmpty() && state.ciError != null -> Text(
                    state.ciError ?: "",
                    fontSize = 13.sp,
                    color = WarnAmber,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                state.ciRuns.isEmpty() -> Text(
                    "还没拉到记录：点下面「刷新」从 GitHub 重拉",
                    fontSize = 13.sp,
                    color = SubInk,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                else -> state.ciRuns.forEach { run ->
                    // 绿灯=成功；红=失败/超时/取消；黄=还在跑（conclusion 还没给）
                    val dot = when (run.conclusion) {
                        "success" -> Tone.Ok
                        "failure", "timed_out", "cancelled" -> Tone.Err
                        else -> Tone.Warn
                    }
                    LRow(
                        "run ${run.id}",
                        "${run.conclusion ?: run.status} · ${run.headSha.take(7)}",
                        dot = dot,
                    )
                }
            }
            if (state.ciBadEntries > 0) {
                Text(
                    "有 ${state.ciBadEntries} 条记录读不懂，已跳过（其余照常显示）",
                    fontSize = 11.5.sp,
                    color = WarnAmber,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
            LRow("刷新", chevron = true) { state.refreshRepoCi() }
        }

        // 我的仓库（含私有，要令牌）：点一行就进去浏览
        HCard {
            CardTitle("我的仓库（含私有，要令牌）")
            when {
                state.myReposBusy && state.myRepos.isEmpty() -> Text(
                    "正在拉仓库清单…",
                    fontSize = 13.sp,
                    color = SubInk,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                state.myRepos.isEmpty() && state.myReposNote != null -> Text(
                    state.myReposNote ?: "",
                    fontSize = 12.5.sp,
                    color = WarnAmber,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
                else -> state.myRepos.forEach { repo ->
                    LRow(
                        repo.fullName,
                        (if (repo.isPrivate) "私有" else "公开") + " · " + repo.updatedAt.take(10),
                        chevron = true,
                    ) {
                        state.closeFileView()
                        state.browseInto(repo.fullName)
                    }
                }
            }
            state.myReposNote?.let { note ->
                if (state.myRepos.isNotEmpty()) {
                    Text(note, fontSize = 11.5.sp, color = SubInk, modifier = Modifier.padding(vertical = 4.dp))
                }
            }
            LRow("刷新清单", chevron = true) { state.refreshMyRepos() }
        }

        // 看别人的仓（公开只读）：owner/name，粘整条链接也认
        HCard {
            CardTitle("看别人的仓（公开只读）")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Bg)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    if (state.otherRepoQuery.isEmpty()) {
                        Text("owner/name，粘整条链接也认", fontSize = 13.sp, color = SubInk)
                    }
                    BasicTextField(
                        value = state.otherRepoQuery,
                        onValueChange = { state.otherRepoQuery = it },
                        textStyle = TextStyle(fontSize = 13.sp, color = Ink),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    "打开",
                    fontSize = 13.sp,
                    color = Ink,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Bg)
                        .clickable { state.browseOtherRepo() }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }

        // 浏览卡：进了仓库才出现；分支切换 + 目录树 + 提交历史（维护记录）
        if (state.browseRepo.isNotEmpty()) {
            HCard {
                CardTitle("浏览 " + state.browseRepo + (if (state.browseRef != null) " @" + state.browseRef else ""))
                Text(
                    "/" + state.browsePath,
                    fontSize = 11.5.sp,
                    color = SubInk,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                // 分支切换（官方 App 最欠的一格）：切了路径回根重新走
                LRow("切换分支", state.browseRef ?: "默认分支", chevron = true) { state.toggleBranchPicker() }
                if (state.branchPickerOpen) {
                    when {
                        state.branchListBusy -> Text(
                            "正在拉分支…",
                            fontSize = 12.5.sp,
                            color = SubInk,
                            modifier = Modifier.padding(vertical = 6.dp),
                        )
                        state.branchList.isEmpty() && state.branchListNote != null -> Text(
                            state.branchListNote ?: "",
                            fontSize = 12.sp,
                            color = WarnAmber,
                            modifier = Modifier.padding(vertical = 6.dp),
                        )
                        else -> state.branchList.forEach { branch ->
                            LRow(branch.name, branch.commitSha.take(7), chevron = true) {
                                state.switchBranch(branch.name)
                            }
                        }
                    }
                    state.branchListNote?.let { note ->
                        if (state.branchList.isNotEmpty()) {
                            Text(note, fontSize = 11.sp, color = WarnAmber, modifier = Modifier.padding(vertical = 2.dp))
                        }
                    }
                }
                when {
                    state.browseBusy -> Text(
                        "正在拉目录…",
                        fontSize = 13.sp,
                        color = SubInk,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                    state.browseEntries.isEmpty() && state.browseNote != null -> Text(
                        state.browseNote ?: "",
                        fontSize = 12.5.sp,
                        color = WarnAmber,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                    state.browseEntries.isEmpty() -> Text(
                        "这个目录是空的",
                        fontSize = 12.5.sp,
                        color = SubInk,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                    else -> state.browseEntries.forEach { entry ->
                        LRow(
                            entry.name,
                            if (entry.isDir) "目录" else formatBytes(entry.sizeBytes),
                            chevron = true,
                        ) {
                            if (entry.isDir) state.browseDown(entry) else state.openBrowseFile(entry)
                        }
                    }
                }
                state.browseNote?.let { note ->
                    if (state.browseEntries.isNotEmpty()) {
                        Text(note, fontSize = 11.5.sp, color = WarnAmber, modifier = Modifier.padding(vertical = 4.dp))
                    }
                }
                if (state.browseTrail.isNotEmpty()) {
                    LRow("返回上一级", chevron = true) { state.browseUp() }
                }
                // 提交历史（维护记录）：当前分支最近干了什么，新在前
                LRow(
                    "提交历史",
                    if (state.commitsOpen) "收起" else if (state.commits.isNotEmpty()) "${state.commits.size} 条" else null,
                    chevron = true,
                ) { state.toggleCommits() }
                if (state.commitsOpen) {
                    when {
                        state.commitsBusy -> Text(
                            "正在拉提交历史…",
                            fontSize = 12.5.sp,
                            color = SubInk,
                            modifier = Modifier.padding(vertical = 6.dp),
                        )
                        state.commits.isEmpty() -> Text(
                            state.commitsNote ?: "还没有提交记录",
                            fontSize = 12.sp,
                            color = if (state.commitsNote != null) WarnAmber else SubInk,
                            modifier = Modifier.padding(vertical = 6.dp),
                        )
                        else -> state.commits.forEach { commit ->
                            LRow(
                                commit.sha.take(7),
                                (commit.messageFirstLine.take(28) + " · " + commit.author).trim(),
                                chevron = false,
                            )
                        }
                    }
                    state.commitsNote?.let { note ->
                        if (state.commits.isNotEmpty()) {
                            Text(note, fontSize = 11.sp, color = WarnAmber, modifier = Modifier.padding(vertical = 2.dp))
                        }
                    }
                    LRow("刷新历史", chevron = true) { state.refreshCommits() }
                }
                LRow("退出浏览", chevron = true) { state.exitBrowse() }
            }
        }

        // 文件预览 + 改码卡：原文，二进制/截断/超限都明说；编辑提交走 sha 对账
        if (state.fileViewPath.isNotEmpty()) {
            HCard {
                CardTitle("文件：" + state.fileViewPath)
                when {
                    state.fileViewBusy -> Text(
                        "正在拉文件…",
                        fontSize = 13.sp,
                        color = SubInk,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                    state.fileViewText == null -> Text(
                        state.fileViewNote ?: "没有内容可给",
                        fontSize = 12.5.sp,
                        color = WarnAmber,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                    else -> {
                        state.fileViewNote?.let { note ->
                            Text(note, fontSize = 11.5.sp, color = SubInk, modifier = Modifier.padding(bottom = 4.dp))
                        }
                        if (!state.editingOpen) {
                            Text(
                                state.fileViewText ?: "",
                                fontSize = 11.sp,
                                color = Ink,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                            )
                            val editable = state.fileViewSha != null &&
                                !state.fileViewTooBig &&
                                !state.fileViewTruncated
                            if (editable) {
                                LRow("编辑这个文件", chevron = true) { state.startEditing() }
                            } else {
                                Text(
                                    "这份不给在 App 里改（超限/截断/无 sha 账）：去电脑上改",
                                    fontSize = 11.5.sp,
                                    color = SubInk,
                                    modifier = Modifier.padding(vertical = 4.dp),
                                )
                            }
                        } else {
                            Text(
                                "编辑中（提交后不可撤回，想清楚再发）",
                                fontSize = 11.5.sp,
                                color = WarnAmber,
                                modifier = Modifier.padding(vertical = 4.dp),
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(170.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Bg)
                                    .padding(10.dp),
                            ) {
                                BasicTextField(
                                    value = state.editingText,
                                    onValueChange = { state.editingText = it },
                                    textStyle = TextStyle(fontSize = 11.sp, color = Ink, fontFamily = FontFamily.Monospace),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Bg)
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                            ) {
                                if (state.editingMessage.isEmpty()) {
                                    Text("commit message：改了什么（必填）", fontSize = 13.sp, color = SubInk)
                                }
                                BasicTextField(
                                    value = state.editingMessage,
                                    onValueChange = { state.editingMessage = it },
                                    textStyle = TextStyle(fontSize = 13.sp, color = Ink),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            state.editNote?.let { note ->
                                Spacer(Modifier.height(6.dp))
                                Text(note, fontSize = 12.sp, color = WarnAmber)
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(if (state.editBusy) SubInk else Accent)
                                        .clickable(enabled = !state.editBusy) { state.commitEdit() }
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                ) {
                                    Text(
                                        if (state.editBusy) "提交中…" else "提交改动",
                                        fontSize = 13.sp,
                                        color = Color.White,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Bg)
                                        .clickable(enabled = !state.editBusy) { state.cancelEditing() }
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                ) {
                                    Text("放弃", fontSize = 13.sp, color = Ink)
                                }
                            }
                        }
                    }
                }
                LRow("收起", chevron = true) { state.closeFileView() }
            }
        }

        Spacer(Modifier.height(10.dp))
        HCard {
            CardTitle("版本")
            Text(
                state.updateNote ?: "拿当前版本对 GitHub 最新发布版；还没发布过会直说",
                fontSize = 12.5.sp,
                color = SubInk,
                modifier = Modifier.padding(vertical = 6.dp),
            )
            LRow("检查更新", chevron = true) { state.checkUpdate() }
        }
    }
}

/** 体积给人话：KB 以下按字节，往上到 MB（清单接口给的本来就是字节账）。 */
private fun formatBytes(bytes: Long): String = when {
    bytes < 0L -> "未知大小"
    bytes < 1024L -> "${bytes}B"
    bytes < 1024L * 1024L -> "${bytes / 1024L}KB"
    else -> String.format("%.1fMB", bytes / (1024.0 * 1024.0))
}
