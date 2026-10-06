package com.hualuo.repotool

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hualuo.engine.api.ProviderRouting
import com.hualuo.repotool.ui.state.AppUiState
import com.hualuo.repotool.ui.state.ModelSettingsRuntime
import com.hualuo.repotool.ui.state.ModelSettingsState
import com.hualuo.repotool.ui.state.UiPersistenceBundle
import com.hualuo.repotool.ui.state.WriteConfirmGate
import com.hualuo.repotool.ui.state.createUiPersistence
import java.io.File

/**
 * 进程级内核：设置出口、会话仓、全部界面状态都住这里，不归任何一任 Activity 管。
 *
 * 0.6.1 取证版改动：构造器里的 init { modelSettings } 预热挪到 onCreate——
 * 构造期从此零业务代码，崩溃观察器（attachBaseContext 最早挂）能罩住
 * 之后的一切 Java 异常；modelSettings 本就是 lazy，首次使用自然挂上，
 * 行为与预热版一字不差（RootScreen 取 uiState 时才真正触达）。
 *
 * 【2026-10-03 修】上面那句「挪到 onCreate」当时只改了注释、没落地代码：
 * 构造器里的预热删了，onCreate 里也没补，于是 `install()` 只在那个 lazy 被
 * **谁**首次触达时才发生——而没有任何人触达它（uiState 的构造里也不碰它）。
 * 结果 `ModelSettingsRuntime.current()` 长期为 null，设置里的「提供商」「模型」
 * 两页各自 `?: return`，整页空白（机主实报：「选择 API、已经选择模型，里面也是空的」）。
 * 现在 onCreate 里显式预热一次，并且失败就把原因记进 ModelSettingsRuntime，界面照实出声。
 */
class HualuoApplication : Application() {

    val uiBundle: UiPersistenceBundle by lazy { createUiPersistence(this) }

    /** 多提供商模型设置的全进程句柄；设置页与聊天发送共用这一份事实。 */
    val modelSettings: ModelSettingsState by lazy {
        ModelSettingsState(uiBundle.persistence, changed = { uiBundle.persistence.flush() }).also {
            ModelSettingsRuntime.install(it)
            ProviderRouting.install(it::sessionFor)
        }
    }

    val writeGate = WriteConfirmGate()

    /** PR 闸门（2026-10-05 全套刀）：建 PR / 合 PR 先摆卡，人点头才动。 */
    val prGate = com.hualuo.repotool.ui.state.PrConfirmGate()

    /** 动作闸门（2026-10-05 全套刀）：建分支/删分支/建 issue/评论/关 PR 六件全过这道门。 */
    val actionGate = com.hualuo.repotool.ui.state.GitHubActionGate()
    val sandboxGate = com.hualuo.repotool.ui.state.SandboxGate()

    /** 定时任务表（tasks 页实装刀）：files/tasks.json，Worker 与设置页共用一个实例。 */
    val taskStore by lazy { com.hualuo.repotool.notify.TaskStore(java.io.File(filesDir, "tasks.json")) }

    /** 记忆库：建不起来就 null，记忆工具不注册，聊天仍照常。 */
    private val memoryStore: com.hualuo.engine.memory.MemoryStore? by lazy {
        try {
            com.hualuo.engine.memory.MemoryStore(
                memoryDir = File(filesDir, "memory_db"),
                activeFile = File(filesDir, "active_memory.md"),
            )
        } catch (_: Exception) {
            null
        }
    }

    /** 技能库（M4 第六刀）：skill_db，无活动记忆文件；建不起来 null 降级。 */
    private val skillStore: com.hualuo.engine.memory.MemoryStore? by lazy {
        try {
            com.hualuo.engine.memory.MemoryStore(memoryDir = File(filesDir, "skill_db"))
        } catch (_: Exception) {
            null
        }
    }

    val uiState: AppUiState by lazy {
        AppUiState(
            persist = uiBundle.persistence,
            store = uiBundle.store,
            writeGate = writeGate,
            prGate = prGate,
            actionGate = actionGate,
            memoryStore = memoryStore,
            skillStore = skillStore,
            modelSettings = modelSettings,
            sandboxGate = sandboxGate,
            sandboxRootDir = java.io.File(filesDir, "sandbox"),
            imageGenConfig = {
                com.hualuo.engine.toolcalls.ImageGenConfig(
                    apiKey = uiBundle.persistence.load(com.hualuo.repotool.ui.state.UiKeys.IMAGE_GEN_KEY).orEmpty(),
                    baseUrl = uiBundle.persistence.load(com.hualuo.repotool.ui.state.UiKeys.IMAGE_GEN_BASE_URL).orEmpty(),
                    model = uiBundle.persistence.load(com.hualuo.repotool.ui.state.UiKeys.IMAGE_GEN_MODEL).orEmpty(),
                    size = uiBundle.persistence.load(com.hualuo.repotool.ui.state.UiKeys.IMAGE_GEN_SIZE).orEmpty(),
                )
            },
            imageGenPersist = { bytes, prefix ->
                val dir = File(filesDir, "tool_images").apply { mkdirs() }
                val out = File(dir, "$prefix-${System.currentTimeMillis()}.png")
                out.writeBytes(bytes)
                out.absolutePath
            },
            watchInboxDir = File(filesDir, "watch_inbox").apply { mkdirs() },
            watchFramesDir = File(filesDir, "watch_frames").apply { mkdirs() },
        )
    }

    private val startupNotice = OnceNotice {
        listOfNotNull(uiBundle.notice, CrashObserver.consumeStartupCrashNotice(this))
            .joinToString("；")
            .ifEmpty { null }
    }
    fun consumeStartupNotice(): String? = startupNotice.consume()

    var backupProgress by mutableStateOf<String?>(null)
    var courierProgress by mutableStateOf<String?>(null)

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // 最早的挂钩点（早于 onCreate）：之后任何线程的未捕获异常都先取证再收尸
        CrashObserver.install(base)
    }

    override fun onCreate() {
        super.onCreate()
        // 幂等：attachBaseContext 已装过就跳过；这行只是双保险
        CrashObserver.install(this)
        // 崩溃留档开关（关于页）：设置件此时可读，读一次喂内存值（崩溃回调里只读内存）
        CrashObserver.keepLocal = uiBundle.persistence.load(com.hualuo.repotool.ui.state.UiKeys.CRASH_KEEP_LOCAL) != "false"
        warmUpModelSettings()
    }

    /**
     * 模型设置句柄预热：在崩溃观察器装好之后显式触达一次，别再指望「谁先用到谁触达」。
     *
     * 建不起来也不吞：原因记进 [ModelSettingsRuntime]，设置页照实摆出来，
     * 不摆「整页什么也没有」这种最难查的形态。
     */
    private fun warmUpModelSettings() {
        runCatching { modelSettings }.onFailure { e ->
            ModelSettingsRuntime.recordFailure(e.message?.takeIf { it.isNotBlank() } ?: e::class.java.simpleName)
        }
    }
}

class OnceNotice(private val provide: () -> String?) {
    private var consumed = false
    fun consume(): String? = if (consumed) null else provide()?.also { consumed = true }
}