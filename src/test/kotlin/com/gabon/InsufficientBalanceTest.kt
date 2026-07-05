package com.gabon

import com.gabon.jooq.tables.references.ACCOUNT
import com.gabon.wallet.internal.ledger.AccountKind
import com.gabon.wallet.internal.ledger.LedgerService
import com.gabon.wallet.internal.ledger.OWNER_CUSTOMER
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * 验收④：守卫 SQL 探针——余额不足则 0 行 → 抛，余额不变。
 * 探针刻意留在测试层（不进钱核 LedgerService）：它只改余额投影、不写 ledger 分录，
 * 若作为 public 业务方法会被误当扣减模板、破坏 balance==Σledger。真实扣减走双分录（见 LedgerService.creditRecharge）。
 */
class InsufficientBalanceTest : AbstractIntegrationTest() {
    @Autowired
    lateinit var ledger: LedgerService

    @Test
    fun `deduct beyond balance fails and leaves balance intact`() {
        val customer = 200L
        ledger.creditRecharge("CR-2", customer, 100)

        assertThatThrownBy { guardedDeduct(customer, 500) }
            .isInstanceOf(IllegalArgumentException::class.java)

        assertThat(ledger.balanceOf(customer)).isEqualTo(100) // 未被扣减
    }

    /** 守卫扣减探针：`WHERE balance >= amount` → 不足则 0 行 → 抛。仅证明守卫 SQL，非钱核。 */
    private fun guardedDeduct(
        customerId: Long,
        amount: Long,
    ) {
        val rows =
            dsl
                .update(ACCOUNT)
                .set(ACCOUNT.BALANCE, ACCOUNT.BALANCE.minus(amount))
                .set(ACCOUNT.VERSION, ACCOUNT.VERSION.plus(1))
                .where(
                    ACCOUNT.OWNER_KIND
                        .eq(OWNER_CUSTOMER)
                        .and(ACCOUNT.OWNER_ID.eq(customerId))
                        .and(ACCOUNT.KIND.eq(AccountKind.AVAILABLE))
                        .and(ACCOUNT.BALANCE.ge(amount)),
                ).execute()
        require(rows == 1) { "insufficient balance" }
    }
}
