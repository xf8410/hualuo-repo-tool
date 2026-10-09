package com.hualuo.engine.observe

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 字段卡仓（IL2CPP 立项第二块引擎件）：按游戏版本存观测桥 dump 的类字段表，
 * 供跨版本对账（OffsetProbe.diff）与冷启动流水复用。
 *
 * 一张卡 = 一个类在一个游戏版本下的字段清单（name/offset/type_name），
 * 来源是观测桥 /fields/<class>（hlpatch enumerate_class_fields 实测形状）。
 *
 * 目录结构：root/<gameVersion>/<classSimpleName>.json
 *   gameVersion 由调用方给（如 "686"、"676"——游戏版本号，不是 IL2CPP 版本）。
 *
 * 家规（对齐 TaskStore 的落盘纪律）：
 *  - 整文件重写 + 原子换名（tmp -> 目标），半截写不打脏成品；
 *  - 读到坏 JSON 如实报（抛 IllegalArgumentException 带文件名），不静默跳过；
 *  - 类名做文件名安全化（路径穿越/分隔符一律拒或替换）；
 *  - 版本目录不自动建在构造里——写卡时才建（只读用户也能拿这个仓指路）。
 */
class FieldCardStore(private val root: File) {

    /** 一张字段卡（与 OffsetProbe.FieldCard 同构，序列化形状见 [save]）。 */
    data class Card(
        val gameVersion: String,
        val className: String,
        val fields: List<OffsetProbe.FieldCard>,
        val capturedAtMs: Long,
    )

    /** 保存一张卡（整文件重写，原子落盘）。类名不合法当场拒。 */
    fun save(card: Card) {
        require(card.gameVersion.isNotBlank()) { "gameVersion 不许空" }
        val safe = safeFileName(card.className)
        val dir = File(root, card.gameVersion)
        dir.mkdirs()
        val target = File(dir, "$safe.json")
        val tmp = File(dir, "$safe.json.tmp")
        val body = buildString {
            append("{\n")
            append("  \"game_version\": \"")
            append(jsonEscape(card.gameVersion))
            append("\",\n")
            append("  \"class_name\": \"")
            append(jsonEscape(card.className))
            append("\",\n")
            append("  \"captured_at_ms\": ")
            append(card.capturedAtMs)
            append(",\n")
            append("  \"fields\": [\n")
            card.fields.forEachIndexed { i, f ->
                append("    {\"name\": \"")
                append(jsonEscape(f.name))
                append("\", \"offset\": ")
                append(f.offset)
                append(", \"type_name\": \"")
                append(jsonEscape(f.typeName))
                append("\"}")
                append(if (i == card.fields.lastIndex) "\n" else ",\n")
            }
            append("  ]\n")
            append("}\n")
        }
        tmp.writeText(body)
        if (!tmp.renameTo(target)) {
            target.delete()
            check(tmp.renameTo(target)) { "落盘失败：$target（重试删目标后仍失败）" }
        }
    }

    /** 读一张卡；没有这张卡返回 null，坏 JSON 抛异常（带文件路径）。 */
    fun load(gameVersion: String, className: String): Card? {
        val safe = safeFileName(className)
        val f = File(File(root, gameVersion), "$safe.json")
        if (!f.exists()) return null
        val text = f.bufferedReader().use { it.readText() }
        val obj = runCatching { Json.parseToJsonElement(text).jsonObject }
            .getOrElse { throw IllegalArgumentException("字段卡坏了（$f）：${it.message}") }
        val fields = (obj["fields"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { el ->
            val fo = el as? JsonObject ?: return@mapNotNull null
            OffsetProbe.FieldCard(
                name = fo["name"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                offset = fo["offset"]?.jsonPrimitive?.content?.toIntOrNull() ?: return@mapNotNull null,
                typeName = fo["type_name"]?.jsonPrimitive?.content ?: "?",
            )
        } ?: throw IllegalArgumentException("字段卡没有 fields 数组（$f）")
        return Card(
            gameVersion = obj["game_version"]?.jsonPrimitive?.content ?: gameVersion,
            className = obj["class_name"]?.jsonPrimitive?.content ?: className,
            fields = fields,
            capturedAtMs = obj["captured_at_ms"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
        )
    }

    /** 列某版本下已有的卡（类名清单，按字典序）。版本目录不存在给空表。 */
    fun list(gameVersion: String): List<String> {
        val dir = File(root, gameVersion)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.map { it.name.removeSuffix(".json") }
            ?.sorted()
            ?: emptyList()
    }

    /** 列全部版本目录（按名排序）。 */
    fun versions(): List<String> {
        if (!root.isDirectory) return emptyList()
        return root.listFiles { f -> f.isDirectory }?.map { it.name }?.sorted() ?: emptyList()
    }

    /** 文件名安全化：路径分隔符、点开头、控制字符都拒（类名是代码标识符，正常不会带）。 */
    private fun safeFileName(className: String): String {
        require(className.length in 1..200) { "类名长度必须在 1..200：$className" }
        require(!className.contains('/') && !className.contains('\\') && !className.contains('\u0000')) {
            "类名不许带路径分隔符：$className"
        }
        require(!className.startsWith(".")) { "类名不许点开头：$className" }
        require(className.none { it.code < 0x20 }) { "类名不许带控制字符" }
        return className
    }

    /** JSON 字符串转义（写卡手拼，转义规则齐全：引号/反斜杠/控制符）。 */
    private fun jsonEscape(s: String): String = buildString {
        for (c in s) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c.code < 0x20 -> append("\\u").append(String.format("%04x", c.code))
                else -> append(c)
            }
        }
    }
}
