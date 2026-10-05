package com.hualuo.repotool.ui.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hualuo.engine.toolcalls.ActionConfirmer
import com.hualuo.engine.toolcalls.GitHubActionProposal
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 一张待用户点头的动作确认卡。[id] 防串台：旧卡点不动新提议。 */
class PendingAction(val id: String, val proposal: GitHubActionProposal)

/**
 * GitHub 动作族（建分支/删分支/建 issue/评论/关 PR）的确认闸门（2026-10-05 全套刀）。
 *
 * 六件全过这道门——用户点名「防止对话 AI 修改任何方式的 pr 和分支工具」：
 * 模型只能提议（摆成键值对卡），人看得清「要动什么」点头才真执行。删分支、关 PR
 * 这类不好回滚的尤其如此。纪律与 [WriteConfirmGate] / [PrConfirmGate] 完全同形：
 *  - **默认拒**：超时、没接闸门、卡没人管一律按拒；
 *  - **单飞**：一张卡没处理完第二条提议直接拒；
 *  - **收卡**：任何收场 [pending] 都收走。
 *
 * 挂进程不挂 Activity（转屏不丢）。
 */
class GitHubActionGate(
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) : ActionConfirmer {

    /** 当前待确认的动作提议；null = 没有悬着的。 */
    var pending by mutableStateOf<PendingAction?>(null)
        private set

    private val lock = Any()
    private var seq = 0
    private var latch: CountDownLatch? = null
    private var approved = false

    /** 摆卡并等决定（阻塞工具执行线程，最长 [timeoutMs]）。返回 true 才允许真执行。 */
    override fun confirm(proposal: GitHubActionProposal): Boolean {
        val waiter = CountDownLatch(1)
        var myId: String? = null
        synchronized(lock) {
            if (pending == null) {
                seq += 1
                val card = PendingAction("act-" + seq, proposal)
                myId = card.id
                pending = card
                approved = false
                latch = waiter
            }
        }
        val id = myId ?: return false
        val decided = runCatching { waiter.await(timeoutMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)
        synchronized(lock) {
            if (pending?.id == id) pending = null
            if (latch === waiter) latch = null
        }
        return synchronized(lock) { decided && approved }
    }

    /** 用户点头：放行并收卡。 */
    fun approve(id: String) = resolve(id, true)

    /** 用户拒绝：收卡并按拒处理。 */
    fun reject(id: String) = resolve(id, false)

    private fun resolve(id: String, ok: Boolean) {
        synchronized(lock) {
            if (pending?.id != id) return
            approved = ok
            latch?.countDown()
        }
    }

    companion object {
        /** 等确认的上限：5 分钟（与写卡同一条规矩）。 */
        const val DEFAULT_TIMEOUT_MS = 5 * 60_000L
    }
}
