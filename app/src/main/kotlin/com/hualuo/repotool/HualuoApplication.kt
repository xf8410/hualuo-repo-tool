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
 * 之后的一切 Java 异常。
 *
 * 0.6.2 修（用户实报「模型页啥也没有、接不了 API」+ 崩溃留档对照）：
 * 上面那次挪动**搬丢了**——预热只是从构造器挪走，并没有在 onCreate 补上，
 * 而 [modelSettings] 是 lazy，lazy 只在被读时才跑初始化块。全仓没有任何一处
 * 读它（RootScreen 只拿 uiState；AppUiState/ChatRuntime 里的
 * ModelSettingsRuntime.current() 都在 lambda 里晚读），于是：
 *   - ModelSettingsRuntime.install 永不执行 -> 设置的「提供商」「模型」两页
 *     第一行 current() ?: return 直接静默空屏（用户截图那一页）；
 *   - ProviderRouting.install 同样永不执行 -> 聊天侧拿不到任何提供商会话。
 * 修法在 onCreate：显式把句柄挂上（[installModelSettings]），仍然构造期零业务代码。
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
            uiBundle.persistence,
            uiBundle.store,
            writeGate,
            memoryStore,
            skillStore,
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
        installModelSettings()
    }

    /**
     * 把模型设置句柄挂上进程（0.6.2 新增，见类头注释里那次「搬丢了」的账）。
     *
     * 已装就什么都不做（install 幂等，但显式判断让「已装」这件事在代码里可读）。
     * 未装时读一次 [modelSettings]：lazy 的初始化块里就装着
     * ModelSettingsRuntime.install 与 ProviderRouting.install，触到 lazy 即装上。
     */
    private fun installModelSettings() {
        if (ModelSettingsRuntime.current() == null) {
            // 读句柄即触发 lazy 初始化；这行的全部收获是它的副作用
            @Suppress("UNUSED_EXPRESSION")
            modelSettings
        }
    }
}

class OnceNotice(private val provide: () -> String?) {
    private var consumed = false
    fun consume(): String? = if (consumed) null else provide()?.also { consumed = true }
}