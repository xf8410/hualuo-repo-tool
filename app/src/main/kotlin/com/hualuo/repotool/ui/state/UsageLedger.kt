package com.hualuo.repotool.ui.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 一次请求的用量账（provider 流尾 usage 上报的字段，缺哪个记哪个的 null）。
 * 引擎不估算、界面不补 0——null 在界面上就是「— / 未提供」。
 */
data class UsageRecord(
    val atMs: Long,
    val model: String,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val cachedTokens: Long? = null,
    val uncachedTokens: Long? = null,
    val totalTokens: Long? = null,
)

/**
 * 当前会话的用量账本（顶栏用量条的数据源，对齐 Agora 那条「缓存命中 · 未缓存输入 · 输出」）。
 *
 * 边界说实话：账只记**本进程、当前会话**——换会话/新开即清（ChatRuntime 换户时调 [clear]），
 * 不落盘。不是 Agora 的全历史账本，明细条上把这句话写出来，不装成永久账。
 * records 是 Compose 可观察的：入账即重绘，条与明细读同一份。
 */
class UsageLedger {

    private val lock = Any()
    private val rows = ArrayList<UsageRecord>()

    /** 已入账的请求（旧在前）；界面直接读它，入账即变。 */
    var records by mutableStateOf<List<UsageRecord>>(emptyList())
        private set

    /** 入账一条；超过上限丢最旧的（账是给人看的，不是审计日志）。 */
    fun add(record: UsageRecord) {
        synchronized(lock) {
            rows.add(record)
            if (rows.size > MAX_RECORDS) {
                rows.subList(0, rows.size - MAX_RECORDS).clear()
            }
            records = rows.toList()
        }
    }

    /** 清户（换会话/新开/删当前时由 ChatRuntime 调）。 */
    fun clear() {
        synchronized(lock) {
            rows.clear()
            records = emptyList()
        }
    }

    companion object {
        /** 账本封顶：超出丢最旧，防一场长会话把账撑成内存负担。 */
        const val MAX_RECORDS = 100
    }
}
