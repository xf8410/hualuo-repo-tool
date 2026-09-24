package com.hualuo.engine.logsync

import com.hualuo.engine.github.normalizeGitHubRepo
import com.hualuo.engine.store.SessionStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * 对话日志同步的规划器（纯 JVM，不碰 Android）。
 *
 * 本地事实是 SessionStore 的 JSONL：一个会话一个文件，原文逐行 append。
 * 云端事实是私有仓 xf8410/hualuo-logs 下的 logs/时间戳/part_*.zip + manifest。
 *
 * 规矩：
 *  - 不省略：传的是整文件逐字节，sha 由 FileCourierClient 在 manifest 里对账；
 *  - 不脱敏：原文直通，目标必须是私有仓（调用方在设置页钉死默认值）；
 *  - 不闪退：这里只做 stat（名字/长度/时间），内容只在打卷与上传时按流过路，
 *    永不整文件进内存（CI 红线二盯着）。
 *
 * checkpoint 记每个会话上次传走时的 length:lastModified：
 * 长度或时间任一对不上就 dirty。rename 会重写头行，长度与时间都会变，
 * 自然 dirty，不用另立法。
 */
object LogSyncPlanner {
    const val DEFAULT_REPO = "xf8410/hualuo-logs"
    const val DEFAULT_BRANCH = "main"
    const val DEFAULT_PREFIX_ROOT = "logs/"

    private val json = Json { ignoreUnknownKeys = true }

    fun buildPrefix(nowMs: Long): String =
        DEFAULT_PREFIX_ROOT + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(nowMs)) + "/"

    fun checkpointValueFor(file: File): String =
        file.length().toString() + ":" + file.lastModified().toString()

    /**
     * 扫一遍仓，返回需要同步的会话 id（已排序，调用方按此顺序配卷）。
     * 仓读不动返回空（不抛，调用方按“已是最新”收场，不静默丢字由上层出声）。
     */
    fun planDirty(store: SessionStore, checkpoint: Map<String, String>): List<String> {
        val listing = runCatching { store.list() }.getOrNull() ?: return emptyList()
        val dirty = ArrayList<String>()
        for ((id, _) in listing.heads) {
            val file = runCatching { store.pathOf(id) }.getOrNull() ?: continue
            if (!file.isFile) continue
            val current = checkpointValueFor(file)
            if (checkpoint[id] != current) dirty += id
        }
        return dirty.sorted()
    }

    fun encodeCheckpoint(checkpoint: Map<String, String>): String = buildJsonObject {
        checkpoint.toSortedMap().forEach { (id, value) -> put(id, value) }
    }.toString()

    fun decodeCheckpoint(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        val root = runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonObject
            ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        root.forEach { (key, value) ->
            val text = (value as? JsonPrimitive)?.contentOrNull?.trim()
            if (key.isNotBlank() && !text.isNullOrEmpty()) out[key] = text
        }
        return out
    }

    fun normalizeRepo(raw: String?): String? {
        if (raw.isNullOrBlank()) return DEFAULT_REPO
        return normalizeGitHubRepo(raw.trim()) ?: return null
    }

    fun normalizeBranch(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        return trimmed.ifBlank { DEFAULT_BRANCH }
    }
}
