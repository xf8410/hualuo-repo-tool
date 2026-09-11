package com.hualuo.engine.io

import java.io.File
import java.net.URLDecoder
import java.util.Locale

/**
 * 解压安全闸：任何"把压缩包里的东西写到磁盘"的动作，落笔之前必须先过这里。
 *
 * 为什么要单独一个文件：旧 Agora 直接把包内条目名拼到目标目录上就写，
 * 一个 `../..` 就能把文件写到应用私有目录外面；zip 炸弹也是一条命令把整机磁盘写满。
 * 这两类不是"性能问题"，是**能造成不可逆后果**的问题，所以做成硬闸：
 * 超限不是"少解一个文件"，而是当场抛错、整次解压作废，由调用方删掉已写出来的部分。
 *
 * 四道上限（对应旧 Agora 缺的四件事）：条目数 / 单条目体积 / 整包总量 / 压缩比。
 * 压缩比那道对炸弹最灵敏：几十 KB 解出几个 G，只有这一道能提前拦住。
 *
 * CI 教出来的两条规矩（别再依赖平台行为）：
 *   1) 校验一律在**字符串层面**做，不靠 java.io.File 帮忙规范化——Linux 上反斜杠
 *      是合法文件名，Windows 上 `C:` 能挂出"同一目录的两种写法"（C: 与 C:\），
 *      把这些交给 File 比较就会漏。现在全部拆成段、逐段判、最后才比前缀。
 *   2) 异常消息里带用户可控的原始名时，先脱敏（去控制字符 + 限长），
 *      否则一个换行就能在日志里伪造出一条"看起来是我自己打印的"记录。
 */
class ExtractReject(reason: String) : Exception(reason)

/** 上限全部可注入，是为了让测试能用几十字节验证，而不是造一个几个 G 的包。 */
data class ExtractLimits(
    val maxEntries: Int = 20000,
    val maxEntryBytes: Long = 512L * 1024 * 1024,
    val maxTotalBytes: Long = 2048L * 1024 * 1024,
    val maxRatio: Double = 200.0,
) {
    init {
        if (maxEntries <= 0) throw IllegalArgumentException("条目数上限必须大于零，现在是 $maxEntries")
        if (maxEntryBytes <= 0) throw IllegalArgumentException("单条目上限必须大于零，现在是 $maxEntryBytes")
        if (maxTotalBytes < maxEntryBytes) {
            throw IllegalArgumentException("总量上限($maxTotalBytes)不能小于单条目上限($maxEntryBytes)，否则单条目上限永远无效")
        }
        if (maxRatio <= 1.0) throw IllegalArgumentException("压缩比上限必须大于一，现在是 $maxRatio")
    }

    fun describe(): String =
        "条目数≤$maxEntries，单条目≤${maxEntryBytes}字节，总量≤${maxTotalBytes}字节，压缩比≤$maxRatio"
}

/** 用户可控文本进日志/异常消息之前一律脱敏：控制字符换成点，超过 120 字符截断。 */
internal fun sanitizeForLog(raw: String): String {
    val clean = raw.map { if (it.code < 0x20 || it.code == 0x7F) '.' else it }
        .joinToString("")
    return if (clean.length <= 120) clean else clean.take(117) + "等截"
}

/**
 * 条目名先还原再校验，顺序不能反。
 *
 * 很多打包工具（以及网页下载落下来的名字）会把中文写成百分号编码；
 * 如果先按字面量校验、后还原，`%2e%2e%2f` 这种就绕过了全部检查——还原完才发现是 `../`。
 * 所以这里**反复还原直到不再变化**（最多三轮），双层编码也压平，然后兜底判断：
 * 还原完的结果只要有任何一段是 `..`、或者带绝对路径/盘符，一律拒绝。
 * 加号是文件名里的字面量，不能当空格，所以先把它转义回去；
 * 还原失败（半截百分号之类）就保留原样——反正下面还有一整套硬校验。
 */
fun percentDecodeName(raw: String): String {
    var current = raw
    repeat(3) {
        val next = runCatching {
            URLDecoder.decode(current.replace("+", "%2B"), "UTF-8")
        }.getOrElse { return@repeat }
        if (next == current) return@percentDecodeName current
        current = next
    }
    return current
}

/** 一段名字是不是盘符：`C:` `D:` 老写法，以及用竖线绕检查的 `C|`。 */
private fun isDriveSegment(segment: String): Boolean =
    segment.length == 2 && segment[segment.lastIndex] == ':' &&
        segment[0].isLetterOrDigit() ||
        segment.length == 2 && segment[segment.lastIndex] == '|' && segment[0].isLetterOrDigit()

/**
 * 把包内条目名翻译成本机上的落点，并且**只允许落在 targetRoot 里面**。
 *
 * 拦的五样：空名/含零字节、还原后仍藏着的越界、绝对路径（含 UNC）、盘符、任意一层 `..`；
 * 最后还有 canonical 前缀复核，专治"名字干净但中间某层是符号链接"。
 */
fun normalizeEntryPath(targetRoot: File, rawName: String): File {
    if (rawName.indexOf('\u0000') >= 0) throw ExtractReject("条目名里含零字节，拒绝")
    val decoded = percentDecodeName(rawName).trim()
    if (decoded.isEmpty()) throw ExtractReject("条目名为空，无法确定落点")
    if (decoded.indexOf('\u0000') >= 0) throw ExtractReject("还原后的条目名含零字节，拒绝")

    // 斜杠与反斜杠一律当分隔符。Windows 解包工具确实会写出反斜杠条目名，
    // 而 Linux 上它是合法文件名字符——不能指望 File 替我们认它。
    val segments = decoded.split('/', '\\').filter { it.isNotEmpty() && it != "." }
    val label = sanitizeForLog(rawName)

    if (segments.any { it == ".." }) {
        throw ExtractReject("条目名试图跳出目标目录（含 .. ），拒绝：$label")
    }
    if (segments.any { it.codePointCount(0, it.length) == 0 }) {
        throw ExtractReject("条目名归一化之后什么都不剩，拒绝：$label")
    }
    if (decoded.startsWith("//")) {
        throw ExtractReject("条目名是网络共享路径（UNC），拒绝：$label")
    }
    if (segments.firstOrNull()?.isEmpty() == true || decoded.startsWith("/")) {
        throw ExtractReject("条目名是绝对路径，拒绝：$label")
    }
    if (segments.isNotEmpty() && isDriveSegment(segments.first())) {
        throw ExtractReject("条目名带盘符，拒绝：$label")
    }
    if (segments.isEmpty()) throw ExtractReject("条目名拆完是空的，拒绝：$label")

    val dest = segments.fold(targetRoot) { acc, part -> File(acc, part) }
    val rootCanon = targetRoot.invariantCanonicalPath()
    val destCanon = dest.invariantCanonicalPath()
    if (destCanon != rootCanon && !destCanon.startsWith(rootCanon + File.separator)) {
        throw ExtractReject("条目落点跑到目标目录外面（多半是符号链接在作怪），拒绝：$label → ${sanitizeForLog(destCanon)}")
    }
    return dest
}

/**
 * canonicalPath 在"路径还不存在"时也要能算，且不许抛 IOException 打断校验；
 * 取不到就退回规范化绝对路径——两种情况都走同一套前缀比较，越界仍然拦得住。
 */
private fun File.invariantCanonicalPath(): String =
    runCatching { canonicalPath }.getOrElse { absoluteFile.normalize().path }

/**
 * 有状态的解压配额器：一次解压用一个实例，绝不复用。
 *
 * 用法是硬性的三步，缺一步就不安全：
 *   beginEntry(根目录, 条目名, 压缩前字节) → 边搬边 accept(每次字节数) → endEntry(压缩前字节)
 * accept 是"已经打算搬这么多"就先记账再搬，所以越限时连一个字节都不会写出去。
 */
class ExtractGuard(private val limits: ExtractLimits = ExtractLimits()) {

    private var entryCount = 0
    private var currentEntryBytes = 0L
    private var totalBytes = 0L

    val entries: Int get() = entryCount
    val decompressedTotal: Long get() = totalBytes

    fun beginEntry(targetRoot: File, rawName: String, compressedBytes: Long): File {
        if (entryCount + 1 > limits.maxEntries) {
            throw ExtractReject("条目数已达上限 ${limits.maxEntries}（这次是第 ${entryCount + 1} 个），整包作废：${limits.describe()}")
        }
        val dest = normalizeEntryPath(targetRoot, rawName)
        entryCount += 1
        currentEntryBytes = 0L
        return dest
    }

    fun accept(byteCount: Long) {
        if (byteCount == 0L) return
        if (byteCount < 0L) throw IllegalArgumentException("字节数不能为负：$byteCount")
        currentEntryBytes += byteCount
        totalBytes += byteCount
        if (currentEntryBytes > limits.maxEntryBytes) {
            throw ExtractReject("单条目解压后已达 $currentEntryBytes 字节，超过上限 ${limits.maxEntryBytes}，整包作废")
        }
        if (totalBytes > limits.maxTotalBytes) {
            throw ExtractReject("整包解压后已达 $totalBytes 字节，超过总量上限 ${limits.maxTotalBytes}，整包作废")
        }
    }

    fun endEntry(compressedBytes: Long) {
        if (compressedBytes > 0L && currentEntryBytes > 0L) {
            val ratio = currentEntryBytes.toDouble() / compressedBytes.toDouble()
            if (ratio > limits.maxRatio) {
                val shown = String.format(Locale.US, "%.1f", ratio)
                val limit = String.format(Locale.US, "%.1f", limits.maxRatio)
                throw ExtractReject("压缩比 $shown 超过上限 $limit（压缩前 $compressedBytes 字节，解压后 $currentEntryBytes 字节），疑似 zip 炸弹，整包作废")
            }
        }
        currentEntryBytes = 0L
    }
}
