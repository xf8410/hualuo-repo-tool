package com.hualuo.repotool.ui.state

import com.hualuo.engine.toolcalls.SandboxConfirmProposal
import com.hualuo.engine.toolcalls.SandboxConfirmer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 一张待用户点头的沙盒动作卡。[id] 防串台：旧卡点不动新提议。 */
class PendingSandbox(val id: String, val proposal: SandboxConfirmProposal)

/**
 * 沙盒族（run_command / install / remove）的确认闸门（终端页实装刀）。
 *
 * 与 [GitHubActionGate] / [PrConfirmGate] 同一条纪律：
 *  - **默认拒**：超时、单飞（一张卡没处理完第二条直接拒）、没人接一律按拒；
 *  - 阻塞的是工具执行线程，UI 只管摆卡收卡；
 *  - 挂进程不挂 Activity（转屏不丢）。
 */
class SandboxGate(
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) : SandboxConfirmer {

    /** 当前待确认的沙盒提议；null = 没有悬着的。 */
    private val _pending = MutableStateFlow<PendingSandbox?>(null)
    val pending: StateFlow<PendingSandbox?> = _pending

    private val lock = Any()
    private var seq = 0
    private var latch: CountDownLatch? = null
    private var approved = false

    /** 摆卡并等决定（阻塞工具执行线程，最长 [timeoutMs]）。返回 true 才允许真执行。 */
    override fun confirm(proposal: SandboxConfirmProposal): Boolean {
        val waiter = CountDownLatch(1)
        var myId: String? = null
        synchronized(lock) {
            if (_pending.value == null) {
                seq += 1
                val card = PendingSandbox("sbx-" + seq, proposal)
                myId = card.id
                _pending.value = card
                approved = false
                latch = waiter
            }
        }
        if (myId == null) return false
        val ok = waiter.await(timeoutMs, TimeUnit.MILLISECONDS)
        synchronized(lock) {
            if (_pending.value?.id == myId) _pending.value = null
            latch = null
        }
        return ok && approved
    }

    /** 用户点了「允许」：只有对得上 id 的卡才收（旧卡不吃新决定）。 */
    fun approve(id: String) = resolve(id, true)

    /** 用户点了「不执行」：同上，对不上 id 的拒绝不生效。 */
    fun reject(id: String) = resolve(id, false)

    private fun resolve(id: String, yes: Boolean) {
        synchronized(lock) {
            if (_pending.value?.id != id) return
            approved = yes
            _pending.value = null
            latch?.countDown()
            latch = null
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 120_000L
    }
}
