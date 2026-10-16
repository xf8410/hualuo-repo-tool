package com.hualuo.engine.sandbox

/**
 * 下载源与 DNS 策略（修复 D5：镜像与 DNS 硬编码、单点、不可换源）。
 *
 * 旧实现把镜像写死为 dl-cdn 一家、resolv.conf 写死 8.8.8.8/1.1.1.1——
 * 国内网络一抖就「环境错误」且无路可换。从这里开始：
 *  - 镜像是一张有序列表，下载失败自动切下一家（见 [next]）；
 *  - DNS 内容是设置项（默认保持官方版取值，可改）；
 *  - rootfs 与 APKINDEX 同一套镜像表（路径拼接规则见 [apkIndexPath] / [rootfsUrlOn]）。
 *
 * 2026-10-16 修 URL 拼接 bug（排雷审计实测）：rootfs 的 releases 段与 APKINDEX 的
 * 仓库段（/main）**不在同一路径下**——同一镜像站点上，包索引挂在 …/v3.21/main/，
 * rootfs 挂在 …/v3.21/releases/。旧实现直接往镜像串后拼 /releases/aarch64/，
 * 三家镜像全部拼成 …/v3.21/main/releases/… 一律 404，首次装环境必死（死得诚实，
 * 但还是死）。现在先把仓库段剥掉再拼。
 */
class MirrorPolicy(
    val repositories: List<String> = DEFAULT_MIRRORS,
    val resolvConf: String = DEFAULT_RESOLV,
) {

    init {
        require(repositories.isNotEmpty()) { "镜像列表不许为空：至少要留一家可下载来源" }
    }

    /** 当前应使用的镜像（失败前第一家）。 */
    fun current(): String = repositories.first()

    /**
     * 下载失败后的降级视图：把失败那家从表头挪走，下一家顶上。
     * 全部换完还不行就保持原列表（调用方报 DOWNLOAD，错误里带最后一家地址）。
     */
    fun next(failedMirror: String): MirrorPolicy {
        if (repositories.size <= 1) return this
        val rest = repositories.filter { it != failedMirror }
        if (rest.isEmpty()) return this
        return MirrorPolicy(rest + listOf(failedMirror), resolvConf)
    }

    /**
     * rootfs 下载地址：镜像串是仓库地址（…/v3.21/main 结尾），rootfs 却在
     * releases 段（…/v3.21/releases/aarch64/）——先剥掉仓库段再拼。
     * 镜像串不带仓库段（直接给到 …/v3.21）也拼得对；只认结尾的 /main，
     * 不会被 /mainland 这类路径误伤。
     */
    fun rootfsUrlOn(mirror: String): String {
        val base = if (mirror.endsWith("/main")) mirror.removeSuffix("/main") else mirror
        return "$base/releases/aarch64/$ROOTFS_FILE"
    }

    /** 镜像串已含仓库段（…/main），直接拼文件名。 */
    fun apkIndexPathOn(mirror: String, repoSub: String = "main"): String = "$mirror/APKINDEX.tar.gz"

    companion object {
        const val ALPINE_VERSION_PATH = "v3.21"

        /** 官方在前（版本最正），清华与中科大做国内兜底；都挂了才报 DOWNLOAD。 */
        val DEFAULT_MIRRORS = listOf(
            "https://dl-cdn.alpinelinux.org/alpine/$ALPINE_VERSION_PATH/main",
            "https://mirrors.tuna.tsinghua.edu.cn/alpine/$ALPINE_VERSION_PATH/main",
            "https://mirrors.ustc.edu.cn/alpine/$ALPINE_VERSION_PATH/main",
        )

        /** 默认与官方版一致的 DNS 取值；设置页可改，不再写死在执行路径里。 */
        const val DEFAULT_RESOLV = "nameserver 8.8.8.8\nnameserver 1.1.1.1\n"

        const val ROOTFS_FILE = "alpine-minirootfs-3.21.0-aarch64.tar.gz"

        /**
         * rootfs 的 sha256 真值（2026-10-16 补上，替换旧占位空串）：
         * 取自官方发布的同名 .sha256 文件
         * （dl-cdn.alpinelinux.org/alpine/v3.21/releases/aarch64/ 下，可复核）。
         * 最大的一个下载反而一直没校验——占位空串在接线层等于关掉了校验，现在真接线。
         */
        const val ROOTFS_SHA256 = "f31202c4070c4ef7de9e157e1bd01cb4da3a2150035d74ea5372c5e86f1efac1"
    }
}
