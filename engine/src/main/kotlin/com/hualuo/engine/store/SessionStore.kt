package com.hualuo.engine.store

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter

/**
 * 会话头（jsonl 首行）：标题、用的模型、创建时刻。
 * title 允许空——新建会话还没说过话时就是没标题，不许编一个装样子。
 */
data class SessionHead(
    val title: String,
    val model: String,
    val createdAtMs: Long,
)

/** 会话列表的整仓回执：能报头的按活动会话优先、再按新在前排好。 */
data class SessionListing(val heads: List<Pair<String, SessionHead>>, val unreadable: Int)

/**
 * 落盘的一条消息。role 只认三个真值：
 *  - user / assistant：真说过话的气泡；
 *  - error：我方对失败的说明卡——它照实存，但喂回模型时永远剔掉，
 *    「错误卡不进历史」的老规矩从内存一路贯到盘上。
 * incomplete 标半截回答：重载回来还带着这个标记，不拿断话冒充成品。
 */
data class StoredMsg(
    val role: String,
    val text: String,
    val atMs: Long,
    val incomplete: Boolean = false,
) {
    init {
        require(role == ROLE_USER || role == ROLE_ASSISTANT || role == ROLE_ERROR) {
            "消息角色只认 user/assistant/error，来了个「$role」"
        }
    }

    companion object {
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
        const val ROLE_ERROR = "error"
    }
}

/** 一次读取的完整回执：坏了几行，数着报，不许悄悄丢。 */
data class LoadedSession(
    val id: String,
    val head: SessionHead?,
    val messages: List<StoredMsg>,
    val badLines: Int,
)

/** 喂模型的历史裁剪结果：砍了多少条、剔了几条错误/空话，界面必须拿这个数出声。 */
data class FeedResult(
    val feed: List<Pair<String, String>>,
    val trimmedCount: Int,
    val droppedNonFeedable: Int,
)

/**
 * 会话仓：一个会话一个 JSONL 文件，杀进程重开还聊得下去。
 *
 * 这层现在有两条“不能丢对话”的保险：
 *  1. 创建先写旁路临时文件再 rename，进程被杀不会留下半截会话头；
 *  2. 记住最近打开/新建的会话，列表启动时优先把它排第一，不按创建时间擅自换台。
 *
 * 没有头的旧文件或坏头文件，只要还能读出消息，仍会被列入库；完全读不出内容的文件
 * 才计入 unreadable，不能因为第一行坏了就把后面的正文从抽屉里抹掉。
 */
class SessionStore(private val dir: File) {

    private val activePointer = File(dir, ".active-session")

    init {
        if (!dir.exists()) dir.mkdirs()
    }

    /** 新会话：拿毫秒 + 进程内序号当 id，同毫秒撞不上。 */
    fun create(model: String): String {
        var id = "s" + System.currentTimeMillis() + "-" + SEQ.incrementAndGet()
        var guard = 0
        while (file(id).exists()) {
            id = "s" + System.currentTimeMillis() + "-" + SEQ.incrementAndGet()
            if (++guard > 64) error("新建会话撞名撞了 64 次，目录八成被人动过：$dir")
        }
        val target = file(id)
        val tmp = File(dir, target.name + ".tmp")
        try {
            tmp.writeText(headJson(SessionHead("", model, System.currentTimeMillis())) + "\n")
            if (!tmp.renameTo(target)) {
                tmp.delete()
                error("会话头临时文件改名失败：$tmp")
            }
            rememberActive(id)
            return id
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    fun exists(id: String): Boolean = file(id).exists()

    /** 会话落盘的完整路径（界面诊断、测试都要用；id 走同一套净化，出不了本目录）。 */
    fun pathOf(id: String): File = file(id)

    /** 追加一条：单行 JSONL；进程被杀时最坏只坏最后半行，前面内容仍能读回。 */
    fun append(id: String, msg: StoredMsg): Boolean {
        val f = file(id)
        if (!f.exists()) return false
        return runCatching {
            FileOutputStream(f, true).use { output ->
                OutputStreamWriter(output, Charsets.UTF_8).use { writer ->
                    writer.write(msgJson(msg))
                    writer.write("\n")
                    writer.flush()
                }
            }
            true
        }.getOrDefault(false)
    }

    /**
     * 整写一个会话（迁移/导入用）：head 在前、消息按给定顺序逐行写。
     * 目标已存在就拒绝（返回 false）——导入不许悄悄盖掉用户手里的会话；
     * 先写 .tmp 再改名，中途崩了原文件不陪葬。
     */
    fun writeSession(id: String, head: SessionHead, messages: List<StoredMsg>): Boolean {
        val f = file(id)
        if (f.exists()) return false
        val body = StringBuilder(headJson(head)).apply {
            messages.forEach { append('\n').append(msgJson(it)) }
            append('\n')
        }
        val tmp = File(dir, f.name + ".tmp")
        return try {
            tmp.writeText(body.toString())
            if (!tmp.renameTo(f)) {
                tmp.delete()
                false
            } else {
                true
            }
        } catch (_: Exception) {
            tmp.delete()
            false
        }
    }

    /** 给会话补标题（首条用户话截几个字由调用方决定，这里只负责改写头行）。 */
    fun rename(id: String, title: String): Boolean {
        val loaded = load(id) ?: return false
        val head = (loaded.head ?: SessionHead("", "", System.currentTimeMillis())).copy(title = title)
        val rewritten = StringBuilder(headJson(head)).apply {
            loaded.messages.forEach { append('\n').append(msgJson(it)) }
            append('\n')
        }
        val tmp = File(dir, file(id).name + ".tmp")
        return try {
            tmp.writeText(rewritten.toString())
            if (!tmp.renameTo(file(id))) {
                tmp.delete()
                false
            } else {
                rememberActive(id)
                true
            }
        } catch (_: Exception) {
            tmp.delete()
            false
        }
    }

    /**
     * 整读一个会话：坏行数着报；头行读不懂就 head=null（消息照给，界面自己决定怎么出声）。
     * 每次成功读到文件都会更新活动会话指针，重开后继续打开原来的对话。
     */
    fun load(id: String): LoadedSession? {
        val f = file(id)
        if (!f.exists()) return null
        rememberActive(id)
        var head: SessionHead? = null
        var headTried = false
        val msgs = ArrayList<StoredMsg>()
        var bad = 0
        f.forEachLine { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEachLine
            if (!headTried) {
                headTried = true
                parseHead(line)?.let { head = it; return@forEachLine }
                // 首行不是头：不判死刑，落到消息解析再试一把
            }
            parseMsg(line)?.let { msgs += it } ?: run { bad++ }
        }
        return LoadedSession(id, head, msgs, bad)
    }

    /**
     * 会话列表：活动会话优先，其后按新在前。头坏但正文还能读出的文件也保留，
     * 防止“进程刚好死在写头那一行”让整段对话从抽屉里消失。
     */
    fun list(): SessionListing {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") } ?: emptyArray()
        val heads = ArrayList<Pair<String, SessionHead>>(files.size)
        var unreadable = 0
        for (f in files) {
            val id = f.nameWithoutExtension
            val first = runCatching { f.bufferedReader().use { it.readLine() } }.getOrNull()
            val head = first?.let { parseHead(it.trim()) }
            if (head != null) {
                heads += id to head
                continue
            }
            val recovered = runCatching { loadWithoutRemembering(id) }.getOrNull()
            if (recovered != null && recovered.messages.isNotEmpty()) {
                heads += id to SessionHead("", "", f.lastModified().coerceAtLeast(1L))
            } else {
                unreadable++
            }
        }
        val active = readActiveId()
        heads.sortWith(
            compareByDescending<Pair<String, SessionHead>> { it.first == active }
                .thenByDescending { it.second.createdAtMs },
        )
        return SessionListing(heads, unreadable)
    }

    /** 删会话：整文件删。活动指针也一起清掉，下一次发送自然开新会话。 */
    fun delete(id: String): Boolean {
        val f = file(id)
        val deleted = f.delete()
        if (readActiveId() == f.nameWithoutExtension) activePointer.delete()
        return deleted
    }

    /**
     * 组喂模型的历史（带盘版）：错误卡与空文本不进；超上限掐头留尾，砍数如实报。
     */
    fun feedFor(id: String, maxTurns: Int): FeedResult {
        val loaded = load(id) ?: return FeedResult(emptyList(), 0, 0)
        val feedable = loaded.messages.filter { it.role != StoredMsg.ROLE_ERROR && it.text.isNotBlank() }
        val dropped = loaded.messages.size - feedable.size
        val keep = feedable.takeLast(maxTurns.coerceAtLeast(0))
        return FeedResult(keep.map { it.role to it.text }, feedable.size - keep.size, dropped)
    }

    /** 最近打开的会话 id；只作为排序提示，丢了也能回退到最新会话。 */
    fun activeSessionId(): String? = readActiveId()

    private fun rememberActive(id: String) {
        val clean = file(id).nameWithoutExtension
        runCatching { activePointer.writeText(clean) }
    }

    private fun readActiveId(): String? =
        runCatching { activePointer.readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }

    private fun loadWithoutRemembering(id: String): LoadedSession? {
        val f = file(id)
        if (!f.exists()) return null
        var head: SessionHead? = null
        var headTried = false
        val msgs = ArrayList<StoredMsg>()
        var bad = 0
        f.forEachLine { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEachLine
            if (!headTried) {
                headTried = true
                parseHead(line)?.let { head = it; return@forEachLine }
            }
            parseMsg(line)?.let { msgs += it } ?: run { bad++ }
        }
        return LoadedSession(id, head, msgs, bad)
    }

    /** 只留文件名安全字符（字母数字点横杠下划线），其余换下划线：防路径穿越写盘。 */
    private fun file(id: String): File {
        val safe = id.map { if (it.isLetterOrDigit() || it == '.' || it == '-' || it == '_') it else '_' }
            .joinToString("").take(80)
        require(safe.isNotEmpty()) { "会话 id 不能是空的" }
        return File(dir, "$safe.jsonl")
    }

    // ── 手写 JSONL 编解码 ──────────────────────────────────────────────────

    private fun headJson(h: SessionHead) = buildString {
        append("{\"k\":\"h\",")
        append("\"title\":").append(jsonEscape(h.title)).append(',')
        append("\"model\":").append(jsonEscape(h.model)).append(',')
        append("\"at\":").append(h.createdAtMs)
        append('}')
    }

    private fun msgJson(m: StoredMsg) = buildString {
        append("{\"k\":\"m\",")
        append("\"role\":").append(jsonEscape(m.role)).append(',')
        append("\"text\":").append(jsonEscape(m.text)).append(',')
        append("\"at\":").append(m.atMs)
        if (m.incomplete) append(",\"incomplete\":true")
        append('}')
    }

    private fun jsonEscape(s: String): String {
        val out = StringBuilder(s.length + 8).append('"')
        for (c in s) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (c < ' ') out.append("\\u").append(c.code.toString(16).padStart(4, '0')) else out.append(c)
            }
        }
        return out.append('"').toString()
    }

    private fun parseHead(line: String): SessionHead? {
        val obj = simpleJson(line) ?: return null
        if (obj["k"] != "h") return null
        val title = obj["title"] ?: return null
        val model = obj["model"] ?: return null
        val at = obj["at"]?.toLongOrNull() ?: return null
        return SessionHead(title, model, at)
    }

    private fun parseMsg(line: String): StoredMsg? {
        val obj = simpleJson(line) ?: return null
        if (obj["k"] != "m") return null
        val role = obj["role"] ?: return null
        if (role != StoredMsg.ROLE_USER && role != StoredMsg.ROLE_ASSISTANT && role != StoredMsg.ROLE_ERROR) return null
        val text = obj["text"] ?: return null
        val at = obj["at"]?.toLongOrNull() ?: 0L
        val incomplete = obj["incomplete"] == "true"
        return StoredMsg(role, text, at, incomplete)
    }

    private fun simpleJson(line: String): Map<String, String>? {
        if (!line.startsWith("{") || !line.endsWith("}")) return null
        val body = line.substring(1, line.length - 1)
        if (body.isEmpty()) return emptyMap()
        val out = HashMap<String, String>()
        var i = 0
        while (i < body.length) {
            if (body[i] != '"') return null
            val keyEnd = indexOfQuote(body, i + 1)
            if (keyEnd < 0) return null
            val key = unescape(body.substring(i + 1, keyEnd))
            i = keyEnd + 1
            if (i >= body.length || body[i] != ':') return null
            i++
            when {
                body[i] == '"' -> {
                    val valEnd = indexOfQuote(body, i + 1)
                    if (valEnd < 0) return null
                    out[key] = unescape(body.substring(i + 1, valEnd))
                    i = valEnd + 1
                }
                body.startsWith("true", i) -> { out[key] = "true"; i += 4 }
                else -> {
                    var j = i
                    while (j < body.length && body[j] != ',') j++
                    out[key] = body.substring(i, j).trim()
                    i = j
                }
            }
            if (i < body.length) {
                if (body[i] != ',') return null
                i++
            }
        }
        return out
    }

    private fun indexOfQuote(s: String, from: Int): Int {
        var j = from
        while (j < s.length) {
            when (s[j]) {
                '\\' -> j += 2
                '"' -> return j
                else -> j++
            }
        }
        return -1
    }

    private fun unescape(s: String): String {
        if (!s.contains('\\')) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\') { out.append(c); i++; continue }
            i++
            if (i >= s.length) return s
            when (val e = s[i]) {
                '"' -> out.append('"')
                '\\' -> out.append('\\')
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
                'u' -> {
                    if (i + 4 >= s.length) return s
                    val code = s.substring(i + 1, i + 5).toIntOrNull(16) ?: return s
                    out.append(code.toChar())
                    i += 4
                }
                else -> return s
            }
            i++
        }
        return out.toString()
    }

    private companion object {
        val SEQ = java.util.concurrent.atomic.AtomicInteger()
    }
}
