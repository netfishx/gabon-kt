package com.gabon.recharge.internal

import com.gabon.platform.security.UuidV7
import com.gabon.platform.web.ProblemException
import com.gabon.platform.web.ProblemType
import com.gabon.recharge.internal.channel.PaymentChannelRegistry
import com.gabon.recharge.internal.channel.PaymentOrderSnapshot
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
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

    companion object {
        const val PAGE_SIZE = 20
    }
}
