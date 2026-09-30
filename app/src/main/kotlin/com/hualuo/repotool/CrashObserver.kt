package com.hualuo.repotool

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 崩溃观察器（2026-09-30 闪退取证刀）。
 *
 * 为什么要有它：0.6.0 的 CI 包闪退，手里一个堆栈都没有——App 之前没有挂
 * UncaughtExceptionHandler，进程死了就是死了，报障只剩「点开就退」四个字，
 * 没法定位。本刀不改任何业务行为：只在默认异常出口前面挂一层，崩溃瞬间把
 * 完整现场落盘，能落几处落几处，谁成功算谁的：
 *  1) 公共下载目录（API 29+ 走 MediaStore.Downloads，**不需要任何存储权限**，
 *     文件管理器直接看得到：Download/hualuo-crash-<时间戳>.txt，一次崩溃一份不覆盖）；
 *  2) App 外私有目录 Android/data/com.hualuo.repotool/files/crash/last.txt；
 *  3) App 内私有目录 files/crash/last.txt（最保险，前两处都废了也有一份）。
 * 内外私有目录的 last.txt 只留**最近一次**（再崩会覆盖）；公共下载目录那份按
 * 时间戳留全部。下次启动把「上次崩了、现场在哪」toast 出声——取证必须出声，
 * 不许静默落盘没人知道。现场拿到手、修完之后，这一刀整个文件删掉即可，
 * 除此之外没有一行业务改动。
 *
 * 红线自查：本文件只写小文本不读大文件（红线二）；不碰 readBytes/toByteArray
 * 字面禁令（写字走 OutputStreamWriter 与 File.writeText）；不动 manifest
 * （不加权限、不加 service——service 是本仓永久红线）；原异常处理器原样回调，
 * 系统自己的崩溃收尸（记录/弹窗）不受影响。
 */
object CrashObserver {

    private const val FILE_PREFIX = "hualuo-crash-"

    /** 崩溃写盘本身再抛不能连环炸：同一进程一次只写一份。 */
    private val busy = AtomicBoolean(false)

    /**
     * 挂观察器（Application.onCreate 调一次）。必须先记下原来的处理器，
     * 我们写完盘后原样交回——系统该弹的弹、该记的记，流程不变。
     */
    fun install(appContext: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            if (busy.compareAndSet(false, true)) {
                try {
                    writeEverywhere(appContext, thread, throwable)
                } catch (_: Throwable) {
                    // 取证失败也不能拦住系统收尸：吞掉，往下交还原处理器
                }
            }
            try {
                previous?.uncaughtException(thread, throwable)
            } catch (_: Throwable) {
                // 原处理器也炸了就到此为止：进程反正要死，不许再抛第二遍
            }
        }
    }

    /**
     * 上次崩溃的留档提示（下次启动 toast 用）；取走即清，一次进程只响一次。
     * null = 没有未报过的新现场。
     */
    fun consumeStartupCrashNotice(context: Context): String? {
        val pending = File(File(context.filesDir, "crash"), "pending.txt")
        if (!pending.isFile) return null
        val where = runCatching { pending.readText().trim() }.getOrNull()
        // 读走就改名留档（pending → last），toast 不会每次启动复读
        runCatching {
            if (!pending.renameTo(File(File(context.filesDir, "crash"), "last-notice.txt"))) {
                pending.delete()
            }
        }
        return if (where.isNullOrEmpty()) null else "上次闪退已留档：$where（拿这份文件就能定位，别重装，装了现场就没了）"
    }

    /** 落盘三处：公共下载目录（29+）+ 外私有 + 内私有；全失败也只算取证失败，不抛。 */
    private fun writeEverywhere(context: Context, thread: Thread, throwable: Throwable) {
        val text = render(thread, throwable)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val downloadName = FILE_PREFIX + stamp + ".txt"

        val downloadsOk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            writeToDownloads(context, downloadName, text)

        writeToFile(File(File(context.filesDir, "crash"), "last.txt"), text)
        val outerDir = context.getExternalFilesDir(null)
        if (outerDir != null) {
            writeToFile(File(File(outerDir, "crash"), "last.txt"), text)
        }

        // 给下一任进程的提示：下载目录成了就指名那份文件，没成就指私有目录留档
        val where = if (downloadsOk) "下载目录 $downloadName" else "App 私有目录 crash/last.txt"
        writeToFile(File(File(context.filesDir, "crash"), "pending.txt"), where)
    }

    /** 现场文本：版本/设备/线程 + 完整堆栈（含因果链），纯文本方便直接贴回来。 */
    private fun render(thread: Thread, throwable: Throwable): String {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        throwable.printStackTrace(pw)
        pw.flush()
        return buildString {
            appendLine("Hualuo 崩溃现场（崩溃观察器自动留档，原文发回即可排查）")
            appendLine(
                "时间：" + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()),
            )
            appendLine("版本：" + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")")
            appendLine(
                "设备：" + Build.MANUFACTURER + " " + Build.MODEL +
                    "  Android " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")",
            )
            appendLine("线程：" + thread.name)
            appendLine()
            appendLine(sw.toString())
        }
    }

    /**
     * API 29+：走 MediaStore.Downloads 写公共下载目录，零权限零授权，
     * 文件管理器/电脑都能直接拿到。任何一步不顺就返回 false，不抛不重试。
     */
    private fun writeToDownloads(context: Context, name: String, text: String): Boolean = try {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            false
        } else {
            val out: OutputStream? = context.contentResolver.openOutputStream(uri)
            if (out == null) {
                false
            } else {
                out.use { stream ->
                    OutputStreamWriter(stream, Charsets.UTF_8).use { writer ->
                        writer.write(text)
                    }
                }
                true
            }
        }
    } catch (_: Throwable) {
        false
    }

    /** 私有目录写一份；失败返回 false，不抛（崩溃路径上抛不得）。 */
    private fun writeToFile(target: File, text: String): Boolean = try {
        target.parentFile?.mkdirs()
        target.writeText(text)
        true
    } catch (_: Throwable) {
        false
    }
}
