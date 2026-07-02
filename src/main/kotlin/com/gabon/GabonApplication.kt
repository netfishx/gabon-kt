package com.gabon

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class GabonApplication

fun main(args: Array<String>) {
    runApplication<GabonApplication>(*args)
}

/** 验收①：Kotlin data class 的构造器绑定 + nullable 语义 */
@ConfigurationProperties("wallet")
data class WalletProps(
    val exchangeRate: Long = 100,
    val currency: String = "CNY",
)
