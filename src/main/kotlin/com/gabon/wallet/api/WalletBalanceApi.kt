package com.gabon.wallet.api

/** wallet 对外余额读取口:跨上下文只经 api(B4;spec §3 AccountKind 定性)。 */
interface WalletBalanceApi {
    /**
     * 读取客户可用余额。
     *
     * 契约:
     * - 账户不存在时返回 0(不抛异常,无 null)——消费方无需区分"无账户"与"零余额"。
     * - 单位为钻石整数,与 `account.balance` 列语义一致。
     */
    fun balanceOf(customerId: Long): Long
}
