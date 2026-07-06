package com.gabon.recharge.internal

import com.gabon.platform.outbox.InboxRepo
import com.gabon.platform.security.UuidV7
import com.gabon.platform.web.ProblemException
import com.gabon.platform.web.ProblemType
import com.gabon.recharge.internal.channel.PaymentCallback
import com.gabon.recharge.internal.channel.PaymentChannelRegistry
import com.gabon.recharge.internal.channel.PaymentOrderSnapshot
import com.gabon.wallet.api.WalletLedgerApi
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/** 下单结果:orderNo + 渠道支付凭据(透传前端)。 */
data class CreateOrderResult(
    val orderNo: String,
    val payload: Map<String, String>,
)

/**
 * 充值应用服务(spec §4)。createOrder 刻意无整体事务:本地建单与渠道网络调用分离
 * (网络调用不裹 DB 事务,与 spec §6.4 同则),单条 insert/update 自身原子。
 */
@Service
class RechargeService(
    private val packages: RechargePackageRepository,
    private val orders: RechargeOrderRepository,
    private val registry: PaymentChannelRegistry,
    private val clock: Clock,
    private val inbox: InboxRepo,
    private val ledger: WalletLedgerApi,
    private val meters: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun listPackages(): List<RechargePackageRepository.PackageRow> = packages.listActive()

    /**
     * 两步下单(spec §4.2):建单(CREATED,档位三列快照,防在途改价)→ 渠道下单 → 回填渠道号 + CAS PROCESSING。
     * 渠道失败:订单留 CREATED(对账可见),PAYMENT_CHANNEL_ERROR 502 冒出,用户重新下单。
     */
    fun createOrder(
        customerId: Long,
        packageId: Long,
        channelCode: Short,
    ): CreateOrderResult {
        val pkg =
            packages.findActive(packageId)
                ?: throw ProblemException(ProblemType.VALIDATION, "package unavailable: $packageId")
        val channel = registry.byCode(channelCode)
        val orderNo = "R-${UuidV7.generate(clock)}"
        orders.insert(orderNo, customerId, packageId, pkg.diamonds, pkg.priceCents, pkg.currency, channelCode)
        val instruction = channel.createPayment(PaymentOrderSnapshot(orderNo, pkg.priceCents, pkg.currency))
        if (orders.markProcessing(orderNo, instruction.channelOrderNo) == 0) {
            // 回调抢先到终态(渠道极快):终态优先不覆盖(spec §4.2);极罕见,留痕便于排查
            log.warn("order {} reached terminal state before markProcessing", orderNo)
        }
        return CreateOrderResult(orderNo, instruction.payload)
    }

    fun listOrders(
        customerId: Long,
        cursor: Long?,
    ): List<RechargeOrderRepository.OrderRow> = orders.page(customerId, cursor, PAGE_SIZE)

    /**
     * 回调处理(spec §4.3,顺序钉死):验签/解析(SPI 抛 401/400,不落 inbox)→ 渠道归属 → 金额(仅 Success)
     * → 渠道号回填/校验 → inbox 去重 → 宽 CAS → 入账。全体同一事务:失败整体回滚(含 inbox 行),
     * 渠道重试可完整重放。重复/错配/终态冲突一律正常返回(2xx ack,渠道停止重试)。
     */
    @Transactional
    fun handleCallback(
        channelCode: Short,
        rawBody: ByteArray,
        headers: Map<String, String>,
    ) {
        val channel = registry.byCode(channelCode)
        when (val cb = channel.verifyAndParse(rawBody, headers)) {
            is PaymentCallback.Ignored -> Unit // 中间态:ack,不落 inbox,不动状态(spec §5.2)
            is PaymentCallback.Success -> applySuccess(channelCode, cb)
            is PaymentCallback.Failure -> applyFailure(channelCode, cb)
        }
    }

    @Suppress("ReturnCount") // 校验链一步一 guard(spec §4.3 顺序钉死),折成单返回反而藏住检查顺序
    private fun applySuccess(
        channelCode: Short,
        cb: PaymentCallback.Success,
    ) {
        val order = orders.findByOrderNo(cb.orderNo) ?: return anomaly("unknown-order", "orderNo=${cb.orderNo}")
        // 渠道归属校验(安全关键):防"渠道 B 的合法签名结算渠道 A 的订单"(多渠道横向越权)
        if (order.channel != channelCode) {
            return anomaly("channel-mismatch", "orderNo=${cb.orderNo} pathChannel=$channelCode orderChannel=${order.channel}")
        }
        // 金额币种校验(安全关键,spec §4.3-1):防"真实小额支付回调错配到大额订单"入账
        if (cb.paidCents != order.priceCents || cb.currency != order.currency) {
            return anomaly(
                "amount-mismatch",
                "orderNo=${cb.orderNo} paid=${cb.paidCents}${cb.currency} expect=${order.priceCents}${order.currency}",
            )
        }
        if (!reconcileChannelOrderNo(order, cb.channelOrderNo)) {
            return anomaly("channel-no-mismatch", "orderNo=${cb.orderNo} got=${cb.channelOrderNo} local=${order.channelOrderNo}")
        }
        if (!inbox.tryRecord(sourceOf(channelCode), cb.externalId)) return // 重复回调短路 ack(spec §4.3-3)
        if (orders.casToTerminal(order.id, ORDER_SUCCESS) == 1) {
            ledger.creditRecharge(cb.orderNo, order.customerId, order.diamonds)
        } else {
            conflict("success callback on terminal order ${cb.orderNo}") // 不翻案(spec §4.3 定案)
        }
    }

    @Suppress("ReturnCount") // 同上:applySuccess 的镜像校验链
    private fun applyFailure(
        channelCode: Short,
        cb: PaymentCallback.Failure,
    ) {
        val order = orders.findByOrderNo(cb.orderNo) ?: return anomaly("unknown-order", "orderNo=${cb.orderNo}")
        if (order.channel != channelCode) {
            return anomaly("channel-mismatch", "orderNo=${cb.orderNo} pathChannel=$channelCode orderChannel=${order.channel}")
        }
        // Failure 无金额字段;渠道号带了就必须过同一校验(spec §4.3-2)
        if (!reconcileChannelOrderNo(order, cb.channelOrderNo)) {
            return anomaly("channel-no-mismatch", "orderNo=${cb.orderNo} got=${cb.channelOrderNo} local=${order.channelOrderNo}")
        }
        if (!inbox.tryRecord(sourceOf(channelCode), cb.externalId)) return
        if (orders.casToTerminal(order.id, ORDER_FAILED) == 0) {
            conflict("failure callback on terminal order ${cb.orderNo}")
        }
    }

    /** 渠道号回填/校验(spec §4.3-2):单条条件 UPDATE 原子完成"为空则写入/已有则须相同",0 行 = 错配。 */
    private fun reconcileChannelOrderNo(
        order: RechargeOrderRepository.CallbackRow,
        channelOrderNo: String?,
    ): Boolean {
        if (channelOrderNo == null) return true
        return orders.reconcileChannelOrderNo(order.id, channelOrderNo) == 1
    }

    /** 错配/查无此单:签名已过说明源头在渠道侧,ERROR + 指标 + 正常返回(2xx,重试不会变对,spec §4.3-1)。 */
    private fun anomaly(
        kind: String,
        detail: String,
    ) {
        log.error("recharge callback anomaly kind={} {}", kind, detail)
        meters.counter("recharge.callback.anomaly", "kind", kind).increment()
    }

    /** 终态冲突:WARN + 指标 + ack,不静默不翻案(对账差错表归子项目 7,spec §4.3)。 */
    private fun conflict(detail: String) {
        log.warn("recharge callback terminal conflict {}", detail)
        meters.counter("recharge.callback.conflict").increment()
    }

    private fun sourceOf(channelCode: Short): Short = (SOURCE_BASE + channelCode).toShort()

    companion object {
        const val PAGE_SIZE = 20

        /** inbox source 命名空间基数(spec §4.3-3):recharge 1000,withdraw 2000(批 3)。 */
        const val SOURCE_BASE = 1_000
    }
}
