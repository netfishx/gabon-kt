package com.gabon.wallet.api

/** wallet 对外余额读取口:跨上下文只经 api(B4;spec §3 AccountKind 定性)。 */
interface WalletBalanceApi {
    fun balanceOf(customerId: Long): Long
}
