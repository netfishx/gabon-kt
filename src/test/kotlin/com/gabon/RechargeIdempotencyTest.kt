package com.gabon

import com.gabon.jooq.tables.references.LEDGER_ENTRY
import com.gabon.jooq.tables.references.LEDGER_TXN
import com.gabon.wallet.internal.ledger.LedgerService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/** 验收④：幂等充值——重复 orderNo 只入账一次 */
class RechargeIdempotencyTest : AbstractIntegrationTest() {
    @Autowired
    lateinit var ledger: LedgerService

    @Test
    fun `duplicate recharge credits exactly once`() {
        val customer = 100L
        val first = ledger.creditRecharge("CR-1", customer, 500)
        val second = ledger.creditRecharge("CR-1", customer, 500) // 同一 orderNo

        assertThat(first).isTrue()
        assertThat(second).isFalse() // 幂等短路
        assertThat(ledger.balanceOf(customer)).isEqualTo(500) // 只加一次
        assertThat(dsl.fetchCount(LEDGER_TXN)).isEqualTo(1) // txn 只一行
    }

    @Test
    fun `replay with different amount is still a no-op`() {
        val customer = 101L
        ledger.creditRecharge("CR-DUP", customer, 500)
        // 同 biz_no 异金额:幂等门必须在守卫/入账之前,重放不得产生任何账务效果
        assertThat(ledger.creditRecharge("CR-DUP", customer, 999)).isFalse()
        assertThat(ledger.balanceOf(customer)).isEqualTo(500)
        assertThat(dsl.fetchCount(LEDGER_ENTRY)).isEqualTo(2)
    }
}
