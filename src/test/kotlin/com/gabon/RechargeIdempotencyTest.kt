package com.gabon

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
        val first = ledger.creditRecharge(customer, 500, "CR-1")
        val second = ledger.creditRecharge(customer, 500, "CR-1") // 同一 orderNo

        assertThat(first).isTrue()
        assertThat(second).isFalse() // 幂等短路
        assertThat(ledger.balanceOf(customer)).isEqualTo(500) // 只加一次
        assertThat(dsl.fetchCount(LEDGER_TXN)).isEqualTo(1) // txn 只一行
    }
}
