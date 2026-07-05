package com.gabon.wallet.api

/**
 * wallet 对外记账口(spec §3.1):幂等键与业务来源焊死在签名,分录组装留在钱核内部。
 *
 * 通用契约:
 * - 返回 true=本次入账,false=同幂等键已入账(幂等短路,含异金额重放);
 * - 扣减侧余额不足抛 ProblemException(INSUFFICIENT_BALANCE),事务回滚不留任何账务痕迹;
 * - diamonds 必须为正(跨上下文契约 require fail-fast;端点输入由调用方 DTO 层先校验成 400);
 * - settle 与 release 对同一笔提现的互斥不由钱核保证,由 withdraw 状态机 CAS 单一终态保证。
 */
interface WalletLedgerApi {
    /** 充值入账:available +N / payment_clearing −N。幂等键 (RECHARGE, orderNo)。 */
    fun creditRecharge(
        orderNo: String,
        customerId: Long,
        diamonds: Long,
    ): Boolean

    /** 提现冻结:available −N(守卫)/ frozen +N。幂等键 (WITHDRAW_FREEZE, withdrawNo)。 */
    fun freezeForWithdraw(
        withdrawNo: String,
        customerId: Long,
        diamonds: Long,
    ): Boolean

    /** 提现结算:frozen −N(守卫)/ payout_clearing +N。幂等键 (WITHDRAW_SETTLE, withdrawNo)。 */
    fun settleWithdraw(
        withdrawNo: String,
        customerId: Long,
        diamonds: Long,
    ): Boolean

    /** 冻结解冻:frozen −N(守卫)/ available +N。幂等键 (WITHDRAW_RELEASE, withdrawNo)。 */
    fun releaseFrozen(
        withdrawNo: String,
        customerId: Long,
        diamonds: Long,
    ): Boolean

    /** 发奖:available +N / platform_equity −N(平台侧可为负)。幂等键 (REWARD, rewardNo)。 */
    fun grantReward(
        rewardNo: String,
        customerId: Long,
        diamonds: Long,
    ): Boolean
}
