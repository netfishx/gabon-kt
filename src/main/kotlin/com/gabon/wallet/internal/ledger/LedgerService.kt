package com.gabon.wallet.internal.ledger

import com.gabon.jooq.tables.references.ACCOUNT
import com.gabon.jooq.tables.references.LEDGER_ENTRY
import com.gabon.jooq.tables.references.LEDGER_TXN
import com.gabon.platform.web.ProblemException
import com.gabon.platform.web.ProblemType
import com.gabon.wallet.api.WalletBalanceApi
import com.gabon.wallet.api.WalletLedgerApi
import org.jooq.DSLContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

const val OWNER_CUSTOMER: Short = 1
const val OWNER_PLATFORM: Short = 0

// 业务类型 = 幂等键 biz_type(spec §3.1);已入库的值不可改
const val BIZ_RECHARGE: Short = 1
const val BIZ_WITHDRAW_FREEZE: Short = 2
const val BIZ_WITHDRAW_SETTLE: Short = 3
const val BIZ_WITHDRAW_RELEASE: Short = 4
const val BIZ_REWARD: Short = 5

/**
 * 钱核:纯阻塞 + @Transactional,禁协程(B5.1)。写入统一收口(三层不变量①,spec §2.1):
 * 同构骨架 = 幂等门(txn 头冲突短路)→ 开户/守卫/投影 → postEntries 唯一分录入口。
 */
@Service
@Suppress("TooManyFunctions") // 五写方法 + 同构骨架私有件均需留在同一收口类内(ModuleBoundaryTest 锁定)
class LedgerService(
    private val dsl: DSLContext,
) : WalletBalanceApi,
    WalletLedgerApi {
    @Transactional
    override fun creditRecharge(
        orderNo: String,
        customerId: Long,
        diamonds: Long,
    ): Boolean =
        post(BIZ_RECHARGE, orderNo, diamonds) {
            val avail = accountId(OWNER_CUSTOMER, customerId, AccountKind.AVAILABLE)
            val clearing = accountId(OWNER_PLATFORM, 0, AccountKind.PAYMENT_CLEARING)
            bump(avail, diamonds)
            bump(clearing, -diamonds)
            listOf(avail to diamonds, clearing to -diamonds)
        }

    @Transactional
    override fun freezeForWithdraw(
        withdrawNo: String,
        customerId: Long,
        diamonds: Long,
    ): Boolean =
        post(BIZ_WITHDRAW_FREEZE, withdrawNo, diamonds) {
            val avail = accountId(OWNER_CUSTOMER, customerId, AccountKind.AVAILABLE)
            val frozen = accountId(OWNER_CUSTOMER, customerId, AccountKind.FROZEN)
            guardedDebit(avail, diamonds, "freeze $withdrawNo")
            bump(frozen, diamonds)
            listOf(avail to -diamonds, frozen to diamonds)
        }

    @Transactional
    override fun settleWithdraw(
        withdrawNo: String,
        customerId: Long,
        diamonds: Long,
    ): Boolean =
        post(BIZ_WITHDRAW_SETTLE, withdrawNo, diamonds) {
            val frozen = accountId(OWNER_CUSTOMER, customerId, AccountKind.FROZEN)
            val clearing = accountId(OWNER_PLATFORM, 0, AccountKind.PAYOUT_CLEARING)
            guardedDebit(frozen, diamonds, "settle $withdrawNo")
            bump(clearing, diamonds)
            listOf(frozen to -diamonds, clearing to diamonds)
        }

    @Transactional
    override fun releaseFrozen(
        withdrawNo: String,
        customerId: Long,
        diamonds: Long,
    ): Boolean =
        post(BIZ_WITHDRAW_RELEASE, withdrawNo, diamonds) {
            val frozen = accountId(OWNER_CUSTOMER, customerId, AccountKind.FROZEN)
            val avail = accountId(OWNER_CUSTOMER, customerId, AccountKind.AVAILABLE)
            guardedDebit(frozen, diamonds, "release $withdrawNo")
            bump(avail, diamonds)
            listOf(frozen to -diamonds, avail to diamonds)
        }

    @Transactional
    override fun grantReward(
        rewardNo: String,
        customerId: Long,
        diamonds: Long,
    ): Boolean =
        post(BIZ_REWARD, rewardNo, diamonds) {
            val avail = accountId(OWNER_CUSTOMER, customerId, AccountKind.AVAILABLE)
            val equity = accountId(OWNER_PLATFORM, 0, AccountKind.PLATFORM_EQUITY)
            bump(avail, diamonds)
            bump(equity, -diamonds)
            listOf(avail to diamonds, equity to -diamonds)
        }

    override fun balanceOf(customerId: Long): Long = projected(customerId, AccountKind.AVAILABLE)

    override fun frozenOf(customerId: Long): Long = projected(customerId, AccountKind.FROZEN)

    /**
     * 同构骨架(spec §3.2):require 正数 → 幂等门最先(重复请求不碰守卫)→ moves(开户/守卫/投影,
     * 返回分录清单)→ postEntries。守卫 0 行抛出 → 整个事务回滚,txn 头一并消失,不留垃圾头。
     */
    private fun post(
        bizType: Short,
        bizNo: String,
        diamonds: Long,
        moves: () -> List<Pair<Long, Long>>,
    ): Boolean {
        require(diamonds > 0) { "diamonds must be positive: $bizNo -> $diamonds" }
        val txnId =
            dsl
                .insertInto(LEDGER_TXN)
                .set(LEDGER_TXN.BIZ_TYPE, bizType)
                .set(LEDGER_TXN.BIZ_NO, bizNo)
                .onConflictDoNothing()
                .returningResult(LEDGER_TXN.ID)
                .fetchOne()
                ?.value1()
                ?: return false // 幂等短路:同 (biz_type, biz_no) 已入账
        postEntries(txnId, moves())
        return true
    }

    /** 唯一写 ledger_entry 的生产入口(spec §3.2-4):写前断言 ≥2 行且 Σ=0(三层不变量①)。 */
    private fun postEntries(
        txnId: Long,
        entries: List<Pair<Long, Long>>,
    ) {
        require(entries.size >= 2 && entries.sumOf { it.second } == 0L) {
            "unbalanced ledger txn $txnId: $entries"
        }
        entries
            .fold(
                dsl.insertInto(LEDGER_ENTRY, LEDGER_ENTRY.TXN_ID, LEDGER_ENTRY.ACCOUNT_ID, LEDGER_ENTRY.AMOUNT),
            ) { insert, (accountId, amount) -> insert.values(txnId, accountId, amount) }
            .execute()
    }

    /** 守卫扣减:0 行 = 余额不足 → 409 problem(spec §3.1);仅用于 customer 非负账户。 */
    private fun guardedDebit(
        accountId: Long,
        amount: Long,
        context: String,
    ) {
        val rows =
            dsl
                .update(ACCOUNT)
                .set(ACCOUNT.BALANCE, ACCOUNT.BALANCE.minus(amount))
                .set(ACCOUNT.VERSION, ACCOUNT.VERSION.plus(1))
                .where(ACCOUNT.ID.eq(accountId).and(ACCOUNT.BALANCE.ge(amount)))
                .execute()
        if (rows != 1) throw ProblemException(ProblemType.INSUFFICIENT_BALANCE, "$context: account=$accountId amount=$amount")
    }

    private fun bump(
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

    private fun projected(
        customerId: Long,
        kind: AccountKind,
    ): Long =
        dsl
            .select(ACCOUNT.BALANCE)
            .from(ACCOUNT)
            .where(
                ACCOUNT.OWNER_KIND
                    .eq(OWNER_CUSTOMER)
                    .and(ACCOUNT.OWNER_ID.eq(customerId))
                    .and(ACCOUNT.KIND.eq(kind)),
            ).fetchOne()
            ?.value1() ?: 0L

    /** 取或建账户,返回其 id。 */
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
