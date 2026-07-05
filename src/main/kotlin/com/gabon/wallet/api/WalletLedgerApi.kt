package com.gabon.wallet.api

/**
 * wallet 对外记账口(spec §3.1):幂等键与业务来源焊死在签名,分录组装留在钱核内部。
 *
 * 通用契约:
 * - 返回 true=本次入账,false=同幂等键已入账(幂等短路,含异金额重放);
 * - 扣减侧余额不足抛 ProblemException(INSUFFICIENT_BALANCE),事务回滚不留任何账务痕迹;
 * - diamonds 必须为正(跨上下文契约 require fail-fast;端点输入由调用方 DTO 层先校验成 400);
 * - settle 与 release 对同一笔提现的互斥不由钱核保证,由 withdraw 状态机 CAS 单一终态保证。
 *
 * 其余写方法(freeze/settle/release/grant)随批 1 Task 4 与实现一起补齐。
 */
interface WalletLedgerApi {
    /** 充值入账:available +N / payment_clearing −N。幂等键 (RECHARGE, orderNo)。 */
    fun creditRecharge(
        orderNo: String,
        customerId: Long,
        diamonds: Long,
    ): Boolean
}
