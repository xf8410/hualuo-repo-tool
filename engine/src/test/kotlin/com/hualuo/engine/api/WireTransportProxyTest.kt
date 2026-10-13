package com.hualuo.engine.api

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 钉死 2026-10-12 修的直连即死：未配代理时 UrlConnTransport 不许炸
 * IllegalArgumentException("proxy can not be null")——URL.openConnection(Proxy)
 * 禁止 null，直连必须翻译成 Proxy.NO_PROXY（旧 Agora resolveProxy 契约）。
 */
class WireTransportProxyTest {

    @After
    fun tearDown() = TransportProxy.clear()

    @Test
    fun `resolve returns NO_PROXY when nothing installed`() {
        TransportProxy.clear()
        assertSame(java.net.Proxy.NO_PROXY, TransportProxy.resolve("api.kimi.com"))
        assertSame(java.net.Proxy.NO_PROXY, TransportProxy.resolve(""))
    }

    @Test
    fun `resolve returns installed proxy for normal host`() {
        assertTrue(TransportProxy.installProxy("http", "127.0.0.1", 8888))
        val resolved = TransportProxy.resolve("api.kimi.com")
        assertEquals(java.net.Proxy.Type.HTTP, resolved.type())
    }

    @Test
    fun `resolve honors bypass list in all three entry forms`() {
        assertTrue(
            TransportProxy.installProxy(
                "http", "127.0.0.1", 8888,
                bypass = listOf("*.lan", "10.0.0.0/8", "api.kimi.com"),
            ),
        )
        assertSame(java.net.Proxy.NO_PROXY, TransportProxy.resolve("nas.lan"))
        assertSame(java.net.Proxy.NO_PROXY, TransportProxy.resolve("lan"))
        assertSame(java.net.Proxy.NO_PROXY, TransportProxy.resolve("10.1.2.3"))
        assertSame(java.net.Proxy.NO_PROXY, TransportProxy.resolve("api.kimi.com"))
        assertEquals(java.net.Proxy.Type.HTTP, TransportProxy.resolve("example.com").type())
    }

    @Test
    fun `bad proxy spec rejected without touching previous state`() {
        assertTrue(TransportProxy.installProxy("http", "127.0.0.1", 8888))
        val before = TransportProxy.resolve("example.com")
        assertEquals(false, TransportProxy.installProxy("http", "", 8888))
        assertEquals(false, TransportProxy.installProxy("http", "127.0.0.1", 0))
        assertEquals(false, TransportProxy.installProxy("gopher", "127.0.0.1", 8888))
        assertSame(before, TransportProxy.resolve("example.com"))
    }

    /** 主线回归：没配代理的设备上，直连请求必须真的发出去并拿到 2xx。 */
    @Test
    fun `direct exchange works when no proxy installed`() {
        TransportProxy.clear()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/models") { ex ->
            val body = """{"data":[{"id":"m1"}]}""".toByteArray(Charsets.UTF_8)
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val transport = UrlConnTransport()
            val lines = StringBuilder()
            val response = transport.exchange(
                WireRequest(url = "http://127.0.0.1:${server.address.port}/models"),
            ) { line -> lines.appendLine(line); true }
            assertEquals(200, response.status)
            assertTrue(lines.contains("m1"))
            assertTrue(response.bytesReceived > 0)
        } finally {
            server.stop(0)
        }
    }

    /** 装了代理但主机命中绕行：绕行主机照样直连出去。 */
    @Test
    fun `bypassed host goes direct even with proxy installed`() {
        assertTrue(TransportProxy.installProxy("http", "127.0.0.1", 1, bypass = listOf("127.0.0.1")))
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/ping") { ex ->
            val body = "ok".toByteArray(Charsets.UTF_8)
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val transport = UrlConnTransport()
            val response = transport.exchange(
                WireRequest(url = "http://127.0.0.1:${server.address.port}/ping"),
            ) { true }
            assertEquals(200, response.status)
        } finally {
            server.stop(0)
        }
    }

    /**
     * 认证生命周期端到端（对齐旧 Agora setProxy 的无条件重置语义）。
     * JDK 无 Authenticator.getDefault 读回，状态全用假代理行为验证：
     * 无 Proxy-Authorization 回 407+challenge，凭据对（u:p）回 200。
     */
    private fun fakeAuthProxy(): Pair<HttpServer, () -> Boolean> {
        var sawAuth = false
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val auth = ex.requestHeaders.getFirst("Proxy-Authorization")
            val expect = "Basic " + java.util.Base64.getEncoder().encodeToString("u:p".toByteArray())
            if (auth == expect) {
                sawAuth = true
                val body = "ok".toByteArray(Charsets.UTF_8)
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            } else {
                ex.responseHeaders.add("Proxy-Authenticate", "Basic realm=\"proxy\"")
                ex.sendResponseHeaders(407, -1)
            }
        }
        server.start()
        return server to { sawAuth }
    }

    /** 带认证装代理：407 challenge -> Authenticator 答话 -> 带 Basic 重试 -> 200。 */
    @Test
    fun `proxy auth answers 407 challenge end to end`() {
        val (server, sawAuth) = fakeAuthProxy()
        try {
            assertTrue(TransportProxy.installProxy("http", "127.0.0.1", server.address.port, username = "u", password = "p"))
            val response = UrlConnTransport().exchange(WireRequest(url = "http://example.com/models")) { true }
            assertEquals(200, response.status)
            assertTrue(sawAuth())
        } finally {
            server.stop(0)
        }
    }

    /** 装认证后重装不带认证：旧凭据撤掉（不留上一份代理的认证）-> 407 透传。 */
    @Test
    fun `reinstall without credentials drops previous auth`() {
        val (server, sawAuth) = fakeAuthProxy()
        try {
            assertTrue(TransportProxy.installProxy("http", "127.0.0.1", 9, username = "u", password = "p"))
            assertTrue(TransportProxy.installProxy("http", "127.0.0.1", server.address.port))
            val response = UrlConnTransport().exchange(WireRequest(url = "http://example.com/models")) { true }
            assertEquals(407, response.status)
            assertTrue(!sawAuth())
        } finally {
            server.stop(0)
        }
    }

    /** clear 连认证一起清：直连假代理 -> 407 透传（无凭据重试）。 */
    @Test
    fun `clear also drops authenticator`() {
        val (server, sawAuth) = fakeAuthProxy()
        try {
            assertTrue(TransportProxy.installProxy("http", "127.0.0.1", 9, username = "u", password = "p"))
            TransportProxy.clear()
            val response = UrlConnTransport().exchange(
                WireRequest(url = "http://127.0.0.1:${server.address.port}/models"),
            ) { true }
            assertEquals(407, response.status)
            assertTrue(!sawAuth())
        } finally {
            server.stop(0)
        }
    }

    /** 非法安装拒收时不许动认证状态。 */
    @Test
    fun `rejected install leaves authenticator untouched`() {
        val (server, sawAuth) = fakeAuthProxy()
        try {
            assertTrue(TransportProxy.installProxy("http", "127.0.0.1", server.address.port, username = "u", password = "p"))
            assertEquals(false, TransportProxy.installProxy("gopher", "127.0.0.1", 8888))
            assertEquals(false, TransportProxy.installProxy("http", "", 8888))
            assertEquals(false, TransportProxy.installProxy("http", "127.0.0.1", 0))
            val response = UrlConnTransport().exchange(WireRequest(url = "http://example.com/models")) { true }
            assertEquals(200, response.status)
            assertTrue(sawAuth())
        } finally {
            server.stop(0)
        }
    }
}
