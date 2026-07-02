package com.gabon.ledger

import com.gabon.jooq.tables.references.ACCOUNT
import com.gabon.jooq.tables.references.LEDGER_ENTRY
import com.gabon.jooq.tables.references.LEDGER_TXN
import org.jooq.DSLContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

const val OWNER_CUSTOMER: Short = 1
const val OWNER_PLATFORM: Short = 0

// 业务类型
const val BIZ_RECHARGE: Short = 1

/**
 * 钱核：纯阻塞 + @Transactional。禁协程（见架构文档 B5.1）。
 */
@Service
class LedgerService(
    private val dsl: DSLContext,
) {
    /** 幂等充值入账：txn 头冲突即短路 → 双分录 → 余额投影，全在一个事务。 */
    @Transactional
    fun creditRecharge(
        customerId: Long,
        diamonds: Long,
        orderNo: String,
    ): Boolean {
        val txnId =
            dsl
                .insertInto(LEDGER_TXN)
                .set(LEDGER_TXN.BIZ_TYPE, BIZ_RECHARGE)
                .set(LEDGER_TXN.BIZ_NO, orderNo)
                .onConflictDoNothing()
                .returningResult(LEDGER_TXN.ID)
                .fetchOne()
                ?.value1()
                ?: return false // 重复回调：幂等短路

        val avail = accountId(OWNER_CUSTOMER, customerId, AccountKind.AVAILABLE)
        val clearing = accountId(OWNER_PLATFORM, 0, AccountKind.PAYMENT_CLEARING)

        // 双分录：贷 available(+N)，借 payment_clearing(-N)，Σ=0（延迟约束触发器提交时校验）
        dsl
            .insertInto(LEDGER_ENTRY, LEDGER_ENTRY.TXN_ID, LEDGER_ENTRY.ACCOUNT_ID, LEDGER_ENTRY.AMOUNT)
            .values(txnId, avail, diamonds)
            .values(txnId, clearing, -diamonds)
            .execute()

        addBalance(avail, diamonds)
        addBalance(clearing, -diamonds)
        return true
    }

    fun balanceOf(customerId: Long): Long =
        dsl
            .select(ACCOUNT.BALANCE)
            .from(ACCOUNT)
            .where(
                ACCOUNT.OWNER_KIND
                    .eq(OWNER_CUSTOMER)
                    .and(ACCOUNT.OWNER_ID.eq(customerId))
                    .and(ACCOUNT.KIND.eq(AccountKind.AVAILABLE)),
            ).fetchOne()
            ?.value1() ?: 0L

    private fun addBalance(
        accountId: Long,
        delta: Long,
    ) {
        dsl
            .update(ACCOUNT)
            .set(ACCOUNT.BALANCE, ACCOUNT.BALANCE.plus(delta))
            .set(ACCOUNT.VERSION, ACCOUNT.VERSION.plus(1))
            .where(ACCOUNT.ID.eq(accountId))
            .execute()
    }

    /** 取或建账户，返回其 id。 */
    private fun accountId(
        ownerKind: Short,
        ownerId: Long,
        kind: AccountKind,
    ): Long {
        val inserted =
            dsl
                .insertInto(ACCOUNT, ACCOUNT.OWNER_KIND, ACCOUNT.OWNER_ID, ACCOUNT.KIND)
                .values(ownerKind, ownerId, kind)
                .onConflictDoNothing()
                .returningResult(ACCOUNT.ID)
                .fetchOne()
                ?.value1()
        if (inserted != null) return inserted
        return dsl
            .select(ACCOUNT.ID)
            .from(ACCOUNT)
            .where(
                ACCOUNT.OWNER_KIND
                    .eq(ownerKind)
                    .and(ACCOUNT.OWNER_ID.eq(ownerId))
                    .and(ACCOUNT.KIND.eq(kind)),
            ).fetchOne()!!
            .value1()!!
    }
}
