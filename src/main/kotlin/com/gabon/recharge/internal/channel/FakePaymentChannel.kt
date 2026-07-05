package com.gabon.recharge.internal.channel

import com.gabon.platform.web.ProblemException
import com.gabon.platform.web.ProblemType
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.security.MessageDigest
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 假渠道配置(spec §7.3):secret 供 HMAC 验签;第一期收付两方向可共用一个 secret,
 * 真实渠道按 channel 独立配置。enabled=false 时不建 bean,secret 缺省空串不炸绑定。
 */
@ConfigurationProperties("gabon.channel.fake")
data class FakeChannelProps(
    val enabled: Boolean = false,
    val secret: String = "",
)

/**
 * 测试/联调用假收款渠道(spec §5.3):HMAC-SHA256 对 raw body 验签(真实可测,非空壳放行)。
 * 回调体 JSON:{externalId, orderNo, status, channelOrderNo?, paidCents?, currency?, reason?};
 * status=SUCCESS/FAILED 映射终态,其余一律 Ignored。readTree 解析,不依赖 Kotlin 数据绑定。
 */
@Component
@ConditionalOnProperty("gabon.channel.fake.enabled", havingValue = "true")
class FakePaymentChannel(
    private val props: FakeChannelProps,
    private val objectMapper: ObjectMapper,
) : PaymentChannel {
    init {
        require(props.secret.isNotBlank()) { "gabon.channel.fake.secret must be set when fake channel is enabled" }
    }

    override val code: Short = CODE

    override fun createPayment(order: PaymentOrderSnapshot): PaymentInstruction =
        PaymentInstruction(
            channelOrderNo = "FAKE-${order.orderNo}",
            payload = mapOf("payUrl" to "https://fake.pay/${order.orderNo}"),
        )

    override fun verifyAndParse(
        rawBody: ByteArray,
        headers: Map<String, String>,
    ): PaymentCallback {
        verifySignature(rawBody, headers)
        val node =
            try {
                objectMapper.readTree(rawBody)
            } catch (e: JacksonException) {
                throw ProblemException(ProblemType.VALIDATION, "fake callback unparseable: $e")
            }
        val externalId = node.requiredText("externalId")
        return when (node.path("status").asString("")) {
            "SUCCESS" ->
                PaymentCallback.Success(
                    externalId = externalId,
                    orderNo = node.requiredText("orderNo"),
                    channelOrderNo = node.requiredText("channelOrderNo"),
                    paidCents = node.requiredLong("paidCents"),
                    currency = node.requiredText("currency"),
                )
            "FAILED" ->
                PaymentCallback.Failure(
                    externalId = externalId,
                    orderNo = node.requiredText("orderNo"),
                    // 空白视同缺失:V3 对 channel_order_no 有非空白 check,空串流入会在回填时炸 DB 约束成 500
                    channelOrderNo =
                        node
                            .path("channelOrderNo")
                            .takeIf { it.isString }
                            ?.asString()
                            ?.takeIf { s -> s.isNotBlank() },
                    reason = node.path("reason").asString("failed").takeIf { s -> s.isNotBlank() } ?: "failed",
                )
            else -> PaymentCallback.Ignored(externalId) // pending/unknown → ack,不落 inbox,不动状态(spec §5.2)
        }
    }

    /** 缺失/错误签名一律 UNAUTHENTICATED(401),不落 inbox。 */
    private fun verifySignature(
        rawBody: ByteArray,
        headers: Map<String, String>,
    ) {
        val given =
            headers[SIGNATURE_HEADER]
                ?: throw ProblemException(ProblemType.UNAUTHENTICATED, "fake callback missing signature header")
        val expected = hmacHex(rawBody)
        if (!MessageDigest.isEqual(expected.toByteArray(), given.toByteArray())) {
            throw ProblemException(ProblemType.UNAUTHENTICATED, "fake callback bad signature")
        }
    }

    private fun hmacHex(body: ByteArray): String =
        HexFormat.of().formatHex(
            Mac.getInstance(HMAC_ALG).apply { init(SecretKeySpec(props.secret.toByteArray(), HMAC_ALG)) }.doFinal(body),
        )

    /** 非空白必填文本:V3 对 order_no/channel_order_no 有非空白 check,空串放进流程会炸 DB 约束成 500。 */
    private fun JsonNode.requiredText(field: String): String =
        path(field).takeIf { it.isString }?.asString()?.takeIf { s -> s.isNotBlank() }
            ?: throw ProblemException(ProblemType.VALIDATION, "fake callback missing text field: $field")

    private fun JsonNode.requiredLong(field: String): Long =
        path(field).takeIf { it.isNumber }?.asLong()
            ?: throw ProblemException(ProblemType.VALIDATION, "fake callback missing numeric field: $field")

    companion object {
        const val CODE: Short = 1
        const val SIGNATURE_HEADER = "x-fake-signature"
        private const val HMAC_ALG = "HmacSHA256"
    }
}
