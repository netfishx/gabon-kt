package com.gabon

import com.gabon.platform.web.ProblemException
import com.gabon.platform.web.ProblemType
import com.gabon.recharge.internal.channel.FakeChannelProps
import com.gabon.recharge.internal.channel.FakePaymentChannel
import com.gabon.recharge.internal.channel.PaymentCallback
import com.gabon.recharge.internal.channel.PaymentChannel
import com.gabon.recharge.internal.channel.PaymentChannelRegistry
import com.gabon.recharge.internal.channel.PaymentInstruction
import com.gabon.recharge.internal.channel.PaymentOrderSnapshot
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** SPI 契约单测(spec §5.2/§5.3):raw body 验签、三态映射、registry fail-fast。无容器。 */
class FakePaymentChannelTest {
    private val channel = FakePaymentChannel(FakeChannelProps(enabled = true, secret = SECRET), ObjectMapper())

    @Test
    fun `signed success callback parses with amount and channel number`() {
        val body = """{"externalId":"E1","orderNo":"R-1","status":"SUCCESS","channelOrderNo":"F-1","paidCents":1000,"currency":"CNY"}"""
        val cb = channel.verifyAndParse(body.toByteArray(), mapOf("x-fake-signature" to sign(body)))
        assertThat(cb).isEqualTo(PaymentCallback.Success("E1", "R-1", "F-1", 1_000, "CNY"))
    }

    @Test
    fun `failed status maps to failure and pending maps to ignored`() {
        val failed = """{"externalId":"E2","orderNo":"R-2","status":"FAILED","channelOrderNo":"F-2","reason":"card declined"}"""
        assertThat(channel.verifyAndParse(failed.toByteArray(), mapOf("x-fake-signature" to sign(failed))))
            .isEqualTo(PaymentCallback.Failure("E2", "R-2", "F-2", "card declined"))
        val pending = """{"externalId":"E3","orderNo":"R-3","status":"PENDING"}"""
        assertThat(channel.verifyAndParse(pending.toByteArray(), mapOf("x-fake-signature" to sign(pending))))
            .isEqualTo(PaymentCallback.Ignored("E3")) // pending/unknown → Ignored,不落 inbox(spec §5.2)
    }

    @Test
    fun `bad or missing signature is unauthenticated`() {
        val body = """{"externalId":"E4","orderNo":"R-4","status":"SUCCESS"}"""
        assertThatThrownBy { channel.verifyAndParse(body.toByteArray(), mapOf("x-fake-signature" to "deadbeef")) }
            .isInstanceOfSatisfying(ProblemException::class.java) { assertThat(it.type).isEqualTo(ProblemType.UNAUTHENTICATED) }
        assertThatThrownBy { channel.verifyAndParse(body.toByteArray(), emptyMap()) }
            .isInstanceOfSatisfying(ProblemException::class.java) { assertThat(it.type).isEqualTo(ProblemType.UNAUTHENTICATED) }
    }

    @Test
    fun `unparseable body and malformed success are validation errors`() {
        val junk = "not-json"
        assertThatThrownBy { channel.verifyAndParse(junk.toByteArray(), mapOf("x-fake-signature" to sign(junk))) }
            .isInstanceOfSatisfying(ProblemException::class.java) { assertThat(it.type).isEqualTo(ProblemType.VALIDATION) }
        val noAmount = """{"externalId":"E5","orderNo":"R-5","status":"SUCCESS","channelOrderNo":"F-5"}"""
        assertThatThrownBy { channel.verifyAndParse(noAmount.toByteArray(), mapOf("x-fake-signature" to sign(noAmount))) }
            .isInstanceOfSatisfying(ProblemException::class.java) { assertThat(it.type).isEqualTo(ProblemType.VALIDATION) }
        // 空白渠道号 = 缺失(V3 非空白 check,放行会炸 DB 约束成 500)
        val blankNo = """{"externalId":"E6","orderNo":"R-6","status":"SUCCESS","channelOrderNo":"  ","paidCents":1,"currency":"CNY"}"""
        assertThatThrownBy { channel.verifyAndParse(blankNo.toByteArray(), mapOf("x-fake-signature" to sign(blankNo))) }
            .isInstanceOfSatisfying(ProblemException::class.java) { assertThat(it.type).isEqualTo(ProblemType.VALIDATION) }
    }

    @Test
    fun `registry rejects duplicate codes and unknown lookups`() {
        val a = stubChannel(code = 7)
        assertThatThrownBy { PaymentChannelRegistry(listOf(a, stubChannel(code = 7))) }
            .isInstanceOf(IllegalStateException::class.java) // 启动期 fail-fast:后注册静默覆盖是危险态(spec §5.1)
        val registry = PaymentChannelRegistry(listOf(a))
        assertThat(registry.byCode(7)).isSameAs(a)
        assertThatThrownBy { registry.byCode(8) }
            .isInstanceOfSatisfying(ProblemException::class.java) { assertThat(it.type).isEqualTo(ProblemType.VALIDATION) }
    }

    private fun stubChannel(code: Short): PaymentChannel =
        object : PaymentChannel {
            override val code: Short = code

            override fun createPayment(order: PaymentOrderSnapshot): PaymentInstruction = PaymentInstruction("STUB-$code", emptyMap())

            override fun verifyAndParse(
                rawBody: ByteArray,
                headers: Map<String, String>,
            ): PaymentCallback = PaymentCallback.Ignored("stub")
        }

    companion object {
        private const val SECRET = "unit-test-secret"

        /** 与 FakePaymentChannel 同算法的测试侧签名(还原渠道服务器的签名路径)。 */
        fun sign(body: String): String =
            HexFormat.of().formatHex(
                Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(SECRET.toByteArray(), "HmacSHA256")) }.doFinal(body.toByteArray()),
            )
    }
}
