package com.hualuo.repotool

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 崩溃观察器（2026-09-30 闪退取证刀，二代）。
 *
 * 一代教训：装上后真机闪退但 Download 里没有文件——两种解释都存在：装错了包
 * （老包里没有取证器），或崩溃发生在挂钩之前。二代把「证据一定拿得到」做死：
 *
 *  1) **尽早挂**：Application.attachBaseContext 就装（比 onCreate 还早），
 *     并把 Application 构造期那点活（modelSettings 预热）挪出构造器——
 *     从此构造期不再有任何业务代码，挂钩之后才可能崩；
 *  2) **多处落盘**：Download/hualuo-crash-<时间戳>.txt（MediaStore，零权限，
 *     一次一份不覆盖）+ 内外私有目录 crash/last.txt；
 *  3) **直投私仓**：落盘之外再开后台线程把现场原文 PUT 到
 *     xf8410/hualuo-logs 的 crashprobe/ 目录（钥匙现读设置文件），限时等收尾，
 *     成不成都不算数——就算 Download 落盘被机型限制挡了，私仓里也有一份；
 *  4) **下次启动出声**：toast「上次闪退已留档在哪」。
 *
 * 红线自查：只写小文本不读大文件（红线二）；整文件读入内存的那类写法本文件
 * 一行都不出现（字节走 String.getBytes，写字走 OutputStreamWriter/File.writeText）；
 * 不动 manifest（不加权限、不加 provider、不加 service——service 永久红线）；
 * 原异常处理器原样回调，系统收尸流程不变。定位修完，本文件整删即可。
 */
object CrashObserver {

    private const val FILE_PREFIX = "hualuo-crash-"

    /** 投递目标（取证专用，私有仓）：crashprobe/<文件名>.txt */
    private const val EVIDENCE_REPO = "repos/xf8410/hualuo-logs/contents/crashprobe/"

    /** 崩溃写盘/投递本身再抛不能连环炸：同一进程一次只做一轮。 */
    private val busy = AtomicBoolean(false)

    @Volatile private var installed = false

    /**
     * 挂观察器。幂等：attachBaseContext 与 onCreate 都调也只生效一次。
     * 必须先记下原来的处理器，我们写完盘后原样交回——系统该弹的弹、该记的记。
     */
    @Synchronized
    fun install(appContext: Context) {
        if (installed) return
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            if (busy.compareAndSet(false, true)) {
                try {
                    val uploader = Thread(
                        { writeEverywhere(appContext, thread, throwable) },
                        "hualuo-crash-capture",
                    )
                    uploader.start()
                    // 限时等取证收尾（私仓直投最多 4 秒），然后立刻交还系统收尸
                    uploader.join(6000)
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

    /** 落盘三处 + 私仓直投；谁成功算谁，全失败也只算取证失败，不抛。 */
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

        // 私仓直投（后台线程已被调用方限时 join，这里同步跑）
        uploadToGitHub(context, downloadName, text)
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

    /**
     * 现场直投私仓（取证专用后备通道）：钥匙从设置文件现读（github.token），
     * 没钥匙或网络不通就静默放弃——本地三处留档还在。崩溃路径上抛不得。
     */
    private fun uploadToGitHub(context: Context, name: String, text: String) {
        try {
            val settings = File(context.filesDir, "settings/ui.properties")
            if (!settings.isFile) return
            val props = Properties()
            FileInputStream(settings).use { input -> props.load(input) }
            val token = props.getProperty("github.token")?.trim().orEmpty()
            if (token.isEmpty()) return
            val encoded = java.util.Base64.getEncoder().encodeToString(text.getBytes(Charsets.UTF_8))
            val body = "{\"message\":\"crash evidence $name\",\"content\":\"$encoded\"}"
            val conn = java.net.URL("https://api.github.com/" + EVIDENCE_REPO + name)
                .openConnection() as java.net.HttpURLConnection
            try {
                conn.requestMethod = "PUT"
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                conn.doOutput = true
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.getBytes(Charsets.UTF_8)) }
                conn.responseCode
            } finally {
                conn.disconnect()
            }
        } catch (_: Throwable) {
            // 直投失败不追究：本地留档（下载目录 + 内外私有目录）还在
        }
    }
}
