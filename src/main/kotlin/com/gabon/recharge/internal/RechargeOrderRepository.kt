package com.gabon.recharge.internal

import com.gabon.jooq.tables.references.RECHARGE_ORDER
import org.jooq.DSLContext
import org.springframework.stereotype.Repository

// 状态机(V3 DDL check (1..5),spec C2.4):CANCELLED 超时关单二期,状态先留
const val ORDER_CREATED: Short = 1
const val ORDER_PROCESSING: Short = 2
const val ORDER_SUCCESS: Short = 3
const val ORDER_FAILED: Short = 4
const val ORDER_CANCELLED: Short = 5

/** 充值订单仓储(spec §4):表归属 recharge.internal(规则 6);状态迁移一律 CAS,禁读改写。 */
@Repository
class RechargeOrderRepository(
    private val dsl: DSLContext,
) {
    data class OrderRow(
        val id: Long,
        val orderNo: String,
        val diamonds: Long,
        val priceCents: Long,
        val currency: String,
        val status: Short,
    )

    /** 7 入参均为订单自然列,拆 DTO 徒增映射(同 RefreshTokenRepository 先例)。 */
    @Suppress("LongParameterList")
    fun insert(
        orderNo: String,
        customerId: Long,
        packageId: Long,
        diamonds: Long,
        priceCents: Long,
        currency: String,
        channel: Short,
    ): Long =
        dsl
            .insertInto(RECHARGE_ORDER)
            .set(RECHARGE_ORDER.ORDER_NO, orderNo)
            .set(RECHARGE_ORDER.CUSTOMER_ID, customerId)
            .set(RECHARGE_ORDER.PACKAGE_ID, packageId)
            .set(RECHARGE_ORDER.DIAMONDS, diamonds)
            .set(RECHARGE_ORDER.PRICE_CENTS, priceCents)
            .set(RECHARGE_ORDER.CURRENCY, currency)
            .set(RECHARGE_ORDER.CHANNEL, channel)
            .returningResult(RECHARGE_ORDER.ID)
            .fetchOne()!!
            .value1()!!

    /** CAS CREATED→PROCESSING + 回填渠道号;0 行 = 回调抢先到终态(渠道极快),终态优先不覆盖(spec §4.2)。 */
    fun markProcessing(
        orderNo: String,
        channelOrderNo: String,
    ): Int =
        dsl
            .update(RECHARGE_ORDER)
            .set(RECHARGE_ORDER.STATUS, ORDER_PROCESSING)
            .set(RECHARGE_ORDER.CHANNEL_ORDER_NO, channelOrderNo)
            .where(RECHARGE_ORDER.ORDER_NO.eq(orderNo).and(RECHARGE_ORDER.STATUS.eq(ORDER_CREATED)))
            .execute()

    /** keyset 列表(spec §4.1):cursor = last seen id;走 (customer_id, id) 索引。 */
    fun page(
        customerId: Long,
        cursor: Long?,
        limit: Int,
    ): List<OrderRow> {
        val base = RECHARGE_ORDER.CUSTOMER_ID.eq(customerId)
        val cond = if (cursor != null) base.and(RECHARGE_ORDER.ID.lt(cursor)) else base
        return dsl
            .select(
                RECHARGE_ORDER.ID,
                RECHARGE_ORDER.ORDER_NO,
                RECHARGE_ORDER.DIAMONDS,
                RECHARGE_ORDER.PRICE_CENTS,
                RECHARGE_ORDER.CURRENCY,
                RECHARGE_ORDER.STATUS,
            ).from(RECHARGE_ORDER)
            .where(cond)
            .orderBy(RECHARGE_ORDER.ID.desc())
            .limit(limit)
            .fetch()
            .map { OrderRow(it.value1()!!, it.value2()!!, it.value3()!!, it.value4()!!, it.value5()!!, it.value6()!!) }
    }

    data class CallbackRow(
        val id: Long,
        val customerId: Long,
        val diamonds: Long,
        val priceCents: Long,
        val currency: String,
        val channel: Short,
        val channelOrderNo: String?,
    )

    fun findByOrderNo(orderNo: String): CallbackRow? =
        dsl
            .select(
                RECHARGE_ORDER.ID,
                RECHARGE_ORDER.CUSTOMER_ID,
                RECHARGE_ORDER.DIAMONDS,
                RECHARGE_ORDER.PRICE_CENTS,
                RECHARGE_ORDER.CURRENCY,
                RECHARGE_ORDER.CHANNEL,
                RECHARGE_ORDER.CHANNEL_ORDER_NO,
            ).from(RECHARGE_ORDER)
            .where(RECHARGE_ORDER.ORDER_NO.eq(orderNo))
            .fetchOne()
            ?.let { CallbackRow(it.value1()!!, it.value2()!!, it.value3()!!, it.value4()!!, it.value5()!!, it.value6()!!, it.value7()) }

    /**
     * 渠道号回填/校验一体(spec §4.3-2):为空则写入、已有则须相同——单条条件 UPDATE 原子完成,
     * 无"读 null → 回填 0 行仍放行"的并发窗口;1 行 = 回填或同值重写(无害),0 行 = 错配。
     */
    fun reconcileChannelOrderNo(
        id: Long,
        channelOrderNo: String,
    ): Int =
        dsl
            .update(RECHARGE_ORDER)
            .set(RECHARGE_ORDER.CHANNEL_ORDER_NO, channelOrderNo)
            .where(
                RECHARGE_ORDER.ID
                    .eq(id)
                    .and(RECHARGE_ORDER.CHANNEL_ORDER_NO.isNull.or(RECHARGE_ORDER.CHANNEL_ORDER_NO.eq(channelOrderNo))),
            ).execute()

    /** 宽 CAS(spec §4.3-4):CREATED|PROCESSING → 终态;0 行 = 已在终态(冲突由调用方 WARN + ack)。 */
    fun casToTerminal(
        id: Long,
        target: Short,
    ): Int =
        dsl
            .update(RECHARGE_ORDER)
            .set(RECHARGE_ORDER.STATUS, target)
            .where(RECHARGE_ORDER.ID.eq(id).and(RECHARGE_ORDER.STATUS.`in`(ORDER_CREATED, ORDER_PROCESSING)))
            .execute()
}
