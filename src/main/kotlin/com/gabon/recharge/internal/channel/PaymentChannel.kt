package com.gabon.recharge.internal.channel

/** 下单入参快照:渠道只拿需要的字段,不给实体(spec §5.2)。 */
data class PaymentOrderSnapshot(
    val orderNo: String,
    val priceCents: Long,
    val currency: String,
)

/** 渠道下单结果:渠道单号 + 支付凭据(透传前端,形态渠道各异)。 */
data class PaymentInstruction(
    val channelOrderNo: String,
    val payload: Map<String, String>,
)

/**
 * 回调解析结果(spec §5.2):Failure 仅渠道**明确终态失败**;pending/unknown/查询失败一律 Ignored
 * (ack 但不落 inbox——中间态与终态可能共用 externalId,落了会把真终态挡在门外)。
 */
sealed interface PaymentCallback {
    data class Success(
        val externalId: String,
        val orderNo: String,
        val channelOrderNo: String,
        val paidCents: Long,
        val currency: String,
    ) : PaymentCallback

    data class Failure(
        val externalId: String,
        val orderNo: String,
        val channelOrderNo: String?,
        val reason: String,
    ) : PaymentCallback

    data class Ignored(
        val externalId: String,
    ) : PaymentCallback
}

/**
 * 收款渠道 SPI(spec §5.2)。域内 internal,不进 api、不跨上下文;真实渠道适配随子项目 7 替换。
 * 渠道失败直接抛 ProblemException(PAYMENT_CHANNEL_ERROR)——spec 的 ChannelException 语义,不另设异常类型。
 */
interface PaymentChannel {
    val code: Short

    /** 渠道下单;网络/渠道侧失败抛 ProblemException(PAYMENT_CHANNEL_ERROR) → 502。 */
    fun createPayment(order: PaymentOrderSnapshot): PaymentInstruction

    /**
     * 验签 + 解析:基于 raw body + headers(key 已小写归一),先验后解析(反序列化再验签会被
     * 字段顺序/空白/编码破坏);验签失败抛 UNAUTHENTICATED(401),解析失败抛 VALIDATION(400)——均不落 inbox。
     */
    fun verifyAndParse(
        rawBody: ByteArray,
        headers: Map<String, String>,
    ): PaymentCallback
}
