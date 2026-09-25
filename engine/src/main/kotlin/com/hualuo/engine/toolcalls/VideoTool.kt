package com.hualuo.engine.toolcalls

import com.hualuo.engine.api.ProviderSession
import com.hualuo.engine.api.UrlConnTransport
import com.hualuo.engine.api.WireTransport
import com.hualuo.engine.io.DEFAULT_BUFFER_BYTES
import com.hualuo.engine.vision.VideoPlan
import com.hualuo.engine.vision.VisionExec
import java.io.File
import java.io.FileInputStream
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 看视频工具族：list_videos 列账，watch_video 读取导入时缓存的帧并返回文字。
 * 视频本体不进入工具执行；这里只接触 manifest 与 frames 目录。
 */
object VideoTool {

    private const val MAX_FRAME_BYTES = 8L * 1024L * 1024L

    fun register(
        registry: ToolRegistry,
        manifestsDir: File,
        framesDir: File,
        visionSession: () -> ProviderSession?,
        transport: WireTransport = UrlConnTransport(),
    ) {
        registry.registerGated(
            ToolSpec(
                name = "list_videos",
                description = "列出已导入的视频库（名字、时长、帧数）。用户让你看视频时，先在这里找有没有。",
                parametersJson = """{"type":"object","properties":{},"required":[]}""",
            ),
            ToolHandler { listVideos(manifestsDir) },
        ) { visionSession() != null }
        registry.registerGated(
            ToolSpec(
                name = "watch_video",
                description = "看一个已导入的视频：逐帧读画面与文字，汇总成【画面流水】+【攻略要点】。name 用 list_videos 里的名字；focus 写你的关注点。",
                parametersJson = """{"type":"object","properties":{"name":{"type":"string","description":"视频名（list_videos 里的 name）"},"focus":{"type":"string","description":"关注点，可空"}},"required":["name"]}""",
            ),
            ToolHandler { argumentsJson -> watch(manifestsDir, framesDir, visionSession(), transport, argumentsJson) },
        ) { visionSession() != null }
    }

    private fun listVideos(manifestsDir: File): String {
        val manifests = readManifests(manifestsDir)
        if (manifests.isEmpty()) return "视频库是空的。让用户先把录屏导入（工具页-视频库-导入）。"
        val rows = manifests.joinToString("\n") { m -> "- ${m.name}（${m.durationMs / 1000}s，${m.frames.size} 帧）" }
        return "视频库共 ${manifests.size} 条：\n$rows"
    }

    fun readManifests(manifestsDir: File): List<Manifest> =
        manifestsDir.listFiles { f -> f.extension == "json" && f.name.endsWith(".manifest.json") }
            ?.sortedBy { it.name }
            ?.mapNotNull { readManifest(it) }
            .orEmpty()

    private fun watch(
        manifestsDir: File,
        framesDir: File,
        session: ProviderSession?,
        transport: WireTransport,
        argumentsJson: String,
    ): String {
        if (session == null) return "看视频的眼睛模型没配上：去设置-看视频的眼睛里填一个带视觉的模型 id。"
        val args = argsOf(argumentsJson)
        val requestedName = (args["name"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        val name = safeName(requestedName)
            ?: return "视频名不合法：只接受 list_videos 返回的单个名字，不接受路径、斜杠或空名字。"
        if (name.isEmpty()) return "缺 name（要看哪条视频都没说）。先 list_videos。"
        val focus = (args["focus"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty().take(2_000)

        val manifestFile = File(manifestsDir, "$name.manifest.json")
        val manifest = if (manifestFile.isFile) readManifest(manifestFile) else null
        if (manifest == null) {
            val have = manifestsDir.listFiles { f -> f.extension == "json" }
                ?.map { it.name.removeSuffix(".manifest.json") }
                .orEmpty()
            return "没有叫「$name」的视频。${if (have.isEmpty()) "视频库也是空的。" else "现在有：${have.joinToString("、")}"}"
        }

        val notes = mutableListOf<String>()
        val batches = VideoPlan.batches(manifest.frameTimes)
        batches.forEachIndexed { bi, batchTimes ->
            val images = batchTimes.mapIndexedNotNull { i, time ->
                val frameIndex = manifest.frameTimes.indexOf(time)
                val relative = manifest.frames.getOrNull(frameIndex) ?: return@mapIndexedNotNull null
                val file = safeFrameFile(framesDir, relative) ?: return@mapIndexedNotNull null
                frameBase64(file)
            }
            if (images.isEmpty()) return@forEachIndexed
            val outcome = VisionExec.ask(
                session,
                transport,
                images,
                VideoPlan.describePrompt(bi, batches.size, batchTimes),
            )
            when (outcome) {
                is VisionExec.Outcome.Ok -> notes += outcome.text
                is VisionExec.Outcome.Failed -> return partial(manifest, name, notes, "第 ${bi + 1} 批没读出来：${outcome.reason}")
            }
        }
        if (notes.isEmpty()) return "帧缓存是空的（导入时抽帧失败，或账本路径不安全）。让用户重新导入这条视频。"

        val summary = VisionExec.askText(
            session,
            transport,
            VideoPlan.summarizePrompt(notes.joinToString("\n\n"), focus.ifEmpty { null }),
        )
        return when (summary) {
            is VisionExec.Outcome.Ok ->
                "视频 ${manifest.name}（${manifest.durationMs / 1000}s，${manifest.frames.size} 帧）看完了：\n\n${summary.text}"
            is VisionExec.Outcome.Failed -> partial(manifest, name, notes, "汇总没成：${summary.reason}")
        }
    }

    private fun partial(m: Manifest, name: String, notes: List<String>, problem: String): String =
        "视频 $name 读了 ${notes.size} 批画面，$problem\n以下是各批原始描述，照样能理解：\n\n" +
            notes.joinToString("\n\n")

    private fun safeName(raw: String): String? {
        if (raw.isBlank() || raw == "." || raw == "..") return null
        if (raw.any { it == '/' || it == '\\' || it.code == 0 }) return null
        return raw
    }

    private fun safeFrameFile(root: File, relative: String): File? {
        if (relative.isBlank() || relative.indexOf('\u0000') >= 0) return null
        if (File(relative).isAbsolute) return null
        val rootPath = runCatching { root.canonicalFile.toPath() }.getOrNull() ?: return null
        val file = runCatching { File(root, relative).canonicalFile }.getOrNull() ?: return null
        return file.takeIf { it.toPath().startsWith(rootPath) && it.isFile }
    }

    private fun frameBase64(f: File): String? {
        if (!f.isFile || f.length() <= 0L || f.length() > MAX_FRAME_BYTES) return null
        val sink = GrowingSink()
        try {
            FileInputStream(f).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_BYTES)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    sink.write(buffer, 0, n)
                }
            }
        } catch (_: Exception) {
            return null
        }
        return Base64.getEncoder().encodeToString(sink.toBytes())
    }

    private class GrowingSink(initial: Int = 256 * 1024) : java.io.OutputStream() {
        private var data = ByteArray(initial)
        private var len = 0
        override fun write(b: Int) {
            ensure(1)
            data[len] = b.toByte()
            len++
        }
        override fun write(b: ByteArray, off: Int, n: Int) {
            ensure(n)
            System.arraycopy(b, off, data, len, n)
            len += n
        }
        fun toBytes(): ByteArray = data.copyOf(len)
        private fun ensure(n: Int) {
            if (len + n > data.size) data = data.copyOf(maxOf(data.size * 2, len + n))
        }
    }

    data class Manifest(val name: String, val durationMs: Long, val frameTimes: List<Long>, val frames: List<String>)

    fun readManifest(f: File): Manifest? = try {
        val obj = Json.parseToJsonElement(f.readText()).let { it as? JsonObject } ?: return null
        val name = (obj["name"] as? JsonPrimitive)?.contentOrNull ?: return null
        val duration = (obj["durationMs"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0
        val times = (obj["frameTimes"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.toLongOrNull() }
            .orEmpty()
        val frames = (obj["frames"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .orEmpty()
        val validName = safeName(name)
        val validFrames = frames.none { it.isBlank() || File(it).isAbsolute || it.split('/', '\\').any { part -> part == ".." } }
        if (validName == null || frames.isEmpty() || times.size != frames.size || times.distinct().size != times.size || !validFrames) null
        else Manifest(validName, duration, times, frames)
    } catch (_: Exception) {
        null
    }

    private fun argsOf(argumentsJson: String): JsonObject =
        runCatching { Json.parseToJsonElement(argumentsJson) }.getOrNull() as? JsonObject
            ?: JsonObject(emptyMap())
}
