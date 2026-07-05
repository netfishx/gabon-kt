package com.gabon.recharge.internal

import com.gabon.jooq.tables.references.RECHARGE_PACKAGE
import org.jooq.DSLContext
import org.springframework.stereotype.Repository

/** 上架态(V3 DDL check (0,1))。 */
private const val PACKAGE_ACTIVE: Short = 1

/** 充值档位仓储(spec §4.1):表归属 recharge.internal(规则 6)。档位录入是运营侧 SQL,本批无管理端点。 */
@Repository
class RechargePackageRepository(
    private val dsl: DSLContext,
) {
    data class PackageRow(
        val id: Long,
        val diamonds: Long,
        val priceCents: Long,
        val currency: String,
    )

    /** 上架档位,按价格升序(spec §2.2:不设排序列)。 */
    fun listActive(): List<PackageRow> =
        dsl
            .select(RECHARGE_PACKAGE.ID, RECHARGE_PACKAGE.DIAMONDS, RECHARGE_PACKAGE.PRICE_CENTS, RECHARGE_PACKAGE.CURRENCY)
            .from(RECHARGE_PACKAGE)
            .where(RECHARGE_PACKAGE.STATUS.eq(PACKAGE_ACTIVE))
            .orderBy(RECHARGE_PACKAGE.PRICE_CENTS.asc())
            .fetch()
            .map { PackageRow(it.value1()!!, it.value2()!!, it.value3()!!, it.value4()!!) }

    /** 上架档位单查:下架/不存在返回 null(调用方转 400 validation)。 */
    fun findActive(id: Long): PackageRow? =
        dsl
            .select(RECHARGE_PACKAGE.ID, RECHARGE_PACKAGE.DIAMONDS, RECHARGE_PACKAGE.PRICE_CENTS, RECHARGE_PACKAGE.CURRENCY)
            .from(RECHARGE_PACKAGE)
            .where(RECHARGE_PACKAGE.ID.eq(id).and(RECHARGE_PACKAGE.STATUS.eq(PACKAGE_ACTIVE)))
            .fetchOne()
            ?.let { PackageRow(it.value1()!!, it.value2()!!, it.value3()!!, it.value4()!!) }
}
