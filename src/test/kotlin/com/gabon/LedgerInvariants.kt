package com.gabon

import com.gabon.jooq.tables.references.ACCOUNT
import com.gabon.jooq.tables.references.LEDGER_ENTRY
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.jooq.impl.DSL

/**
 * 全量不变量断言(spec §3.3,测试共享资产):
 * 每账户 balance == Σ ledger_entry.amount(全部账户,不只 customer)+ 全局 Σ = 0。
 * 资金链路 E2E 测试终局必跑(批 2/3 复用)。
 */
object LedgerInvariants {
    fun assertHolds(dsl: DSLContext) {
        val entrySums: Map<Long, Long> =
            dsl
                .select(LEDGER_ENTRY.ACCOUNT_ID, DSL.sum(LEDGER_ENTRY.AMOUNT))
                .from(LEDGER_ENTRY)
                .groupBy(LEDGER_ENTRY.ACCOUNT_ID)
                .fetch()
                .associate { it.value1()!! to it.value2()!!.toLong() }
        dsl.select(ACCOUNT.ID, ACCOUNT.BALANCE).from(ACCOUNT).fetch().forEach { row ->
            assertThat(row.value2()!!)
                .describedAs("account ${row.value1()} balance vs Σentries")
                .isEqualTo(entrySums[row.value1()!!] ?: 0L)
        }
        assertThat(entrySums.values.sum()).describedAs("global Σ entries").isEqualTo(0L)
    }
}
