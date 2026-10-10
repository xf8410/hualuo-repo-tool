package com.hualuo.engine.observe

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * 剧本板读取器（冷启动流水 L5「采」的切分件，设计稿 docs/IL2CPP-COLDSTART-PIPELINE.md）。
 *
 * 剧本板 = 一个剧本的语义字段账（scenario -> entries -> fields），每条字段带
 * semantic（这是体力/干线/友谊…）、offset（当前版本的内存偏移）、type_name、
 * diff_state（对账四态）、verified（证据三态）。
 *
 * 本件干两件事：
 *  1. parse：板 JSON -> 结构（必需字段缺了如实报错，不静默猜）
 *  2. readEntry：把 read_mem 搬回来的整段对象字节，按板上的 offset+type 切值
 *
 * 纪律（设计稿拍板过的）：
 *  - 语义只搬不发明——disappeared 字段跳过不读（旧位置已经不是它了）
 *  - 越界如实报 out_of_range（板漂移的信号），不炸不编
 *  - 未知类型按 8 字节指针给 hex（引用类型 v1 不追指针——要追是下一刀）
 *  - verified 照读（unverified 也切出来），但值行里带上状态让调用方自判
 */
class BoardReader {

    /** 板上一条字段。 */
    class FieldSpec(
        val semantic: String,
        val offset: Int,
        val typeName: String,
        val diffState: String,
        val verified: String,
    )

    /** 一个类（singleton）的板条目。 */
    class Entry(
        val className: String,
        val singletonHint: String,
        val fields: List<FieldSpec>,
    )

    /** 整块板。 */
    class Board(
        val scenario: String,
        val gameVersion: String,
        val entries: List<Entry>,
    )

    companion object {

        /** 解板 JSON。结构坏/必需字段缺 -> 如实抛（人话错误）。 */
        fun parse(text: String, source: String = "剧本板"): Board {
            val obj = runCatching { Json.parseToJsonElement(text).let { it as? JsonObject } }
                .getOrElse { throw IllegalArgumentException("$source 不是合法 JSON：${it.message}") }
                ?: throw IllegalArgumentException("$source 不是 JSON 对象")
            val scenario = obj.str("scenario") ?: throw IllegalArgumentException("$source 缺 scenario（剧本标识，如 ramen）")
            val gv = obj.str("game_version") ?: throw IllegalArgumentException("$source 缺 game_version（板对应的游戏版本号）")
            val entriesArr = obj["entries"] as? kotlinx.serialization.json.JsonArray
                ?: throw IllegalArgumentException("$source 缺 entries 数组")
            val entries = entriesArr.mapIndexed { i, el ->
                val eo = el as? JsonObject
                    ?: throw IllegalArgumentException("$source 第 $i 条 entry 不是对象")
                val cn = eo.str("class") ?: throw IllegalArgumentException("$source 第 $i 条 entry 缺 class（完整类名）")
                val hint = eo.str("singleton_hint") ?: cn.substringAfterLast('.')
                val fieldsArr = eo["fields"] as? kotlinx.serialization.json.JsonArray
                    ?: throw IllegalArgumentException("$source 类 $cn 缺 fields 数组")
                val fields = fieldsArr.mapIndexed { j, fe ->
                    val fo = fe as? JsonObject
                        ?: throw IllegalArgumentException("$source 类 $cn 第 $j 条字段不是对象")
                    FieldSpec(
                        semantic = fo.str("semantic") ?: throw IllegalArgumentException("$source 类 $cn 第 $j 条字段缺 semantic"),
                        offset = fo.str("offset")?.toIntOrNull() ?: throw IllegalArgumentException("$source 类 $cn 字段 ${fo.str("semantic")} 的 offset 不是整数"),
                        typeName = fo.str("type_name") ?: "?",
                        diffState = fo.str("diff_state") ?: "unknown",
                        verified = fo.str("verified") ?: "unverified",
                    )
                }
                Entry(className = cn, singletonHint = hint, fields = fields)
            }
            return Board(scenario = scenario, gameVersion = gv, entries = entries)
        }

        /** 类型 -> 字节数。未认识的按 8（指针宽度，64 位 IL2CPP 对象字段引用）。 */
        fun typeSize(typeName: String): Int = when (normalize(typeName)) {
            "int32", "uint32", "single", "float" -> 4
            "int64", "uint64", "double" -> 8
            "int16", "uint16" -> 2
            "int8", "uint8", "boolean", "bool" -> 1
            else -> 8
        }

        /** 读这条 entry 需要的最小字节数（max(offset+size)，下限 8，上限 65536=read_mem 顶格）。 */
        fun requiredBytes(entry: Entry): Int {
            var need = 8
            for (f in entry.fields) {
                if (f.diffState == "disappeared") continue
                need = maxOf(need, f.offset + typeSize(f.typeName))
            }
            return need.coerceAtMost(65536)
        }

        /**
         * 按板切值：semantic -> 值字符串（人话）。
         * buffer 是 read_mem 从实例地址起读回的原字节；bufferOffset 供 hex 对齐用（v1 传 0）。
         */
        fun readEntry(entry: Entry, buffer: ByteArray, bufferOffset: Int = 0): LinkedHashMap<String, String> {
            val out = LinkedHashMap<String, String>()
            for (f in entry.fields) {
                if (f.diffState == "disappeared") continue // 语义只搬不发明：消失字段旧位置已不是它
                val off = f.offset - bufferOffset
                val size = typeSize(f.typeName)
                if (off < 0 || off + size > buffer.size) {
                    out[f.semantic] = "out_of_range"
                    continue
                }
                out[f.semantic] = decode(normalize(f.typeName), buffer, off)
            }
            return out
        }

        private fun normalize(t: String): String =
            t.trim().removePrefix("System.").lowercase()

        private fun decode(t: String, b: ByteArray, o: Int): String = when (t) {
            "int32" -> readI32(b, o).toString()
            "uint32" -> (readI32(b, o).toLong() and 0xFFFFFFFFL).toString()
            "int64" -> readI64(b, o).toString()
            "uint64" -> java.lang.Long.toUnsignedString(readI64(b, o))
            "int16" -> ((b[o].toInt() and 0xff) or (b[o + 1].toInt() shl 8)).toShort().toString()
            "uint16" -> ((b[o].toInt() and 0xff) or (b[o + 1].toInt() and 0xff shl 8)).toString()
            "int8" -> b[o].toString()
            "uint8" -> (b[o].toInt() and 0xff).toString()
            "boolean", "bool" -> (b[o].toInt() != 0).toString()
            "single", "float" -> String.format("%.4f", java.lang.Float.intBitsToFloat(readI32(b, o)))
            "double" -> String.format("%.4f", java.lang.Double.longBitsToDouble(readI64(b, o)))
            else -> "0x%016x".format(readI64(b, o)) // 引用类型：给指针，v1 不追
        }

        private fun readI32(b: ByteArray, o: Int): Int =
            (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or
                ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)

        private fun readI64(b: ByteArray, o: Int): Long {
            var v = 0L
            for (i in 7 downTo 0) v = (v shl 8) or (b[o + i].toLong() and 0xff)
            return v
        }

        private fun JsonObject.str(key: String): String? =
            (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.isNotEmpty() }
    }
}
