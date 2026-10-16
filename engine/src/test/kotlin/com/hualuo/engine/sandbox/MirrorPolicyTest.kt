package com.hualuo.engine.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorPolicyTest {

    @Test
    fun defaultHasOfficialFirstThenChinaMirrors() {
        val m = MirrorPolicy()
        assertEquals("官方应排第一（版本最正）：${m.repositories}", "https://dl-cdn.alpinelinux.org/alpine/v3.21/main", m.current())
        assertTrue("至少三家（官方+清华+中科大）：${m.repositories}", m.repositories.size >= 3)
    }

    @Test
    fun failoverMovesFailedMirrorToTail() {
        val m = MirrorPolicy()
        val official = m.current()
        val after = m.next(official)
        assertTrue("失败的那家要挪到队尾：before=${m.repositories} after=${after.repositories}", after.current() != official)
        assertEquals("队尾保留原列表（不丢源）：", official, after.repositories.last())
    }

    @Test
    fun singleMirrorFailoverIsSelf() {
        val m = MirrorPolicy(listOf("https://only-one.example/alpine"), "nameserver 1.1.1.1\n")
        assertTrue("只有一家时 next 返回自身：", m.next("https://only-one.example/alpine") === m)
    }

    @Test
    fun emptyMirrorListIsRejectedAtConstruction() {
        val err = runCatching { MirrorPolicy(emptyList(), "x") }.exceptionOrNull()
        assertTrue("空镜像表必须在构造时炸，不许等到下载期", err is IllegalArgumentException)
    }

    @Test
    fun pathBuildersJoinCorrectly() {
        val m = MirrorPolicy()
        assertEquals(
            "APKINDEX 路径拼接：",
            "https://dl-cdn.alpinelinux.org/alpine/v3.21/main/APKINDEX.tar.gz",
            m.apkIndexPathOn(m.current()),
        )
        assertEquals(
            "rootfs 路径拼接（releases 段不在 /main 仓库段下——旧拼法是 404）：",
            "https://dl-cdn.alpinelinux.org/alpine/v3.21/releases/aarch64/alpine-minirootfs-3.21.0-aarch64.tar.gz",
            m.rootfsUrlOn(m.current()),
        )
        assertEquals(
            "国内镜像的 rootfs 路径同样要拼对：",
            "https://mirrors.tuna.tsinghua.edu.cn/alpine/v3.21/releases/aarch64/alpine-minirootfs-3.21.0-aarch64.tar.gz",
            m.rootfsUrlOn("https://mirrors.tuna.tsinghua.edu.cn/alpine/v3.21/main"),
        )
        assertEquals(
            "镜像串不带仓库段（直接给到版本根）也拼得对：",
            "https://mirrors.ustc.edu.cn/alpine/v3.21/releases/aarch64/alpine-minirootfs-3.21.0-aarch64.tar.gz",
            m.rootfsUrlOn("https://mirrors.ustc.edu.cn/alpine/v3.21"),
        )
    }

    @Test
    fun rootfsSha256IsRealValueNotPlaceholder() {
        // 2026-10-16 修：占位空串等于悄悄关掉校验。真值取自官方同名 .sha256（可复核）。
        assertEquals(
            "f31202c4070c4ef7de9e157e1bd01cb4da3a2150035d74ea5372c5e86f1efac1",
            MirrorPolicy.ROOTFS_SHA256,
        )
        assertEquals("sha256 真值是 64 位十六进制：", 64, MirrorPolicy.ROOTFS_SHA256.length)
    }
}
