package com.hualuo.repotool.ui.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hualuo.engine.toolcalls.GitHubPrProposal
import com.hualuo.engine.toolcalls.PrConfirmer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 一张待用户点头的 PR 确认卡。[id] 防串台：旧卡点不动新提议。 */
class PendingPr(val id: String, val proposal: GitHubPrProposal)

/**
 * PR 工具的确认闸门（2026-10-05 全套刀）：模型提议建 PR / 合 PR 时先摆卡，人点头才动。
 *
 * 与 [WriteConfirmGate] 同一条纪律，一条不少：
 *  - **默认拒**：等不到明确点头（超时、没接闸门、卡没人管）一律按拒——
 *    「合错一个 PR」和「写错一个文件」在仓库历史上同级别不可逆；
 *  - **单飞**：一张卡没处理完第二条提议直接拒——不给「连发两个 PR 绕确认」留缝；
 *  - **收卡**：无论哪种收场 [pending] 都收走，界面不留悬案。
 *
 * 挂进程（HualuoApplication）不挂 Activity：转屏、切出再回来卡不丢。
 *
 * 为什么合 PR 也要过这道门：合并是把 head 分支的改动钉进 base 的**正式动作**，
 * 电脑版 AI 对话工具合 PR 前同样要用户确认。模型只能提议，扣扳机的永远是人。
 */
class PrConfirmGate(
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) : PrConfirmer {

    /** 当前待确认的 PR 提议；null = 没有悬着的。 */
    var pending by mutableStateOf<PendingPr?>(null)
        private set

    private val lock = Any()
    private var seq = 0
    private var latch: CountDownLatch? = null
    private var approved = false

    /** 摆卡并等决定（阻塞工具执行线程，最长 [timeoutMs]）。返回 true 才允许真动手。 */
    override fun confirm(proposal: GitHubPrProposal): Boolean {
        val waiter = CountDownLatch(1)
        var myId: String? = null
        synchronized(lock) {
            if (pending == null) {
                seq += 1
                val card = PendingPr("pr-" + seq, proposal)
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
