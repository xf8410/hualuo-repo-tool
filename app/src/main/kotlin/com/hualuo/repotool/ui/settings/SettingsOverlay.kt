package com.hualuo.repotool.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import kotlin.math.roundToInt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.size
import androidx.compose.ui.res.stringResource
import com.hualuo.repotool.ui.components.SwitchPill
import com.hualuo.repotool.ui.data.mergedSettingsSections
import com.hualuo.repotool.ui.data.orphanAdditions
import com.hualuo.repotool.ui.data.subPage
import com.hualuo.repotool.ui.model.IconKey
import com.hualuo.repotool.ui.model.SubField
import com.hualuo.repotool.ui.state.AppUiState
import com.hualuo.repotool.ui.state.ModelSettingsRuntime
import com.hualuo.repotool.ui.theme.Accent
import com.hualuo.repotool.ui.theme.Bg
import com.hualuo.repotool.ui.theme.CardBg
import com.hualuo.repotool.ui.theme.ChevGray
import com.hualuo.repotool.ui.theme.Hairline
import com.hualuo.repotool.ui.theme.Ink
import com.hualuo.repotool.ui.theme.SubInk

@Composable
fun SettingsOverlay(state: AppUiState) {
    BackHandler(enabled = state.settingsOpen) {
        state.backFromSettings()
    }
    val top = state.subStack.lastOrNull()
    Column(Modifier.fillMaxSize().background(Bg)) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            // Agora 式圆返回钮：图标字符住 icons.xml（家规），不带文字省地方
            Box(
                Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(17.dp))
                    .background(CardBg)
                    .border(1.dp, Hairline, RoundedCornerShape(17.dp))
                    .clickable { state.backFromSettings() },
                contentAlignment = Alignment.Center,
            ) { Text(stringResource(IconKey.Back.resId), fontSize = 16.sp, color = Ink) }
            Spacer(Modifier.width(14.dp))
            // 主页「设置」两个大字（Agora 式超大加粗），子页用子页名小一号
            Text(top?.let { subPage(it)?.title } ?: "设置", fontSize = if (top == null) 26.sp else 20.sp, fontWeight = FontWeight.Bold, color = Ink)
        }
        if (top == null) SettingsHome(state, Modifier.weight(1f)) else SubPageView(state, top, Modifier.weight(1f))
    }
}

@Composable
private fun SettingsHome(state: AppUiState, modifier: Modifier) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp).clip(RoundedCornerShape(16.dp)).background(CardBg).padding(horizontal = 14.dp, vertical = 9.dp)) {
            BasicTextField(value = state.settingsQuery, onValueChange = { state.settingsQuery = it }, textStyle = TextStyle(fontSize = 14.sp, color = Ink), modifier = Modifier.fillMaxWidth())
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp)) {
            val q = state.settingsQuery.trim().lowercase()
            val orphans = orphanAdditions()
            if (orphans.isNotEmpty()) {
                Text(
                    "有 ${orphans.size} 项设置挂不到任何组：" + orphans.joinToString("、") { it.title },
                    fontSize = 12.sp,
                    color = Ink,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
                )
            }
            mergedSettingsSections().forEach { section ->
                val hit = section.items.filter { q.isEmpty() || it.title.contains(q, true) || it.desc.contains(q, true) }
                if (hit.isNotEmpty()) {
                    // 分区小标签（Agora 式：主色小字，跟着分组名走）
                    Text(section.title, fontSize = 13.sp, color = Accent, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp))
                    hit.forEach { item ->
                        val runtime = ModelSettingsRuntime.current()
                        val value = when (item.subKey) {
                            "provider" -> runtime?.settings?.providers?.count { it.custom || runtime.isConfigured(it.id) }?.toString() ?: item.value
                            "model" -> runtime?.settings?.enabledModels?.size?.let { "启用 $it" } ?: item.value
                            // 网页搜索那行现报「现在会用谁」：换一家立刻看得见，不是一句写死的文案
                            "websearch" -> state.webSearch.providerLabel()
                            else -> item.value
                        }
                        // Agora 式行卡：左图标（圆角方底浅主色）+ 标题/副说明 + 行尾右箭头；
                        // 图标字形住 icons.xml，这里只取键（家规：源码不留图形字符）
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(bottom = 9.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .background(CardBg)
                                .clickable { state.subStack = state.subStack + item.subKey }
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier
                                    .size(34.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFFEAF1FF)),
                                contentAlignment = Alignment.Center,
                            ) { Text(stringResource(item.iconKey.resId), fontSize = 16.sp) }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) { Text(item.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink); Text(item.desc, fontSize = 12.sp, color = SubInk) }
                            value?.let { Text(it, fontSize = 11.sp, color = SubInk, textAlign = TextAlign.End) }
                            Text(stringResource(IconKey.Chevron.resId), fontSize = 14.sp, color = ChevGray, modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SubPageView(state: AppUiState, key: String, modifier: Modifier) {
    BackHandler(enabled = state.settingsOpen) {
        state.backFromSettings()
    }
    val page = subPage(key) ?: return
    val switches = remember(key) { mutableStateMapOf<String, Boolean>() }
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp)) {
        page.fields.forEach { f ->
            when (f) {
                is SubField.Sec -> Text(f.text, fontSize = 13.sp, color = Accent, modifier = Modifier.padding(6.dp))
                is SubField.PersistedText -> PersistedTextField(state, f)
                is SubField.PersistedSwitch -> Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(16.dp)).background(CardBg).padding(14.dp)) { Text(f.label, modifier = Modifier.weight(1f)); val on = state.flag(f.key, f.defaultOn); SwitchPill(on) { state.setFlag(f.key, !on) } }
                is SubField.PersistedSlider -> PersistedSliderField(state, f)
                is SubField.PersistedSeg -> PersistedSegField(state, f)
                SubField.StorageStats -> StorageStatsCard(state)
                SubField.AboutCard -> AboutCardField(state)
                SubField.MemoryCard -> MemoryCardField(state)
                is SubField.Switch -> Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(16.dp)).background(CardBg).padding(14.dp)) { Text(f.label, modifier = Modifier.weight(1f)); val on = switches[f.label] ?: f.on; SwitchPill(on) { switches[f.label] = !on } }
                SubField.WebSearchSettings -> WebSearchSettingsPanel(state)
                SubField.GithubLogin -> GithubLoginCard(state)
                SubField.ProviderSettings -> ProviderSettingsPanel(state)
                SubField.ModelSettings -> ModelSettingsPanel(state)
                is SubField.Note -> Text(f.text, fontSize = 12.sp, color = SubInk, modifier = Modifier.padding(6.dp))
                is SubField.Button -> Text(f.text, color = Accent, modifier = Modifier.fillMaxWidth().clickable { f.actionKey?.let(state::requestDataAction) ?: state.toast("已提交（演示，接线后生效）") }.padding(14.dp))
                else -> Text(f.toString(), fontSize = 12.sp, color = SubInk, modifier = Modifier.padding(8.dp))
            }
        }
    }
}

/** 记忆账卡：真库统计（memory_db）+ 活动记忆原文 + 逐条删除（先确认再删，删完刷新）。 */
@Composable
private fun MemoryCardField(state: AppUiState) {
    var refresh by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0) }
    val files = androidx.compose.runtime.remember(refresh) { state.memoryFiles() }
    val active = androidx.compose.runtime.remember(refresh) { state.activeMemory() }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(16.dp)).background(CardBg).padding(14.dp)) {
        when {
            files == null -> Text("记忆库没建起来（本机降级态）", fontSize = 13.sp, color = SubInk)
            files.isEmpty() -> Text("记忆库是空的：AI 还没往里记过东西（记忆工具族可用，模型调 memory_write 才会落条目）", fontSize = 13.sp, color = SubInk)
            else -> {
                Text("记忆 ${'$'}{files.size} 条（memory_db）", fontSize = 13.sp, color = Ink)
                Spacer(Modifier.height(6.dp))
                files.forEach { f ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(f.name, fontSize = 13.sp, color = Ink)
                            if (f.description.isNotEmpty()) Text(f.description, fontSize = 11.sp, color = SubInk)
                        }
                        Text(
                            "删除",
                            fontSize = 12.sp, color = Accent,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                                state.confirmText = "删记忆 ${'$'}{f.name}？"
                                state.confirmAction = {
                                    state.deleteMemory(f.name)?.let { state.toast(it) }
                                    refresh += 1
                                }
                                state.confirmOpen = true
                            }.padding(horizontal = 6.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
        if (active.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("活动记忆（每次生成都会带上）：", fontSize = 12.sp, color = SubInk)
            Text(active.take(200) + if (active.length > 200) "…" else "", fontSize = 12.sp, color = Ink)
        }
    }
}

/** 关于卡：版本行读真 versionLabel；检查更新真调 GitHub releases/latest 对比；提 issue 真 intent。 */
@Composable
private fun AboutCardField(state: AppUiState) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var latest by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var checking by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var licenseOpen by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(16.dp)).background(CardBg).padding(14.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text("版本", fontSize = 13.sp, color = SubInk)
            Spacer(Modifier.weight(1f))
            Text(state.versionLabel.ifEmpty { "正在读取" }, fontSize = 13.sp, color = Ink)
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            Text(
                if (checking) "正在查更新…" else "检查更新",
                fontSize = 13.sp, color = Accent,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable {
                    if (checking) return@clickable
                    checking = true
                    Thread {
                        val result = fetchLatestReleaseTag()
                        latest = result
                        checking = false
                    }.apply { name = "hualuo-update-check" }.start()
                }.padding(horizontal = 8.dp, vertical = 6.dp),
            )
            Spacer(Modifier.weight(1f))
            latest?.let {
                Text(it, fontSize = 13.sp, color = Ink)
            }
        }
        if (latest != null) {
            val current = state.versionLabel.substringBefore(" (")
            val newer = latest.orEmpty().removePrefix("v") > current.removePrefix("v")
            Text(
                if (newer) "有新版本（上面是线上最新 tag，去仓库 Releases 下载）" else "已是线上最新（tag 对比）",
                fontSize = 11.sp, color = SubInk,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "提 issue",
            fontSize = 13.sp, color = Accent,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable {
                runCatching {
                    ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/xf8410/hualuo-repo-tool/issues/new")))
                }.onFailure { state.toast("打不开 issue 页（设备上没有能接的浏览器？）") }
            }.padding(horizontal = 8.dp, vertical = 6.dp),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "开源许可",
            fontSize = 13.sp, color = Accent,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { licenseOpen = true }.padding(horizontal = 8.dp, vertical = 6.dp),
        )
    }
    if (licenseOpen) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { licenseOpen = false }) {
            Column(Modifier.clip(RoundedCornerShape(18.dp)).background(CardBg).padding(18.dp)) {
                Text("开源许可", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                Spacer(Modifier.height(8.dp))
                Text(
                    "本应用自身：源码仓 xf8410/hualuo-repo-tool（项目组自有，未挂第三方开源许可）。\n" +
                        "依赖件：AndroidX / Jetpack Compose / Kotlin 标准库 / kotlinx-serialization / OkHttp 生态等，" +
                        "各自按其上游许可分发；完整清单见仓库 gradle/libs.versions.toml。",
                    fontSize = 12.5.sp, color = SubInk,
                )
            }
        }
    }
}

/** 后台查 GitHub 最新 release tag；查不到就回一句人话（网络/私有仓/无 release 都落这里）。 */
private fun fetchLatestReleaseTag(): String =
    runCatching {
        val url = java.net.URL("https://api.github.com/repos/xf8410/hualuo-repo-tool/releases/latest")
        val conn = url.openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.setRequestProperty("User-Agent", "hualuo-repo-tool")
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        val body = conn.inputStream.bufferedReader().readText()
        conn.disconnect()
        val tag = body.substringAfter("\"tag_name\":", "").substringAfter('"', "").substringBefore('"', "")
        tag.ifEmpty { "仓库还没有发过 Release" }
    }.getOrElse { "查不到（网络不通或接口限流）" }

/** 存储占用卡：真统计 + 缓存真清理（清完重算；会话仓与收件箱只报大小不给一键删——防手滑丢历史）。 */
@Composable
private fun StorageStatsCard(state: AppUiState) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var stats by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<List<Pair<String, Long>>?>(null) }
    var scanning by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        scanning = true
        stats = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { storageStats(ctx) }
        scanning = false
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(16.dp)).background(CardBg).padding(14.dp)) {
        Text("存储占用（应用私有目录，按需刷新）", fontSize = 13.sp, color = SubInk)
        Spacer(Modifier.height(6.dp))
        val list = stats
        when {
            list == null -> Text(if (scanning) "统计中……" else "点下方刷新", fontSize = 13.sp, color = SubInk)
            list.isEmpty() -> Text("目录都是空的", fontSize = 13.sp, color = SubInk)
            else -> list.forEach { (name, bytes) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(name, fontSize = 13.sp, color = Ink)
                    Spacer(Modifier.weight(1f))
                    Text(fmtStorage(bytes), fontSize = 13.sp, color = if (bytes > 256L * 1024 * 1024) Ink else SubInk)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            Text("刷新统计", fontSize = 13.sp, color = Accent, modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable {
                scanning = true
                Thread {
                    val fresh = storageStats(ctx)
                    stats = fresh
                    scanning = false
                }.apply { name = "hualuo-storage-scan" }.start()
            }.padding(horizontal = 8.dp, vertical = 6.dp))
            Spacer(Modifier.weight(1f))
            Text("清缓存（帧/崩溃/生成图）", fontSize = 13.sp, color = Accent, modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable {
                Thread {
                    clearCacheDirs(ctx)
                    val fresh = storageStats(ctx)
                    stats = fresh
                }.apply { name = "hualuo-storage-clear" }.start()
            }.padding(horizontal = 8.dp, vertical = 6.dp))
        }
        Text("会话历史（sessions）与录屏收件箱（watch_inbox）只报大小：删它们会丢数据，要去数据控制页做导出再清。", fontSize = 11.sp, color = SubInk)
    }
}

private fun storageStats(ctx: android.content.Context): List<Pair<String, Long>> {
    val files = ctx.filesDir
    val entries = listOf(
        "会话历史 sessions" to java.io.File(files, "sessions"),
        "录屏收件箱 watch_inbox" to java.io.File(files, "watch_inbox"),
        "视频帧缓存 watch_frames" to java.io.File(files, "watch_frames"),
        "生成图 tool_images" to java.io.File(files, "tool_images"),
        "崩溃留档 crash" to java.io.File(files, "crash"),
        "设置 settings" to java.io.File(files, "settings"),
    )
    return entries.map { (label, dir) -> label to dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() } } +
        listOf("合计（含其他）" to files.walkBottomUp().filter { it.isFile }.sumOf { it.length() })
}

private fun clearCacheDirs(ctx: android.content.Context) {
    listOf("watch_frames", "tool_images", "crash").forEach { name ->
        java.io.File(ctx.filesDir, name).takeIf { it.isDirectory }?.listFiles()?.forEach { it.deleteRecursively() }
    }
}

private fun fmtStorage(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.2f GB".format(bytes / 1073741824.0)
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / 1048576.0)
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

/** 落盘滑块：拖动写 [SubField.PersistedSlider.key]，显示值带位数格式化。 */
@Composable
private fun PersistedSliderField(state: AppUiState, f: SubField.PersistedSlider) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(16.dp)).background(CardBg).padding(14.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(f.label, fontSize = 13.sp, color = SubInk)
            Spacer(Modifier.weight(1f))
            Text("%.${f.digits}f".format(persistedSliderValue(state, f)), fontSize = 13.sp, color = Ink)
        }
        val value = persistedSliderValue(state, f)
        androidx.compose.material3.Slider(
            value = value.toFloat(),
            onValueChange = { raw ->
                // 步进对齐：拖到最近格点，避免存出 0.7333 这种既不步进也难读的值
                val stepped = (raw / f.step).roundToInt() * f.step
                val clamped = stepped.coerceIn(f.min, f.max)
                state.setText(f.key, "%.${f.digits}f".format(clamped))
            },
            valueRange = f.min.toFloat()..f.max.toFloat(),
        )
        Row(Modifier.fillMaxWidth()) {
            Text("%.${f.digits}f".format(f.min), fontSize = 11.sp, color = SubInk)
            Spacer(Modifier.weight(1f))
            Text("%.${f.digits}f".format(f.max), fontSize = 11.sp, color = SubInk)
        }
    }
}

private fun persistedSliderValue(state: AppUiState, f: SubField.PersistedSlider): Double {
    val stored = state.text(f.key).toDoubleOrNull() ?: f.default
    return stored.coerceIn(f.min, f.max)
}

/** 落盘选项组：点选写 [SubField.PersistedSeg.key]（存 value 不存显示名）。 */
@Composable
private fun PersistedSegField(state: AppUiState, f: SubField.PersistedSeg) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(16.dp)).background(CardBg).padding(14.dp)) {
        Text(f.label, fontSize = 13.sp, color = SubInk)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            f.options.forEach { choice ->
                val selected = state.text(f.key, f.default) == choice.value
                Text(
                    choice.name,
                    fontSize = 13.sp,
                    color = if (selected) Accent else SubInk,
                    modifier = Modifier
                        .padding(end = 6.dp, bottom = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .then(if (selected) Modifier.border(1.dp, Accent, RoundedCornerShape(12.dp)) else Modifier)
                        .clickable { state.setText(f.key, choice.value) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun PersistedTextField(state: AppUiState, f: SubField.PersistedText) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(16.dp)).background(CardBg).padding(14.dp)) {
        Text(f.label, fontSize = 13.sp, color = SubInk)
        val v = state.text(f.key)
        if (v.isEmpty()) Text(f.placeholder, fontSize = 13.sp, color = SubInk)
        BasicTextField(value = v, onValueChange = { state.setText(f.key, it) }, visualTransformation = if (f.secret) PasswordVisualTransformation() else VisualTransformation.None, modifier = Modifier.fillMaxWidth())
    }
}
