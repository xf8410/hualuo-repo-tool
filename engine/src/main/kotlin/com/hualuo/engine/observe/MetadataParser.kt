package com.hualuo.engine.observe

import java.io.File
import java.io.RandomAccessFile

/**
 * metadata.bin 离线解析器（IL2CPP 立项第四块件，冷启动流水的「全景卡源」）。
 *
 * 输入是 MetadataSpool 从游戏内存分片搬下来的 global-metadata（解密后原文）。
 * 输出：类清单（namespace+name、字段名列表、方法数）——dump.cs 的等价物。
 *
 * 认知边界（重要，别记错）：
 *  - metadata 里只有「字段名/类型索引/声明关系」，**字段偏移不在 metadata**——
 *    偏移在 libil2cpp.so 的 MetadataRegistration.fieldOffsets（SO 数据段）。
 *    所以本件的产出与观测桥 /fields/（运行时真实偏移）是**互补关系**：
 *    metadata 给名字全景（有哪些类、字段叫什么），观测桥给布局真相（偏移多少）。
 *    两者交叉 = 字段卡（名字+类型+偏移三对齐）。
 *
 * 版本门：只实装游戏实际用到的 v31（686 版 probe 实证）。
 * 其他版本如实拒并列支持清单——不瞎猜布局（宁拒不错，Il2CppDumper 的
 * 版本试探是它的场景，我们的场景版本已知）。
 *
 * 内存纪律（红线二）：RandomAccessFile 流式按需读——header 一次 256 字节、
 * 字符串按需 seek 读到 \0、typeDefinitions 逐条 88 字节迭代。
 * 151MB 文件全程内存占用 <= 几 KB。
 */
class MetadataParser(private val file: File) {

    /** header 段表：段名 -> (offset, size)。 */
    class Sections(val version: Int, val table: Map<String, LongArray>)

    /** 一个类的清单行。 */
    class TypeRow(
        val namespace: String,
        val name: String,
        val fieldCount: Int,
        val methodCount: Int,
    )

    /** 一个类的字段名清单（查单个类用）。 */
    class FieldNames(
        val namespace: String,
        val name: String,
        val fieldNames: List<String>,
    )

    // ---------- header ----------

    /** 解析 header：魔数校验 + 版本校验 + 段表（v31 布局：31 对 offset/size）。 */
    fun sections(): Sections {
        RandomAccessFile(file, "r").use { raf ->
            val head = ByteArray(HEADER_LEN_V31)
            raf.readFully(head)
            val sanity = readU32(head, 0)
            require(sanity == MAGIC) { "不是 global-metadata（魔数不符）：0x${sanity.toString(16)}" }
            val version = readU32(head, 4).toInt()
            require(version in SUPPORTED) {
                "metadata 版本 $version 不在实装清单 $SUPPORTED 里——如实拒：别的版本布局不同，" +
                    "需要先对 Il2CppDumper MetadataClass.cs 的 [Version] 注解补对应字段序列"
            }
            val table = linkedMapOf<String, LongArray>()
            var p = 8
            for (sec in SECTION_NAMES_V31) {
                val off = readU32(head, p).toLong()
                val size = readU32(head, p + 4).toLong()
                table[sec] = longArrayOf(off, size)
                p += 8
            }
            return Sections(version, table)
        }
    }

    // ---------- 查询 ----------

    /** 全量类清单（流式迭代 typeDefinitions，回调不落大表）。 */
    fun forEachType(action: (TypeRow) -> Unit) {
        val secs = sections()
        val (tdOff, tdSize) = secs.table.getValue("typeDefinitions")
        val count = (tdSize / TYPEDEF_SIZE_V31).toInt()
        require(count > 0) { "typeDefinitions 段空——metadata 不完整或段表错位" }
        RandomAccessFile(file, "r").use { raf ->
            val rec = ByteArray(TYPEDEF_SIZE_V31.toInt())
            for (i in 0 until count) {
                raf.seek(tdOff + i.toLong() * TYPEDEF_SIZE_V31)
                raf.readFully(rec)
                val nameIdx = readU32(rec, 0).toLong()
                val nsIdx = readU32(rec, 4).toLong()
                val methodCount = readU16(rec, 64)
                val fieldCount = readU16(rec, 68)
                if (nameIdx == 0xFFFFFFFFL) continue
                action(
                    TypeRow(
                        namespace = readStringAt(raf, secs, nsIdx),
                        name = readStringAt(raf, secs, nameIdx),
                        fieldCount = fieldCount,
                        methodCount = methodCount,
                    ),
                )
            }
        }
    }

    /** 查单个类（简名或带命名空间全名），返回字段名清单。找不到给 null。 */
    fun findClass(query: String): FieldNames? {
        val secs = sections()
        val (tdOff, tdSize) = secs.table.getValue("typeDefinitions")
        val count = (tdSize / TYPEDEF_SIZE_V31).toInt()
        val (fOff, _) = secs.table.getValue("fields")
        RandomAccessFile(file, "r").use { raf ->
            val rec = ByteArray(TYPEDEF_SIZE_V31.toInt())
            for (i in 0 until count) {
                raf.seek(tdOff + i.toLong() * TYPEDEF_SIZE_V31)
                raf.readFully(rec)
                val nameIdx = readU32(rec, 0).toLong()
                if (nameIdx == 0xFFFFFFFFL) continue
                val name = readStringAt(raf, secs, nameIdx)
                val nsIdx = readU32(rec, 4).toLong()
                val ns = readStringAt(raf, secs, nsIdx)
                val full = if (ns.isEmpty()) name else "$ns.$name"
                if (name != query && full != query) continue
                val fieldStart = readU32(rec, 32).toLong()
                val fieldCount = readU16(rec, 68)
                val names = mutableListOf<String>()
                val frec = ByteArray(FIELD_SIZE_V31.toInt())
                for (f in 0 until fieldCount) {
                    raf.seek(fOff + (fieldStart + f) * FIELD_SIZE_V31)
                    raf.readFully(frec)
                    val fNameIdx = readU32(frec, 0).toLong()
                    if (fNameIdx == 0xFFFFFFFFL) continue
                    names += readStringAt(raf, secs, fNameIdx)
                }
                return FieldNames(namespace = ns, name = name, fieldNames = names)
            }
        }
        return null
    }

    /** 类总数。 */
    fun typeCount(): Int {
        val (_, size) = sections().table.getValue("typeDefinitions")
        return (size / TYPEDEF_SIZE_V31).toInt()
    }

    // ---------- 内部 ----------

    /** 按字符串表索引读一个 \0 结尾字符串（限长 512 防越界坏表）。 */
    private fun readStringAt(raf: RandomAccessFile, secs: Sections, index: Long): String {
        if (index == 0xFFFFFFFFL || index < 0) return ""
        val (sOff, sSize) = secs.table.getValue("string")
        require(index < sSize) { "字符串索引越界：$index >= $sSize（段表错位或文件不完整）" }
        raf.seek(sOff + index)
        val sb = StringBuilder()
        var remaining = 512
        while (remaining-- > 0) {
            val b = raf.read()
            if (b <= 0) break
            sb.append(b.toChar())
        }
        return sb.toString()
    }

    private fun readU32(b: ByteArray, off: Int): Long =
        ((b[off].toLong() and 0xff)) or
            ((b[off + 1].toLong() and 0xff) shl 8) or
            ((b[off + 2].toLong() and 0xff) shl 16) or
            ((b[off + 3].toLong() and 0xff) shl 24)

    private fun readU16(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xff)) or ((b[off + 1].toInt() and 0xff) shl 8)

    companion object {
        /* v31 Il2CppTypeDefinition 布局速查（88 字节）：nameIndex@0 namespaceIndex@4
         * byvalTypeIndex@8 declaringTypeIndex@12 parentIndex@16 elementTypeIndex@20
         * genericContainerIndex@24 flags@28 fieldStart@32 methodStart@36 eventStart@40
         * propertyStart@44 nestedTypesStart@48 interfacesStart@52 vtableStart@56
         * interfaceOffsetsStart@60 method_count@64 property_count@66 field_count@68
         * event_count@70 bitfield@80 token@84 */
        const val MAGIC = 0xFAB11BAFL
        val SUPPORTED = listOf(31)
        const val HEADER_LEN_V31 = 8 + 31 * 8
        const val TYPEDEF_SIZE_V31 = 88L
        const val FIELD_SIZE_V31 = 12L

        /** v31 段名序（照 Il2CppDumper MetadataClass.cs 的 [Version] 门滤出，顺序即布局）。 */
        val SECTION_NAMES_V31 = listOf(
            "stringLiteral", "stringLiteralData", "string",
            "events", "properties", "methods",
            "parameterDefaultValues", "fieldDefaultValues", "fieldAndParameterDefaultValueData",
            "fieldMarshaledSizes", "parameters", "fields",
            "genericParameters", "genericParameterConstraints", "genericContainers",
            "nestedTypes", "interfaces", "vtableMethods",
            "interfaceOffsets", "typeDefinitions",
            "images", "assemblies",
            "fieldRefs", "referencedAssemblies",
            "attributeData", "attributeDataRange",
            "unresolvedVirtualCallParameterTypes", "unresolvedVirtualCallParameterRanges",
            "windowsRuntimeTypeNames", "windowsRuntimeStrings",
            "exportedTypeDefinitions",
        )
    }
}
