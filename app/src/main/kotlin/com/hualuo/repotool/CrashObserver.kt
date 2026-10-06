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
 * 干什么：默认异常出口前面挂一层，崩溃瞬间把完整现场写到**本机**三处——
 *  1) 公共下载目录 Download/hualuo-crash-<时间戳>.txt（API 29+ 走 MediaStore，
 *     零权限，一次崩溃一份不覆盖，文件管理器直接看得到）；
 *  2) 外私有目录 Android/data/com.hualuo.repotool/files/crash/last.txt（最近一次）；
 *  3) 内私有目录 files/crash/last.txt（最近一次，最保险）。
 * 下次启动 toast 出声「上次闪退已留档在哪」。取证必须出声，不许静默。
 *
 * 边界（2026-09-30 用户明令，钉死）：**本文件不联网、不碰任何钥匙**。
 * 曾有一版擅自用 App 里存的 github.token 把现场直投私仓，未获许可，已整体移除；
 * 以后任何"自动上传"都必须先把方案摆给用户、点头才准写进代码。
 * 现场取走由维护侧经用户授权的通道进行，与本文件无关。
 *
 * 红线自查：只写小文本不读大文件（红线二）；整文件读入内存的那类写法本文件
 * 一行都不出现（写字走 OutputStreamWriter 与 File.writeText）；不动 manifest
 * （不加权限、不加 provider、不加 service——service 永久红线）；原异常处理器
 * 原样回调，系统收尸流程不变。定位修完，本文件整删即可。
 */
object CrashObserver {

    private const val FILE_PREFIX = "hualuo-crash-"

    /** 崩溃写盘本身再抛不能连环炸：同一进程一次只写一轮。 */
    private val busy = AtomicBoolean(false)

    @Volatile private var installed = false

    /**
     * 崩溃本地留档开关（关于页真开关）：默认 true——崩溃取证是底线，关掉是用户明示的取舍。
     * 写盘回调里只读这个内存值，绝不碰文件（崩溃瞬间 IO 越少越好）。
     */
    @Volatile var keepLocal = true

    /**
     * 挂观察器。幂等：attachBaseContext 与 onCreate 都调也只生效一次。
     * 必须先记下原来的处理器，我们写完盘后原样交回——系统该弹的弹、该记的记。
     */
    @Synchronized
    fun install(appContext: Context) {
        if (installed) return
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            if (busy.compareAndSet(false, true) && keepLocal) {
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
        installed = true
    }

    /**
     * 上次崩溃的留档提示（下次启动 toast 用）；取走即清，一次进程只响一次。
     * null = 没有未报过的新现场。
     */
    fun consumeStartupCrashNotice(context: Context): String? {
        val pending = File(File(context.filesDir, "crash"), "pending.txt")
        if (!pending.isFile) return null
        val where = runCatching { pending.readText().trim() }.getOrNull()
        // 读走就改名留档（pending 改名成 last-notice.txt），toast 不会每次启动复读
        runCatching {
            if (!pending.renameTo(File(File(context.filesDir, "crash"), "last-notice.txt"))) {
                pending.delete()
            }
        }
        return if (where.isNullOrEmpty()) {
            null
        } else {
            "上次闪退已留档：$where（拿这份文件就能定位，别重装，装了现场就没了）"
        }
    }

    /** 落盘三处，谁成功算谁；全失败也只算取证失败，不抛。 */
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
