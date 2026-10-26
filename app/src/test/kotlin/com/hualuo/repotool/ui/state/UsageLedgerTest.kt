package com.hualuo.repotool.ui.state

import org.junit.Assert.assertEquals
import org.junit.Test

/** 用量账本：入账、封顶丢最旧、清户。顶栏那条读的就是这份真账，账乱了条就是骗人。 */
class UsageLedgerTest {

    @Test
    fun `records are observable and keep order`() {
        val ledger = UsageLedger()
        ledger.add(UsageRecord(atMs = 1L, model = "m", inputTokens = 10L, outputTokens = 2L))
        ledger.add(UsageRecord(atMs = 2L, model = "m", cachedTokens = 5L, uncachedTokens = 5L))

        assertEquals(2, ledger.records.size)
        assertEquals("旧在前（明细倒着摆），不许倒客为主", 1L, ledger.records.first().atMs)
        assertEquals(2L, ledger.records.last().atMs)
    }

    @Test
    fun `overflow drops the oldest rows`() {
        val ledger = UsageLedger()
        for (i in 0 until UsageLedger.MAX_RECORDS + 10) {
            ledger.add(UsageRecord(atMs = i.toLong(), model = "m"))
        }

        assertEquals(UsageLedger.MAX_RECORDS, ledger.records.size)
        assertEquals("丢的是最旧的 10 条", 10L, ledger.records.first().atMs)
    }

    @Test
    fun `clear empties the book`() {
        val ledger = UsageLedger()
        ledger.add(UsageRecord(atMs = 1L, model = "m"))
        ledger.clear()

        assertEquals(0, ledger.records.size)
    }
}
