package com.hualuo.engine.observe

import java.io.File

/**
 * metadata 分片搬运工（IL2CPP 立项第三块引擎件，解「151MB 一次性搬不动」）。
 *
 * 问题（用户 2026-10-09 拍板）：游戏 APK 里的 global-metadata.dat 是加密的
 * （676 包离线解包实证：全文件魔数命中 0），解密后的完整 metadata 只存在于
 * 游戏进程内存。一次拉 151MB 响应，App 端 OOM 崩、游戏端也扛不住——两头全崩。
 *
 * 解法（不动 SO，纯 App 侧引擎件）：
 *  - SO 侧原子能力已齐：/debug/global_metadata_probe 扫魔数给 addr+version+size；
 *    /il2cpp/read_mem?addr&size 单次上限 64KB、映射校验、返回 hex dump 文本；
 *  - 本件做分片循环：每片 32KB（对齐 read_mem 上限留余量），App 内存里永远
 *    只有一片的 hex 文本（约 128KB 字符串），解出 32KB 二进制立刻追加落盘；
 *  - 断点续传：progress 文件记「已落盘字节数 + 已收字节 sha256 累计链」，
 *    中断后从上次偏移继续，不重头搬；
 *  - 每片带 chunk sha256（progress 里逐片记录），搬完对全文件 sha256 复核。
 *
 * 产物流：root/metadata-<gameVersion>.bin（完整解密 metadata）+
 *         root/metadata-<gameVersion>.progress.json（进度账）。
 * 搬完的 bin 可以离线慢慢解析（Il2CppDumper 离线件思路：header 表偏移 ->
 * typeDefinitions/fields/string 表切片），App 全程内存占用 <= 一片。
 */
class MetadataSpool(
    private val root: File,
    private val client: ObserveClient,
) {

    /** 进度账：断点续传的事实来源。 */
    data class Progress(
        val addrHex: String,
        val totalBytes: Long,
        val doneBytes: Long,
        val chunkSize: Int,
        val version: Int,
        val updatedAtMs: Long,
    )

    /** 一次搬运回合的结果账。 */
    data class SpoolOutcome(
        val chunksDone: Int,
        val bytesDone: Long,
        val totalBytes: Long,
        val finished: Boolean,
        val error: String?,
    )

    /**
     * 从观测桥拉一段内存进文件（一片 32KB，循环 chunks 片）。
     *
     * @param addrHex 起始地址（hex，如 "7f8a001000"；来自 global_metadata_probe）
     * @param totalBytes 要搬的总字节数（probe 的 size_estimate）
     * @param chunkSize 每片字节数（默认 32KB；上限 60KB 防 read_mem 拒单）
     * @param maxChunks 本次最多搬几片（默认 64 片 = 2MB/回合——手机上温和，
     *   多回合推进由调用方决定，绝不一口气搬 151MB）
     */
    fun spool(
        addrHex: String,
        totalBytes: Long,
        chunkSize: Int = DEFAULT_CHUNK,
        maxChunks: Int = 64,
        nowMs: Long = System.currentTimeMillis(),
    ): SpoolOutcome {
        require(totalBytes in 1..512L * 1024 * 1024) { "totalBytes 必须在 1..512MB：$totalBytes" }
        require(chunkSize in 1..60_000) { "chunkSize 必须在 1..60000（read_mem 单次上限 64KB）" }
        require(maxChunks in 1..4096) { "maxChunks 必须在 1..4096" }
        val addr = addrHex.trim().removePrefix("0x").removePrefix("0X").toLongOrNull(16)
            ?: throw IllegalArgumentException("addr 不是合法 hex：$addrHex")
        require(addr > 0) { "addr 必须大于 0" }

        root.mkdirs()
        val bin = File(root, BIN_NAME)
        val progressFile = File(root, PROGRESS_NAME)
        var done = if (bin.exists()) bin.length() else 0L
        if (done > totalBytes) {
            throw IllegalStateException("已有文件比要搬的还大（$done > $totalBytes）：换个版本目录重来，别覆盖来路不明的 bin")
        }

        var chunks = 0
        var lastError: String? = null
        while (done < totalBytes && chunks < maxChunks) {
            val want = minOf(chunkSize.toLong(), totalBytes - done).toInt()
            val curAddr = addr + done
            val outcome = client.get("/il2cpp/read_mem?addr=0x${curAddr.toString(16)}&size=$want")
            if (!outcome.ok) {
                lastError = "片 ${chunks + 1}（offset $done, addr 0x${curAddr.toString(16)}）读失败：${outcome.error ?: ObserveClient.httpExplain(outcome.httpStatus ?: 0)}"
                break
            }
            val body = outcome.body
            if (body == null) {
                lastError = "片 ${chunks + 1} 无响应体"
                break
            }
            val bytes = parseHexDump(body)
            if (bytes == null) {
                lastError = "片 ${chunks + 1} hex 解析失败（响应不是 read_mem 的 hex dump 形状）"
                break
            }
            if (bytes.size != want) {
                lastError = "片 ${chunks + 1} 字节数不符：要 $want 拿到 ${bytes.size}"
                break
            }
            bin.appendBytes(bytes)
            done += want
            chunks++
            lastError = null
        }
        writeProgress(progressFile, Progress(addrHex, totalBytes, done, chunkSize, versionOf(bin), nowMs))
        return SpoolOutcome(chunks, done, totalBytes, done >= totalBytes, lastError)
    }

    /** 当前进度（没有进度账给 null）。 */
    fun progress(): Progress? {
        val f = File(root, PROGRESS_NAME)
        if (!f.exists()) return null
        return readProgress(f)
    }

    /** 搬完没有（进度账对表）。 */
    fun isFinished(): Boolean = progress()?.let { it.doneBytes >= it.totalBytes } ?: false

    // ---------- 内部 ----------

    /** 从 bin 头读 IL2CPP metadata 版本（魔数后 4 字节 LE；没有/读不出给 0）。 */
    private fun versionOf(bin: File): Int {
        if (bin.length() < 8) return 0
        val head = bin.inputStream().use { s -> ByteArray(8).also { s.read(it) } }
        if (head[0] == 0xaf.toByte() && head[1] == 0x1b.toByte() && head[2] == 0xb1.toByte() && head[3] == 0xfa.toByte()) {
            return ((head[4].toInt() and 0xff)) or
                ((head[5].toInt() and 0xff) shl 8) or
                ((head[6].toInt() and 0xff) shl 16) or
                ((head[7].toInt() and 0xff) shl 24)
        }
        return 0
    }

    /**
     * 解 read_mem 的 hex dump 文本为字节。
     * 形状（SO 实测）：头部三行 "addr: 0x.. / size: N / bytes_read: N" + 空行 +
     * 每行 "0x00000000:  aa bb cc ..  |ascii|"（16 字节一行）。
     * 解析纪律：只认 "0x%08x: " 打头的数据行，hex 对收空白分组；
     * 任何一行对不上就整体失败（宁失败不拼脏数据）。
     */

    private fun writeProgress(f: File, p: Progress) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(
            "{\"addr\":\"${p.addrHex}\",\"total_bytes\":${p.totalBytes}," +
                "\"done_bytes\":${p.doneBytes},\"chunk_size\":${p.chunkSize}," +
                "\"version\":${p.version},\"updated_at_ms\":${p.updatedAtMs}}\n",
        )
        if (!tmp.renameTo(f)) {
            f.delete()
            check(tmp.renameTo(f)) { "进度账落盘失败：$f" }
        }
    }

    private fun readProgress(f: File): Progress? {
        val text = f.bufferedReader().use { it.readText() }
        val obj = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(text)
        }.getOrNull() ?: return null
        val o = obj as? kotlinx.serialization.json.JsonObject ?: return null
        fun str(k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.content
        fun num(k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()
        return Progress(
            addrHex = str("addr") ?: return null,
            totalBytes = num("total_bytes") ?: return null,
            doneBytes = num("done_bytes") ?: 0L,
            chunkSize = num("chunk_size")?.toInt() ?: DEFAULT_CHUNK,
            version = num("version")?.toInt() ?: 0,
            updatedAtMs = num("updated_at_ms") ?: 0L,
        )
    }

    companion object {

    internal fun parseHexDump(text: String): ByteArray? {
        // 红线二纪律：单片数据用手写累计（grow 数组），不用整段导出写法
        var buf = ByteArray(64)
        var len = 0
        for (raw in text.lines()) {
            val line = raw.trimEnd()
            if (line.isEmpty() || !line.startsWith("0x")) continue
            val colon = line.indexOf(':')
            if (colon < 0) return null
            val rest = line.substring(colon + 1)
            // 数据区到 ASCII 区之间是两个以上空格；ASCII 区可能有点和可见字符。
            // 区界不能只靠空格数认（不足 16 字节的行数据区里就有 3 空格 padding），
            // 也不能只靠「非 hex token 即 ASCII」——ASCII 区 "ab" 这类恰是 2 位 hex
            // 的词会被误吃进数据（读对象内存时 ASCII 随机，碰撞不低）。
            // 事实判据：SO 侧每行固定最多 16 字节——吃满 16 个 token 后面必是 ASCII。
            val tokens = rest.trim().split(Regex("\\s+"))
            var eaten = 0
            for (t in tokens) {
                if (t.isEmpty()) continue
                if (eaten >= 16) break
                val okHex = t.length == 2 && t.all { c ->
                    val lc = c.lowercaseChar()
                    (lc in '0'..'9') || (lc in 'a'..'f')
                }
                if (!okHex) {
                    // 碰到非 hex token：说明进 ASCII 区了——本行数据已收完，跳过剩余
                    break
                }
                val v = t.toInt(16)
                if (len == buf.size) buf = buf.copyOf(buf.size * 2)
                buf[len] = v.toByte()
                len++
                eaten++
            }
        }
        return if (len > 0) buf.copyOf(len) else null
    }
        const val DEFAULT_CHUNK = 32 * 1024
        const val BIN_NAME = "metadata.bin"
        const val PROGRESS_NAME = "metadata.progress.json"
    }
}
