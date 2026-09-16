package com.hualuo.engine.backup

import com.hualuo.engine.store.SessionHead
import com.hualuo.engine.store.StoredMsg
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream

// ── 旧 Agora 备份（.agora）格式 ────────────────────────────────────────────
// 按 xf8410/Agora-Workbench 源码钉死（DataExporter.kt / SettingsManager.kt，备份格式版本 1..3）：
// zip 包内五个有用条目，其余（images/、videos/、memories/、custom_font/）当场略过并计数。
// 字段名 = 旧仓 @Serializable 属性名，原样照抄；读不懂的一律按缺省处理，不炸。

/** manifest.json：认包的唯一凭证——没有它或版本不在 1..3，整个包拒收。 */
@Serializable
data class AgoraManifest(
    val agora_export_version: Int = 0,
    val app_version: String = "",
    val exported_at: String = "",
    val categories: List<String> = emptyList(),
    val has_api_keys: Boolean = false,
)

@Serializable
data class AgoraApiKey(
    val id: String = "",
    val name: String = "",
    val key: String = "",
    val provider: String = "",
)

/** api_keys.json：apiKeys 配 activeApiKeyIds（provider→keyId）才能对出「当前用的那把钥匙」。 */
@Serializable
data class AgoraKeys(
    val apiKeys: List<AgoraApiKey> = emptyList(),
    val activeApiKeyIds: Map<String, String> = emptyMap(),
    val webSearchApiKeys: Map<String, String> = emptyMap(),
    val shellApiKeys: Map<String, String> = emptyMap(),
)

@Serializable
data class AgoraCustomProvider(val name: String = "")

/** settings.json 只摘新版能接的字段，其余靠 ignoreUnknownKeys 路过——贪多必错。 */
@Serializable
data class AgoraSettings(
    val selectedModel: String = "",
    val providerBaseUrls: Map<String, String> = emptyMap(),
    val customProviders: List<AgoraCustomProvider> = emptyList(),
    val thinkingEnabled: Boolean? = null,
    val thinkingLevel: String? = null,
    val codeExecutionEnabled: Boolean? = null,
    val webSearchEnabled: Boolean? = null,
    val shellEnabled: Boolean? = null,
    val activeSystemPromptId: String? = null,
)

@Serializable
data class AgoraPromptItem(
    val id: String = "",
    val type: String = "CUSTOM",
    val value: String = "",
)

@Serializable
data class AgoraPromptEntry(
    val id: String = "",
    val title: String = "",
    val content: String = "",
    val systemItems: List<AgoraPromptItem> = emptyList(),
)

@Serializable
data class AgoraConversation(
    val id: String = "",
    val title: String = "",
    val lastUpdated: Long = 0,
    val modelId: String? = null,
)

/** images/attachmentMeta 用来数「媒体带不过来」的账，不搬运内容。 */
@Serializable
data class AgoraMessage(
    val id: String = "",
    val conversationId: String = "",
    val text: String = "",
    val participant: String = "MODEL",
    val status: String = "SUCCESS",
    val timestamp: Long = 0,
    val images: List<String> = emptyList(),
    val attachmentMeta: String? = null,
)

@Serializable
data class AgoraConversationsBlock(
    val conversations: List<AgoraConversation> = emptyList(),
    val messages: List<AgoraMessage> = emptyList(),
    val tasks: List<kotlinx.serialization.json.JsonObject> = emptyList(),
    val loops: List<kotlinx.serialization.json.JsonObject> = emptyList(),
)

/** 转出来的一个会话：id 已加 agora- 前缀并净化，head/messages 与新版会话库同行同款。 */
data class AgoraSessionPlan(
    val id: String,
    val head: SessionHead,
    val messages: List<StoredMsg>,
)

/**
 * 旧备份的完整兑换单：recognized=false 时其余字段全空、一个字节都不动本地库。
 * 带不过来的东西全在 notes 里说人话，不许静默丢。
 */
data class AgoraImportPlan(
    val recognized: Boolean,
    val formatVersion: Int = 0,
    val providerName: String? = null,
    val baseUrl: String? = null,
    val apiKey: String? = null,
    val selectedModel: String? = null,
    val thinkingOn: Boolean? = null,
    val thinkingLevel: Int? = null,
    val codeExecOn: Boolean? = null,
    val webSearchOn: Boolean? = null,
    val shellOn: Boolean? = null,
    val systemPrompt: String? = null,
    val sessions: List<AgoraSessionPlan> = emptyList(),
    val notes: List<String> = emptyList(),
)

private val AGORA_JSON = Json { ignoreUnknownKeys = true }

/** conversations.json 的字符上限：旧格式是一个大 JSON，超过就明说读不完，不拿内存赌命。 */
const val MAX_AGORA_CONVERSATION_CHARS = 48 shl 20

private const val MAX_SMALL_ENTRY_CHARS = 2 shl 20

/**
 * 读旧 Agora 备份：单趟 zip 流（不整包进内存），五个条目各有界收进，
 * 逐项兑换成新版的键与会话。任何一步读不懂都落进 notes/recognized=false，绝不抛给界面。
 */
fun readAgoraBackup(input: InputStream): AgoraImportPlan {
    val collected = HashMap<String, String>()
    var ignoredEntries = 0
    ZipInputStream(input.buffered()).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            val name = entry.name.substringAfterLast('\\', entry.name)
            val cap = when (name) {
                "manifest.json" -> MAX_SMALL_ENTRY_CHARS
                "api_keys.json", "settings.json", "system_prompts.json" -> MAX_SMALL_ENTRY_CHARS
                "conversations.json" -> MAX_AGORA_CONVERSATION_CHARS
                else -> 0
            }
            if (cap == 0 || entry.isDirectory) {
                ignoredEntries++
                zip.closeEntry()
                continue
            }
            collected[name] = readBoundedText(zip, cap)
            zip.closeEntry()
        }
    }

    val manifest = collected["manifest.json"]?.let { parseOrNull<AgoraManifest>(it) }
    if (manifest == null || manifest.agora_export_version !in 1..3) {
        return AgoraImportPlan(recognized = false)
    }
    val notes = ArrayList<String>()
    if (manifest.agora_export_version < 3) {
        notes.add("旧备份格式版本 ${manifest.agora_export_version}（v3 之前的草稿字段会有缺，能兑的都兑了）")
    }

    // ── 提供商三件套：activeApiKeyIds 指到哪把钥匙，就兑哪套 ────────────────
    val keys = collected["api_keys.json"]?.let { parseOrNull<AgoraKeys>(it) } ?: AgoraKeys()
    val active = keys.activeApiKeyIds.entries.firstOrNull { it.key.isNotBlank() && it.value.isNotBlank() }
    val keyEntry = active?.let { a -> keys.apiKeys.firstOrNull { it.id == a.value } }
    val providerName = keyEntry?.provider?.takeIf { it.isNotBlank() }
        ?: active?.key?.takeIf { it.isNotBlank() }
        ?: keys.apiKeys.firstOrNull()?.provider?.takeIf { it.isNotBlank() }
        ?: collected["settings.json"]?.let { parseOrNull<AgoraSettings>(it) }?.customProviders?.firstOrNull()?.name
    val settings = collected["settings.json"]?.let { parseOrNull<AgoraSettings>(it) } ?: AgoraSettings()
    val baseUrl = providerName?.let { p -> settings.providerBaseUrls[p]?.takeIf { it.isNotBlank() } }
    val apiKey = keyEntry?.key?.takeIf { it.isNotBlank() }
    if (keys.apiKeys.isNotEmpty() && apiKey == null) {
        notes.add("密钥没兑出来：激活记录对不上钥匙清单（旧包里 key 列表可能是空的）")
    }

    // ── 开关与模型 ─────────────────────────────────────────────────────────
    val thinkingLevel = when (settings.thinkingLevel?.trim()?.lowercase()) {
        "off" -> 0
        "low" -> 1
        "medium", "med" -> 2
        "high" -> 3
        else -> null
    }

    // ── 系统指令：激活的那条；模板变量没法在新版编译，只兑字面部分 ───────────
    val prompts = collected["system_prompts.json"]?.let { parseOrNull<List<AgoraPromptEntry>>(it) } ?: emptyList()
    var systemPrompt: String? = null
    if (settings.activeSystemPromptId != null) {
        val entry = prompts.firstOrNull { it.id == settings.activeSystemPromptId }
        if (entry != null) {
            val text = if (entry.systemItems.isNotEmpty()) {
                entry.systemItems.filter { it.type == "CUSTOM" }.joinToString("") { it.value }
            } else {
                entry.content
            }.trim()
            systemPrompt = text.takeIf { it.isNotEmpty() }
            if (entry.systemItems.any { it.type != "CUSTOM" }) {
                notes.add("系统指令里的模板变量（时间/模型名等）没法原样搬，只兑了固定文字")
            }
        }
    }

    // ── 会话：正文一条不丢，带不过来的逐项数出来 ───────────────────────────
    var conversations: List<AgoraConversation> = emptyList()
    var messages: List<AgoraMessage> = emptyList()
    var tasksCount = 0
    var loopsCount = 0
    var conversationBlockTruncated = false
    collected["conversations.json"]?.let { raw ->
        if (raw.length >= MAX_AGORA_CONVERSATION_CHARS) {
            conversationBlockTruncated = true
        }
        parseOrNull<AgoraConversationsBlock>(raw)?.let { block ->
            conversations = block.conversations
            messages = block.messages
            tasksCount = block.tasks.size
            loopsCount = block.loops.size
        }
    }
    if (conversationBlockTruncated) {
        notes.add("会话块超过 ${MAX_AGORA_CONVERSATION_CHARS / (1 shl 20)}MB 没读完：这次只兑设置，会话请分批导或先清旧图再导一次")
    }

    val byConv = LinkedHashMap<String, MutableList<AgoraMessage>>()
    var blankOrMedia = 0
    var unknownParticipant = 0
    var oddStatus = 0
    for (m in messages) {
        val hasMedia = m.images.isNotEmpty() || !m.attachmentMeta.isNullOrBlank()
        if (m.text.isBlank() && hasMedia) {
            blankOrMedia++
            continue
        }
        when (m.participant) {
            "USER", "MODEL" -> byConv.getOrPut(m.conversationId) { ArrayList() }.add(m)
            else -> unknownParticipant++
        }
    }

    val usedIds = HashSet<String>()
    val sessions = ArrayList<AgoraSessionPlan>(conversations.size)
    var oddStatusTotal = 0
    for (c in conversations) {
        val convMsgs = byConv[c.id] ?: continue
        val mapped = ArrayList<StoredMsg>(convMsgs.size)
        for (m in convMsgs) {
            val role = when {
                m.participant == "USER" -> StoredMsg.ROLE_USER
                m.status == "ERROR" -> StoredMsg.ROLE_ERROR
                else -> StoredMsg.ROLE_ASSISTANT
            }
            if (m.status != "SUCCESS" && m.status != "ERROR") {
                oddStatus++
                continue
            }
            if (m.text.isBlank()) {
                blankOrMedia++
                continue
            }
            mapped.add(StoredMsg(role, m.text, m.timestamp))
        }
        var id = "agora-" + sanitizeId(c.id.ifBlank { "conv" })
        var n = 2
        while (!usedIds.add(id)) {
            id = "agora-" + sanitizeId(c.id.ifBlank { "conv" }) + "-" + n
            n++
        }
        sessions.add(AgoraSessionPlan(id, SessionHead(c.title, c.modelId ?: "", c.lastUpdated), mapped))
    }
    oddStatusTotal = oddStatus

    if (blankOrMedia > 0) notes.add("纯附件/空文本消息 ${blankOrMedia} 条没兑（图片视频不在备份里，新版本也不存气泡媒体）")
    if (unknownParticipant > 0) notes.add("身份不明的消息 ${unknownParticipant} 条没兑（旧版工具/系统气泡）")
    if (oddStatusTotal > 0) notes.add("状态异常（非成功也非错误）的消息 ${oddStatusTotal} 条没兑")
    if (tasksCount > 0 || loopsCount > 0) {
        notes.add("定时任务 ${tasksCount} 条、循环 ${loopsCount} 条：新版还没有这功能，没导（已在路线图）")
    }
    if (ignoredEntries > 0) notes.add("包里另有 ${ignoredEntries} 个不认识的条目，原样略过")

    return AgoraImportPlan(
        recognized = true,
        formatVersion = manifest.agora_export_version,
        providerName = providerName,
        baseUrl = baseUrl,
        apiKey = apiKey,
        selectedModel = settings.selectedModel.takeIf { it.isNotBlank() },
        thinkingOn = settings.thinkingEnabled,
        thinkingLevel = thinkingLevel,
        codeExecOn = settings.codeExecutionEnabled,
        webSearchOn = settings.webSearchEnabled,
        shellOn = settings.shellEnabled,
        systemPrompt = systemPrompt,
        sessions = sessions,
        notes = notes,
    )
}

/** 旧 id 只留文件名安全字符，防路径穿越；空了兜底成占位，调用方再撞名去重。 */
private fun sanitizeId(raw: String): String {
    val safe = raw.map { if (it.isLetterOrDigit() || it == '.' || it == '-' || it == '_') it else '_' }
        .joinToString("").take(72)
    return safe.ifEmpty { "conv" }
}

/** 有界读：cap 个字符封顶（UTF-8 由 InputStreamReader 兜住，半个多字节符不会被劈开）。 */
private fun readBoundedText(input: InputStream, cap: Int): String {
    val reader = InputStreamReader(input, StandardCharsets.UTF_8)
    val out = StringBuilder(1 shl 12)
    val buf = CharArray(1 shl 14)
    while (out.length < cap) {
        val n = reader.read(buf, 0, minOf(buf.size, cap - out.length))
        if (n < 0) break
        out.append(buf, 0, n)
    }
    return out.toString()
}

private inline fun <reified T> parseOrNull(json: String): T? =
    runCatching { AGORA_JSON.decodeFromString<T>(json) }.getOrNull()
