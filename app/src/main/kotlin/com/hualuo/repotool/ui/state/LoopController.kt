package com.hualuo.repotool.ui.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

import android.os.Handler
import android.os.Looper

/**
 * 会话循环调度器（loop 页实装刀）：开着时，每轮生成落定后隔 [intervalSec] 秒
 * 自动往**当前会话**发一句续跑提示词（默认「继续」），打到 [maxRounds] 轮停。
 *
 * 设计纪律：
 *  - 驱动源=ChatRuntime.onSettled（成功/失败/按停都算一轮落定），不轮询 busy；
 *  - **用户按停=明确喊停**：cancelled=true 时不排下一轮（把控制权还给人）；
 *  - 换会话（sessionId 变）或关开关：清零计数，计时器作废；
 *  - 检查点就是会话本身（每轮都落库），崩了重开=从最新一轮继续数；
 *  - Handler 挂主线程，send 走 AppUiState 现成入口（限流/闸门全套照走）。
 */
class LoopController(
    private val state: () -> AppUiState?,
) {

    /** 真态（UI 直接读）。rounds=已发出去的循环轮数。 */
    var enabled by mutableStateOf(false)
        private set
    var intervalSec by mutableStateOf(300)
        private set
    var maxRounds by mutableStateOf(20)
        private set
    var rounds by mutableStateOf(0)
        private set
    var nextAtMs by mutableStateOf(0L)
        private set

    /** 循环续跑那句话（loop 页可改；原文进会话不过滤）。 */
    var prompt: String = "继续"

    // Handler 懒加载：AppUiState 在纯 JVM 单元测试里会被直接构造，构造期碰 Looper
    // 直接 RuntimeException 炸一片（AgoraImportApplyTest/AppUiStateCourierTest 连环红）。
    // 只有真调度（start/fireNow/onSettled 落定）才需要主线程；测试路径根本不碰这些。
    private val handler: Handler by lazy { Handler(Looper.getMainLooper()) }
    private var watchedSession: String? = null
    private val tick = Runnable { fireOneRound() }

    /** 设置页开关（改配置会话不重排；下轮落定后才按新间隔走）。 */
    /** 设置页三件口：开（接着当前轮数跑）、停、单独调参。 */
    fun start() {
        enabled = true
        if (rounds >= maxRounds) rounds = 0
    }

    fun stop() {
        enabled = false
        handler.removeCallbacks(tick)
        nextAtMs = 0L
    }

    fun tuneInterval(sec: Int) {
        intervalSec = sec.coerceIn(60, 3600)
    }

    fun tuneMaxRounds(n: Int) {
        maxRounds = n.coerceIn(1, 100)
    }

    fun configure(enabled: Boolean, intervalSec: Int, maxRounds: Int, prompt: String) {
        this.enabled = enabled
        this.intervalSec = intervalSec.coerceIn(60, 3600)
        this.maxRounds = maxRounds.coerceIn(1, 100)
        this.prompt = prompt.ifBlank { "继续" }
        if (!enabled) {
            handler.removeCallbacks(tick)
            nextAtMs = 0L
        }
    }

    /** ChatRuntime.onSettled 回调（生成落定，含按停标记）。 */
    fun onSettled(cancelled: Boolean) {
        handler.removeCallbacks(tick)
        if (!enabled) return
        if (cancelled) {
            // 人喊停：循环也停（开关不动，屏上状态如实显示）
            nextAtMs = 0L
            return
        }
        if (rounds >= maxRounds) {
            nextAtMs = 0L
            return
        }
        nextAtMs = System.currentTimeMillis() + intervalSec * 1000L
        handler.postDelayed(tick, intervalSec * 1000L)
    }

    /** 会话切换钩子：换了会话，轮数清零、旧计时作废（新会话从头数）。 */
    fun onSessionChanged(sessionId: String?) {
        if (sessionId == watchedSession) return
        watchedSession = sessionId
        handler.removeCallbacks(tick)
        rounds = 0
        nextAtMs = 0L
    }

    /** 手动立即跑一轮（设置页「现在跑一轮」；同样走全套闸门）。 */
    fun fireNow() {
        handler.removeCallbacks(tick)
        fireOneRound()
    }

    private fun fireOneRound() {
        val ui = state() ?: return
        if (!enabled || rounds >= maxRounds) {
            nextAtMs = 0L
            return
        }
        if (ui.chat.busy) {
            // 上一轮还没落定（理论到不了这，双保险）：推迟 30s 再看
            handler.postDelayed(tick, 30_000L)
            return
        }
        val sessionId = ui.chat.sessionId
        if (sessionId != watchedSession) {
            onSessionChanged(sessionId)
            return
        }
        rounds += 1
        ui.chat.send(prompt, ui.currentModel)
        nextAtMs = 0L
    }
}
