package com.hualuo.engine.api

import com.hualuo.engine.http.HttpTaxonomy
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * HTTP 换一次的**契约**与 JDK 实现。
 *
 * 为什么走 HttpURLConnection 而不引 OkHttp（D-03 精神：依赖最少）：
 * SSE 逐行读流、封顶计数这些 BoundedWireRead 已经用纯 JDK 写好了，
 * 引一整个 HTTP 库只为 openConnection 不划算；而且少一个依赖少一处供应链风险。
 *
 * 超时策略直说（旧仓的教训钉在这里）：`readTimeout` 是**两次字节之间**的最长等待，
 * 不是总时长 —— 旧仓为了长思考不被误杀把它归零，结果死连接永不报错（见 IdleWatchdog 头注）。
 * 归零是错、砍太短也是错；正确姿势是把它对齐看门狗档位（默认 5 分钟，一个字节都等不到才炸）。
 * 所以走 [effectiveReadTimeoutMs]：**传 0 或负数会被顶回默认档，不许归零**（有测试钉着）。
 */
data class WireRequest(
    val url: String,
    val method: String = "GET",
    val headers: List<Pair<String, String>> = emptyList(),
    /** 请求体全文（JSON 文本）；GET 之类的留 null。 */
    val body: String? = null,
    val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    val readTimeoutMs: Int = READ_TIMEOUT_FLOOR_MS,
) {
    /** 真正生效的单次读超时：非正数一律顶回默认档（禁归零是行为，不是注释愿望）。 */
    fun effectiveReadTimeoutMs(): Int =
        if (readTimeoutMs <= 0) READ_TIMEOUT_FLOOR_MS else readTimeoutMs

    companion object {
        /** 连接建立超时默认档。 */
        const val DEFAULT_CONNECT_TIMEOUT_MS = 15_000

        /** 单次读超时最低档：与 IdleWatchdog.GENERATION_IDLE_MS 对齐。 */
        const val READ_TIMEOUT_FLOOR_MS = 300_000
    }
}

/** 换一次的结果快照。都是事实，不含判断。 */
data class WireResponse(
    val status: Int,
    /** Retry-After 头换算的毫秒；没给就 null。 */
    val retryAfterMs: Long?,
    /**
     * 交付给 [LineSink] 的 2xx 正文字节数。**错误体不计** ——
     * 「收到过内容就不许盲重」这条红线数的是内容，不能被一坨错误 HTML 触发。
     */
    val bytesReceived: Long,
    /** 2xx 为 null（正文已逐行交付）；非 2xx 是截断后的错误体全文。 */
    val errorBody: String?,
)

/** 流中途断开的统一异常：必须带上「断开前已经读到几字节」，红线判定才有据可查。 */
class WireStreamIOException(
    val bytesSoFar: Long,
    cause: Throwable,
) : IOException("流中断：断开前已读 $bytesSoFar 字节", cause)

/** 逐行交付。返回 false 表示接收方要提前收线（比如读到 DONE 收尾标记）。 */
fun interface LineSink {
    fun onLine(line: String): Boolean
}

/** 一次换的抽象：单测用假实现排脚本，生产走 [UrlConnTransport]。 */
interface WireTransport {
    /**
     * 发一次请求并逐行喂 [sink]。本地失败抛 IOException（流断开包成 [WireStreamIOException]）；
     * 状态码非 2xx **不算异常**，装进 [WireResponse] 由上层决策。
     */
    @Throws(IOException::class)
    fun exchange(request: WireRequest, sink: LineSink): WireResponse

    /** 打断进行中的一换（幂等，可跨线程）。 */
    fun cancel()

    /** 是否已被取消：接线层用它区分「流断了」与「用户停了」，别把停止报成失败。 */
    fun isCancelled(): Boolean
}

/**
 * 进程级代理口（proxy 页实装刀）：不配 = 直连。App 层在设置变化时调 [installProxy]，
 * 所有 UrlConnTransport 实例（AI 请求/GitHub API）从此处取代理——只影响这两类出网，
 * 游戏观测桥是设备侧 localhost:18765，不经这里（家规：代理不碰观测）。
 *
 * 【2026-10-12 修】出网前一律经 [resolve] 把内部状态翻译成**绝不为 null** 的 Proxy：
 * 没配代理、或主机命中绕行清单，都返回 [java.net.Proxy.NO_PROXY]（直连）。
 * 这是旧 Agora `HttpClient.resolveProxy()` 的契约——URL.openConnection(Proxy)
 * 明文禁止 null，旧仓从 null 配置到 NO_PROXY 的翻译在重写时丢了，
 * 导致未配代理的设备上所有 AI 请求在建连接前抛
 * IllegalArgumentException("proxy can not be null")（机主实报：模型清单同步线程异常）。
 */
object TransportProxy {
    /** 已安装的代理；null = 直连。**内部状态，出网不许直接读它，走 [resolve]**。 */
    @Volatile var proxy: java.net.Proxy? = null

    /** 代理绕行清单（对齐旧 Agora：精确主机名、`*.后缀`、IPv4 CIDR，逗号无关、逐条列）。 */
    @Volatile var bypass: List<String> = emptyList()

    /**
     * 格式：type("http"/"socks") + host + port；type 不认或 host 空返回 false 不动旧值。
     * [bypass] 缺省为空清单（全量走代理），与旧签名保持源码兼容。
     *
     * 认证对齐旧 Agora setProxy：[username] 非空时装默认 [java.net.Authenticator]
     * （只答 RequestorType.PROXY 的问话）；JDK 栈下 HttpURLConnection 的 407 与
     * SOCKS 握手都走这一路，一套盖两形态。每次安装无条件重置认证——没带
     * username 就撤掉旧认证，不留上一份代理的凭据。
     */
    fun installProxy(
        type: String,
        host: String,
        port: Int,
        bypass: List<String> = emptyList(),
        username: String = "",
        password: String = "",
    ): Boolean {
        val h = host.trim()
        if (h.isEmpty() || port !in 1..65535) return false
        proxy = when (type.lowercase()) {
            "http" -> java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress(h, port))
            "socks" -> java.net.Proxy(java.net.Proxy.Type.SOCKS, java.net.InetSocketAddress(h, port))
            else -> return false
        }
        this.bypass = bypass
        if (username.isNotBlank()) {
            java.net.Authenticator.setDefault(object : java.net.Authenticator() {
                override fun getPasswordAuthentication(): java.net.PasswordAuthentication? =
                    if (requestorType == java.net.Authenticator.RequestorType.PROXY)
                        java.net.PasswordAuthentication(username, password.toCharArray())
                    else null
            })
        } else {
            java.net.Authenticator.setDefault(null)
        }
        return true
    }

    /** 清掉：回直连（连绕行清单与认证一起清，不留半份配置）。 */
    fun clear() {
        proxy = null
        bypass = emptyList()
        java.net.Authenticator.setDefault(null)
    }

    /**
     * 给目标主机解出要用的代理：**返回值绝不为 null**（旧 Agora 契约）。
     * 没配代理或命中绕行清单时，返回 [java.net.Proxy.NO_PROXY]。
     */
    fun resolve(host: String): java.net.Proxy {
        val installed = proxy ?: return java.net.Proxy.NO_PROXY
        if (isBypassed(host)) return java.net.Proxy.NO_PROXY
        return installed
    }

    /** 旧 Agora isProxyBypassed 原样移植：空主机名不走路由（没东西可判），三种条目形态。 */
    private fun isBypassed(host: String): Boolean {
        if (host.isBlank()) return true
        val normalized = host.lowercase().trim('[', ']')
        for (raw in bypass) {
            val entry = raw.trim().lowercase()
            when {
                entry.isEmpty() -> continue
                entry.contains('/') -> if (ipv4InCidr(normalized, entry)) return true
                entry.startsWith("*.") -> if (normalized == entry.drop(2) || normalized.endsWith(entry.drop(1))) return true
                normalized == entry -> return true
            }
        }
        return false
    }

    private fun ipv4ToLong(ip: String): Long? {
        val octets = ip.split('.')
        if (octets.size != 4) return null
        var value = 0L
        for (part in octets) {
            val octet = part.toIntOrNull() ?: return null
            if (octet !in 0..255) return null
            value = (value shl 8) or octet.toLong()
        }
        return value
    }

    private fun ipv4InCidr(host: String, cidr: String): Boolean {
        val parts = cidr.split('/')
        if (parts.size != 2) return false
        val bits = parts[1].toIntOrNull()?.takeIf { it in 0..32 } ?: return false
        val ip = ipv4ToLong(host) ?: return false
        val network = ipv4ToLong(parts[0]) ?: return false
        val mask = if (bits == 0) 0L else (-1L shl (32 - bits)) and 0xffffffffL
        return (ip and mask) == (network and mask)
    }
}

/** JDK HttpURLConnection 实现。 */
class UrlConnTransport : WireTransport {

    @Volatile private var active: HttpURLConnection? = null
    @Volatile private var cancelled = false

    override fun exchange(request: WireRequest, sink: LineSink): WireResponse {
        cancelled = false
        if (request.url.isBlank()) throw IOException("URL 是空的，没发出去")
        // resolve 按旧 Agora 契约绝不返回 null：没配代理 = NO_PROXY 直连。
        // 直塞 TransportProxy.proxy 会在未配代理时被 URL.openConnection(Proxy) 当场拒收。
        val target = URL(request.url)
        val conn = (target.openConnection(TransportProxy.resolve(target.host.orEmpty())) as HttpURLConnection).apply {
            instanceFollowRedirects = true
            useCaches = false
            connectTimeout = request.connectTimeoutMs.coerceIn(1, 120_000)
            readTimeout = request.effectiveReadTimeoutMs()
            requestMethod = request.method
        }
        active = conn
        try {
            request.headers.forEach { (name, value) -> conn.addRequestProperty(name, value) }
            request.body?.let { text ->
                val bytes = text.toByteArray(Charsets.UTF_8)
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
            }
            val status = conn.responseCode
            val retryAfterMs = parseRetryAfterMs(conn.getHeaderField("Retry-After"))
            if (status in 200..299) {
                val raw = conn.inputStream ?: return WireResponse(status, retryAfterMs, 0L, null)
                val counter = CountingStream(raw)
                val reader = BoundedLineReader(counter)
                try {
                    while (true) {
                        val line = reader.nextLine() ?: break
                        if (!sink.onLine(line)) break
                    }
                } catch (e: WireLimitException) {
                    throw e
                } catch (e: IOException) {
                    throw WireStreamIOException(counter.count, e)
                }
                return WireResponse(status, retryAfterMs, counter.count, null)
            }
            // 非 2xx：错误体整份封顶读；读不动就只给状态码，事实少说但绝不谎报。
            val stream = runCatching { conn.errorStream ?: conn.inputStream }.getOrNull()
            val errorText = stream?.let {
                runCatching { readBoundedText(it, ERROR_BODY_LIMIT_BYTES) }
                    .getOrNull()
                    ?.let { full -> HttpTaxonomy.truncateProviderMessage(full) }
            }
            return WireResponse(status, retryAfterMs, 0L, errorText)
        } finally {
            runCatching { conn.disconnect() }
            active = null
        }
    }

    override fun cancel() {
        cancelled = true
        active?.let { runCatching { it.disconnect() } }
    }

    override fun isCancelled(): Boolean = cancelled

    /** 只加计数字节的透传流。 */
    private class CountingStream(private val src: InputStream) : InputStream() {
        @Volatile var count: Long = 0L
            private set

        override fun read(): Int = src.read().also { if (it >= 0) count += 1 }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            src.read(b, off, len).also { if (it > 0) count += it }

        override fun available(): Int = src.available()

        override fun close() = src.close()
    }

    companion object {
        private const val ERROR_BODY_LIMIT_BYTES = 1_048_576L

        /** Retry-After 认两种写法：秒数、HTTP-date（换算成还要等多久，负数钳到 0）。 */
        internal fun parseRetryAfterMs(header: String?): Long? {
            if (header.isNullOrBlank()) return null
            header.trim().toLongOrNull()?.let { return it * 1_000L }
            runCatching {
                val date = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).parse(header.trim())
                    ?: return null
                return (date.time - System.currentTimeMillis()).coerceAtLeast(0L)
            }
            return null
        }
    }
}
