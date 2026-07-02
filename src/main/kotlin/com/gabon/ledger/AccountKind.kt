package com.gabon.ledger

import org.jooq.impl.AbstractConverter

/** 验收②：真实 forced type —— account.kind(smallint) 强制映射为该枚举 */
enum class AccountKind(
    val code: Int,
) {
    AVAILABLE(1),
    FROZEN(2),
    PAYMENT_CLEARING(3),
    PAYOUT_CLEARING(4),
    PLATFORM_EQUITY(5),
    ;

    companion object {
        fun of(code: Short): AccountKind = entries.first { it.code == code.toInt() }
    }
}

/** jOOQ Converter：DB smallint <-> AccountKind，验收 forced type/Converter 落地 */
class AccountKindConverter : AbstractConverter<Short, AccountKind>(Short::class.javaObjectType, AccountKind::class.java) {
    override fun from(databaseObject: Short?): AccountKind? = databaseObject?.let { AccountKind.of(it) }

    override fun to(userObject: AccountKind?): Short? = userObject?.code?.toShort()
}
