package com.gabon

import com.gabon.jooq.tables.references.ACCOUNT
import com.gabon.jooq.tables.references.LEDGER_ENTRY
import com.gabon.jooq.tables.references.LEDGER_TXN
import com.gabon.wallet.internal.ledger.AccountKind
import com.gabon.wallet.internal.ledger.OWNER_CUSTOMER
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.support.TransactionTemplate

/**
 * 三层不变量的 ②/③ 层探针(spec §2.1/§2.3):
 * ② gabon_app 对 ledger 表无 UPDATE/DELETE("只追加"由权限保证);
 * ③ 有 INSERT 权限也插不进不平分录(deferred trigger 提交时拒)。
 * 种子走 ownerDsl(owner 绕过 grants 但绕不过 trigger)。
 */
class AppRoleGuardTest : AbstractIntegrationTest() {
    @Autowired
    lateinit var transactionTemplate: TransactionTemplate

    @Test
    fun `app role cannot update or delete ledger rows`() {
        val entryId = seedBalancedTxn()
        assertThatThrownBy {
            dsl
                .update(LEDGER_ENTRY)
                .set(LEDGER_ENTRY.AMOUNT, 0L)
                .where(LEDGER_ENTRY.ID.eq(entryId))
                .execute()
        }.hasStackTraceContaining("permission denied for table ledger_entry")
        assertThatThrownBy {
            dsl.deleteFrom(LEDGER_ENTRY).where(LEDGER_ENTRY.ID.eq(entryId)).execute()
        }.hasStackTraceContaining("permission denied for table ledger_entry")
        assertThatThrownBy {
            dsl.update(LEDGER_TXN).set(LEDGER_TXN.MEMO, "tampered").execute()
        }.hasStackTraceContaining("permission denied for table ledger_txn")
        assertThatThrownBy {
            dsl.deleteFrom(LEDGER_TXN).execute()
        }.hasStackTraceContaining("permission denied for table ledger_txn")
    }

    @Test
    fun `unbalanced insert is rejected at commit even though insert is granted`() {
        val account = seedAccount(OWNER_902)
        assertThatThrownBy {
            transactionTemplate.execute {
                val txnId =
                    dsl
                        .insertInto(LEDGER_TXN)
                        .set(LEDGER_TXN.BIZ_TYPE, PROBE_BIZ)
                        .set(LEDGER_TXN.BIZ_NO, "PROBE-UNBALANCED")
                        .returningResult(LEDGER_TXN.ID)
                        .fetchOne()!!
                        .value1()!!
                dsl
                    .insertInto(LEDGER_ENTRY, LEDGER_ENTRY.TXN_ID, LEDGER_ENTRY.ACCOUNT_ID, LEDGER_ENTRY.AMOUNT)
                    .values(txnId, account, 5L)
                    .execute() // 单行不平:deferred trigger 在提交时拒
            }
        }.hasStackTraceContaining("invalid")
        assertThat(dsl.fetchCount(LEDGER_ENTRY)).isEqualTo(0)
    }

    private fun seedAccount(ownerId: Long): Long =
        ownerDsl
            .insertInto(ACCOUNT, ACCOUNT.OWNER_KIND, ACCOUNT.OWNER_ID, ACCOUNT.KIND)
            .values(OWNER_CUSTOMER, ownerId, AccountKind.AVAILABLE)
            .returningResult(ACCOUNT.ID)
            .fetchOne()!!
            .value1()!!

    private fun seedBalancedTxn(): Long {
        val account = seedAccount(OWNER_901)
        val txnId =
            ownerDsl
                .insertInto(LEDGER_TXN)
                .set(LEDGER_TXN.BIZ_TYPE, PROBE_BIZ)
                .set(LEDGER_TXN.BIZ_NO, "PROBE-SEED")
                .returningResult(LEDGER_TXN.ID)
                .fetchOne()!!
                .value1()!!
        return ownerDsl
            .insertInto(LEDGER_ENTRY, LEDGER_ENTRY.TXN_ID, LEDGER_ENTRY.ACCOUNT_ID, LEDGER_ENTRY.AMOUNT)
            .values(txnId, account, 100L)
            .values(txnId, account, -100L) // 同账户两行,Σ=0,trigger 放行
            .returningResult(LEDGER_ENTRY.ID)
            .fetch()
            .first()
            .value1()!!
    }

    companion object {
        private const val PROBE_BIZ: Short = 99
        private const val OWNER_901 = 901L
        private const val OWNER_902 = 902L
    }
}
