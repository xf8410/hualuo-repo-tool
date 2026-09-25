package com.hualuo.repotool.ui.tools

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.hualuo.engine.toolcalls.VideoTool
import com.hualuo.engine.vision.VideoPlan
import com.hualuo.repotool.ui.state.AppUiState
import java.io.File

/**
 * 导入流水：SAF 录屏进库，读时长、抽帧、写 manifest。调用方把本件放后台线程。
 * 原始录屏只作为抽帧输入；导入成功后界面层会删掉它，工具长期只读帧和账本。
 */
object VideoImporter {

    fun copyIn(context: Context, uri: Uri, inbox: File, displayName: String?): File? {
        val safeName = cleanName(displayName)
        var dest = File(inbox, safeName)
        if (dest.exists()) {
            val dot = safeName.lastIndexOf('.')
            val base = if (dot > 0) safeName.substring(0, dot) else safeName
            val ext = if (dot > 0) safeName.substring(dot) else ".mp4"
            dest = File(inbox, "$base-${System.currentTimeMillis()}$ext")
        }
        return try {
            val input = context.contentResolver.openInputStream(uri) ?: return null
            input.use { source ->
                dest.outputStream().use { target -> com.hualuo.engine.io.streamingCopy(source, target) }
            }
            if (dest.isFile && dest.length() > 0L) dest else {
                dest.delete()
                null
            }
        } catch (_: Exception) {
            dest.delete()
            null
        }
    }

    fun import(
        state: AppUiState,
        videoFile: File,
        framesRoot: File,
        inbox: File,
    ): File? {
        if (!videoFile.isFile || videoFile.length() == 0L) return null
        val retriever = android.media.MediaMetadataRetriever()
        var frameDir: File? = null
        var success = false
        try {
            retriever.setDataSource(videoFile.absolutePath)
            val durationMs = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            if (durationMs <= 0L) return null
            val times = VideoPlan.frameTimes(durationMs)
            val base = videoFile.nameWithoutExtension
            val dir = File(framesRoot, base).apply { mkdirs() }
            frameDir = dir
            val rels = mutableListOf<String>()
            times.forEachIndexed { i, tMs ->
                val frame = retriever.getFrameAtTime(
                    tMs * 1000,
                    android.media.MediaMetadataRetriever.OPTION_CLOSEST,
                )
                if (frame != null) {
                    var scaled: Bitmap? = null
                    try {
                        scaled = scaleDown(frame)
                        val rel = "$base/f$i.jpg"
                        val out = File(dir, "f$i.jpg")
                        val sink = ByteSink()
                        scaled.compress(Bitmap.CompressFormat.JPEG, 85, sink)
                        out.writeBytes(sink.toBytes())
                        rels += rel
                    } finally {
                        if (scaled != null && scaled !== frame && !scaled.isRecycled) scaled.recycle()
                        if (!frame.isRecycled) frame.recycle()
                    }
                }
                state.video.setVideoImporting(true, "抽帧 ${i + 1}/${times.size}")
            }
            if (rels.isEmpty()) return null
            val manifest = File(inbox, "${videoFile.name}.manifest.json")
            val timesJson = times.joinToString(",", "[", "]")
            val framesJson = rels.joinToString(",", "[", "]") { jsonQuote(it) }
            manifest.writeText(
                """{"name":${jsonQuote(videoFile.name)},"durationMs":$durationMs,"frameTimes":$timesJson,"frames":$framesJson}""",
            )
            success = true
            return manifest
        } catch (_: Exception) {
            return null
        } finally {
            runCatching { retriever.release() }
            if (!success) runCatching { frameDir?.deleteRecursively() }
        }
    }

    private fun scaleDown(src: Bitmap, maxWidthPx: Int = 1024): Bitmap {
        if (src.width <= maxWidthPx) return src
        val h = src.height.toLong() * maxWidthPx / src.width
        return Bitmap.createScaledBitmap(src, maxWidthPx, h.toInt().coerceAtLeast(1), true)
    }

    private fun cleanName(raw: String?): String {
        val source = raw?.trim().orEmpty().ifBlank { "clip" }
        val cleaned = buildString {
            source.forEach { ch ->
                if (ch.code < 0x20 || ch.code == 0x7f || ch in "/\\:*?\"<>|") append('_') else append(ch)
            }
        }.trim().ifBlank { "clip" }.take(180)
        return if (cleaned == "." || cleaned == "..") "clip" else cleaned
    }

    private fun jsonQuote(value: String): String = buildString {
        append('"')
        value.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch.code < 0x20) append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
        append('"')
    }

    private class ByteSink(initial: Int = 512 * 1024) : java.io.OutputStream() {
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
}

fun queryDisplayName(context: Context, uri: Uri): String? =
    context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
