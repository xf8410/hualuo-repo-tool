package com.hualuo.repotool.ui.state

/**
 * 沙盒 proot 执行层的装配参数（2026-10-16 真机接线刀）。
 *
 * 三个 .so（libproot_exec.so / libproot_loader.so / libtalloc.so）由 CI 从钉死源码
 * 构建打进 APK（jniLibs + useLegacyPackaging），系统解压到 nativeLibraryDir——
 * 那是应用唯一合法的可执行目录。这里只装四条路径事实：
 *  - [execPath]：libproot_exec.so 绝对路径（ProotSession 的执行件）
 *  - [loaderPath]：libproot_loader.so 绝对路径（PROOT_LOADER 环境变量——
 *    proot 按它把 loader 映进 guest，不设就是编译期默认路径，真机上不存在）
 *  - [libraryDir]：nativeLibraryDir（LD_LIBRARY_PATH——proot 自己的 libtalloc 依赖在这找）
 *  - [tmpDir]：proot 宿主侧临时目录（PROOT_TMP_DIR，proot 造链接文件用，须可写）
 */
data class SandboxProotLaunch(
    val execPath: String,
    val loaderPath: String,
    val libraryDir: String,
    val tmpDir: String,
)

/**
 * 进程级登记口（与 [ToolRegistryRuntime] / ModelSettingsRuntime 同一套形状）：
 * app 入口（HualuoApplication）装一次，工具装配口（ToolWiring）现读。
 *
 * 为什么不走构造参数链：AppUiState 已在 999 行红线边上（985），为两条参数
 * 再穿三层构造不划算；登记口与读口都在同包，装配时序由 Application.onCreate
 * 先于界面构造保证。null = 没登记（纯 JVM 测试、或这个构建没带执行件）——
 * 引擎回落 PATH 找 proot，找不到时 ProotSession 预检给人话，不装样子。
 */
object SandboxProotRuntime {
    @Volatile
    private var launch: SandboxProotLaunch? = null

    fun install(l: SandboxProotLaunch) {
        launch = l
    }

    fun current(): SandboxProotLaunch? = launch
}
