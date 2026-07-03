package com.gabon.wallet.internal

import org.springframework.boot.context.properties.ConfigurationProperties

/** 验收①:Kotlin data class 的构造器绑定 + nullable 语义(自根包归位,spec 批次 2) */
@ConfigurationProperties("wallet")
data class WalletProps(
    val exchangeRate: Long = 100,
    val currency: String = "CNY",
)
