package com.hualuo.engine.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * proot 会话对照表（D4 busy 门 / L2 输出封顶排干 / L3 参数拒绝 / 超时强杀 / 终态行）。
 * 纯 JVM：真进程用 /bin/sh（与目标运行时同为 POSIX 进程语义），不 mock 进程对象。
 * 2026-10-16 真机接线刀补三件：环境变量真到达子进程 / 执行件缺失给人话 / 绑定 guest 冒号禁令。
 */
class ProotSessionTest {

    private fun session(launcher: ProotSession.Launcher = ProotSession.Launcher { cmd, dir, env ->
        // 测试桩：把 proot 命令转成直接跑 sh（首元素是 prootBinary 时跳过它和 -r rootfs 段）。
        // 桩不吃沙盒环境变量（宿主 sh 没必要吃 guest 的 PATH/LOADER），env 只透传不应用。
        val shim = if (cmd.firstOrNull() == "proot") {
            val idx = cmd.indexOf("/bin/sh")
            cmd.subList(idx, cmd.size)
        } else cmd
        ProcessBuilder(shim).apply { dir?.let { directory(it) } }.start()
    }) = ProotSession(rootfsDir = File("/tmp/unused-rootfs"), launcher = launcher)

    @Test
    fun echoRoundTripCarriesStdoutAndTerminalLine() {
        val outcome = session().exec("echo hello-sandbox", 10_000L)
        assertEquals("退出码应为 0：$outcome", 0, outcome.exitCode)
        assertTrue("stdout 要含回显：stdout=${outcome.stdoutTail}", outcome.stdoutTail.contains("hello-sandbox"))
        assertTrue("终态行必须有：${outcome.terminalLine}", outcome.terminalLine.startsWith("终态："))
    }

    @Test
    fun emptyCommandIsRejectedNotRun() {
        val err = runCatching { session().exec("", 5_000L) }.exceptionOrNull()
        assertTrue("空命令必须 REJECTED：err=$err", err is SandboxException && err.error.kind == SandboxError.Kind.REJECTED)
    }

    @Test
    fun overlongCommandIsRejected() {
        val big = "x".repeat(ProotSession.MAX_COMMAND_CHARS + 1)
        val err = runCatching { session().exec(big, 5_000L) }.exceptionOrNull()
        assertTrue("超长命令必须 REJECTED：err=$err", err is SandboxException && err.error.kind == SandboxError.Kind.REJECTED)
    }

    @Test
    fun missingProotBinaryIsHonestErrorNotBareIoException() {
        // 真机接线的预检：绝对路径形态的执行件不在时给指路人话（这个构建没带执行层），
        // 不许 ProcessBuilder 抛一句英文 IOException 糊弄——那是「环境错误没错误码」的旧病。
        val s = ProotSession(rootfsDir = File("/tmp/unused-rootfs"), prootBinary = "/definitely/not/here/libproot_exec.so")
        val err = runCatching { s.exec("echo hi", 5_000L) }.exceptionOrNull()
        assertTrue("缺执行件必须 EXEC：err=$err", err is SandboxException && err.error.kind == SandboxError.Kind.EXEC)
        assertTrue(
            "错误话要指名道姓（libproot_exec.so 与「没带执行层」）：${err?.message}",
            err!!.message!!.contains("libproot_exec.so") && err.message!!.contains("没带沙盒执行层"),
        )
    }

    @Test
    fun envVarsActuallyReachTheLaunchedProcess() {
        // 环境变量注入链：ProotSession 把 env 原样递给 Launcher；真机默认 Launcher 会
        // 应用到 ProcessBuilder。这里用「应用 env 的桩 + sh 回显」验证整条链。
        val seen = mutableListOf<Map<String, String>>()
        val s = ProotSession(
            rootfsDir = File("/tmp/unused-rootfs"),
            env = mapOf("SANDBOX_PROBE_ENV" to "it-reached"),
            launcher = ProotSession.Launcher { cmd, dir, env ->
                seen += env
                ProcessBuilder(listOf("/bin/sh", "-c", "printf %s \"\$SANDBOX_PROBE_ENV\""))
                    .apply {
                        dir?.let { directory(it) }
                        environment().putAll(env)
                    }.start()
            },
        )
        val outcome = s.exec("anything", 10_000L)
        assertEquals("子进程要真收到环境变量：stdout=${outcome.stdoutTail}", "it-reached", outcome.stdoutTail)
        assertEquals("env 原样递给 Launcher（一个不少一个不多）：", listOf(mapOf("SANDBOX_PROBE_ENV" to "it-reached")), seen)
    }

    @Test
    fun bindGuestWithColonIsRejectedNotSilentlyMangled() {
        // proot -b 只认 host:guest：把只读旗标写成 host:guest:ro 会让挂载点名字带冒号，
        // 后面 apk add 找不到文件还看不出因果——当场炸响，不许诈哑。
        val s = ProotSession(rootfsDir = File("/tmp/r"), bindMounts = listOf(File("/tmp/x") to "/apks-tmp:ro"))
        val err = runCatching { s.buildCommand("true") }.exceptionOrNull()
        assertTrue("guest 带冒号必须拒：err=$err", err is IllegalArgumentException)
        assertTrue("拒绝话要说清因果（ro 旗标吃掉挂载点名）：${err?.message}", err!!.message!!.contains("ro"))
    }

    @Test
    fun timeoutKillsAndLeavesNoSentinelMinusOne() {
        val outcome = session().exec("sleep 30", 1_000L)
        assertTrue("超时必须标记 timedOut：$outcome", outcome.timedOut)
        assertNull("超时的退出码必须是 null（不再拿 -1 当哨兵，D4）：exitCode=${outcome.exitCode}", outcome.exitCode)
        assertTrue("超时也要有终态行：${outcome.terminalLine}", outcome.terminalLine.contains("强杀") || outcome.terminalLine.contains("超时"))
    }

    @Test
    fun busyGateRejectsSecondCommandWithExplicitReason() {
        val s = session()
        // 真实占用：起一个 sleep，然后并发第二个命令必须被 BUSY 拒绝
        val holder = ProcessBuilder("/bin/sh", "-c", "sleep 2").start()
        try {
            // 直接占住内部 busy 位（不通过 exec，精确模拟「上一个操作还在跑」）
            val field = ProotSession::class.java.getDeclaredField("currentProcess")
            field.isAccessible = true
            field.set(s, holder)
            val err = runCatching { s.exec("echo should-not-run", 5_000L) }.exceptionOrNull()
            assertTrue("第二个命令必须 BUSY：err=$err", err is SandboxException && err.error.kind == SandboxError.Kind.BUSY)
            assertTrue("拒绝原因必须说明上一个操作还在跑：${err?.message}", err!!.message!!.contains("上一个操作"))
        } finally {
            holder.destroyForcibly()
            holder.waitFor(2, TimeUnit.SECONDS)
        }
    }

    @Test
    fun outputOverCapIsDrainedNotDeadlocked() {
        // 输出远超 TAIL_CHARS：验证不卡死、流被排干（进程正常退出）
        val outcome = session().exec("seq 1 20000", 15_000L)
        assertEquals("大批量输出不许卡死也不许丢退出码：$outcome", 0, outcome.exitCode)
        assertTrue("尾巴只保最后一段：stdout 长度=${outcome.stdoutTail.length}", outcome.stdoutTail.length <= ProotSession.TAIL_CHARS + 100)
        assertTrue("尾部应含最后几行：", outcome.stdoutTail.contains("19999") || outcome.stdoutTail.contains("20000"))
    }

    @Test
    fun failedCommandCarriesExitCodeAndStderr() {
        val outcome = session().exec("echo boom >&2; exit 3", 10_000L)
        assertEquals("退出码要照实：$outcome", 3, outcome.exitCode)
        assertTrue("stderr 尾要含 boom：${outcome.stderrTail}", outcome.stderrTail.contains("boom"))
    }
}
