package com.gabon

import com.gabon.jooq.tables.references.RECHARGE_ORDER
import com.gabon.jooq.tables.references.RECHARGE_PACKAGE
import com.gabon.platform.security.AccessTokenCodec
import com.gabon.platform.security.PrincipalType
import com.gabon.platform.web.ProblemException
import com.gabon.platform.web.ProblemType
import com.gabon.recharge.internal.ORDER_CREATED
import com.gabon.recharge.internal.ORDER_PROCESSING
import com.gabon.recharge.internal.channel.PaymentCallback
import com.gabon.recharge.internal.channel.PaymentChannel
import com.gabon.recharge.internal.channel.PaymentInstruction
import com.gabon.recharge.internal.channel.PaymentOrderSnapshot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/** recharge 域验收(spec §4):档位、角色门;下单/列表用例随批 2 Task 3 补入。 */
@AutoConfigureMockMvc
class RechargeFlowTest : AbstractIntegrationTest() {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var codec: AccessTokenCodec

    @Autowired
    lateinit var objectMapper: ObjectMapper

    /** 渠道失败探针:code 99 恒抛,验证"渠道失败订单留 CREATED"(spec §4.2)。 */
    @TestConfiguration
    class ThrowingChannelConfig {
        @Bean
        fun throwingChannel(): PaymentChannel =
            object : PaymentChannel {
                override val code: Short = 99

                override fun createPayment(order: PaymentOrderSnapshot): PaymentInstruction =
                    throw ProblemException(ProblemType.PAYMENT_CHANNEL_ERROR, "simulated channel outage")

                override fun verifyAndParse(
                    rawBody: ByteArray,
                    headers: Map<String, String>,
                ): PaymentCallback = PaymentCallback.Ignored("never")
            }
    }

    @Test
    fun `active packages are listed by ascending price`() {
        seedPackage(diamonds = 500, priceCents = 4_900)
        seedPackage(diamonds = 100, priceCents = 1_000)
        seedPackage(diamonds = 9_999, priceCents = 99_900, active = false) // 下架不出现
        mockMvc
            .perform(get("/v1/recharge/packages").header(AUTH, "Bearer ${customerToken()}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].priceCents").value(1_000))
            .andExpect(jsonPath("$[1].priceCents").value(4_900))
    }

    @Test
    fun `admin token is rejected on recharge routes`() {
        mockMvc
            .perform(get("/v1/recharge/packages").header(AUTH, "Bearer ${adminToken()}"))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.type").value("/problems/forbidden"))
    }

    @Test
    fun `anonymous is rejected on recharge routes`() {
        mockMvc
            .perform(get("/v1/recharge/packages"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `empty package table lists as empty array`() {
        mockMvc
            .perform(get("/v1/recharge/packages").header(AUTH, "Bearer ${customerToken()}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(0))
    }

    @Test
    fun `create order snapshots the package and moves to processing`() {
        val pkg = seedPackage(diamonds = 500, priceCents = 4_900)
        mockMvc
            .perform(
                post("/v1/recharge/orders")
                    .header(AUTH, "Bearer ${customerToken(11L)}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"packageId":$pkg,"channel":1}"""),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.orderNo").isNotEmpty)
            .andExpect(jsonPath("$.payload.payUrl").isNotEmpty)
        val row = dsl.selectFrom(RECHARGE_ORDER).fetchOne()!!
        assertThat(row.status).isEqualTo(ORDER_PROCESSING)
        assertThat(row.diamonds).isEqualTo(500)
        assertThat(row.priceCents).isEqualTo(4_900)
        assertThat(row.channelOrderNo).isEqualTo("FAKE-${row.orderNo}")
    }

    @Test
    fun `create order with inactive package or unknown channel is a validation error`() {
        val inactive = seedPackage(diamonds = 1, priceCents = 1, active = false)
        mockMvc
            .perform(
                post("/v1/recharge/orders")
                    .header(AUTH, "Bearer ${customerToken()}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"packageId":$inactive,"channel":1}"""),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.type").value("/problems/validation"))
        val pkg = seedPackage(diamonds = 100, priceCents = 1_000)
        mockMvc
            .perform(
                post("/v1/recharge/orders")
                    .header(AUTH, "Bearer ${customerToken()}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"packageId":$pkg,"channel":42}"""),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.type").value("/problems/validation"))
    }

    @Test
    fun `channel failure leaves the order created for reconciliation`() {
        val pkg = seedPackage(diamonds = 100, priceCents = 1_000)
        mockMvc
            .perform(
                post("/v1/recharge/orders")
                    .header(AUTH, "Bearer ${customerToken(12L)}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"packageId":$pkg,"channel":99}"""),
            ).andExpect(status().isBadGateway)
            .andExpect(jsonPath("$.type").value("/problems/payment-channel-error"))
        val row = dsl.selectFrom(RECHARGE_ORDER).fetchOne()!!
        assertThat(row.status).isEqualTo(ORDER_CREATED) // 对账可见,用户重新下单(spec §4.2)
        assertThat(row.channelOrderNo).isNull()
    }

    @Test
    fun `orders page by keyset in descending id order`() {
        val pkg = seedPackage(diamonds = 100, priceCents = 1_000)
        repeat(3) {
            mockMvc
                .perform(
                    post("/v1/recharge/orders")
                        .header(AUTH, "Bearer ${customerToken(13L)}")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"packageId":$pkg,"channel":1}"""),
                ).andExpect(status().isOk)
        }
        val ids =
            dsl
                .select(RECHARGE_ORDER.ID)
                .from(RECHARGE_ORDER)
                .orderBy(RECHARGE_ORDER.ID.desc())
                .fetch()
                .map { it.value1()!! }
        mockMvc
            .perform(get("/v1/recharge/orders").header(AUTH, "Bearer ${customerToken(13L)}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(3))
            .andExpect(jsonPath("$.nextCursor").value(org.hamcrest.Matchers.nullValue())) // 不满页无下一页(null 字段仍会序列化输出)
        mockMvc
            .perform(get("/v1/recharge/orders").header(AUTH, "Bearer ${customerToken(13L)}").param("cursor", ids[1].toString()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(1)) // 只剩最旧一条
        // 他人订单不可见
        mockMvc
            .perform(get("/v1/recharge/orders").header(AUTH, "Bearer ${customerToken(14L)}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(0))
    }

    private fun customerToken(customerId: Long = 1L): String = codec.issue(customerId, PrincipalType.CUSTOMER, UUID.randomUUID())

    private fun adminToken(): String = codec.issue(2L, PrincipalType.ADMIN, UUID.randomUUID())

    private fun seedPackage(
        diamonds: Long,
        priceCents: Long,
        currency: String = "CNY",
        active: Boolean = true,
    ): Long =
        dsl
            .insertInto(RECHARGE_PACKAGE)
            .set(RECHARGE_PACKAGE.DIAMONDS, diamonds)
            .set(RECHARGE_PACKAGE.PRICE_CENTS, priceCents)
            .set(RECHARGE_PACKAGE.CURRENCY, currency)
            .set(RECHARGE_PACKAGE.STATUS, if (active) PKG_ACTIVE else PKG_INACTIVE) // if 表达式是 Int 型,必须给 Short 常量
            .returningResult(RECHARGE_PACKAGE.ID)
            .fetchOne()!!
            .value1()!!

    companion object {
        const val AUTH = "Authorization"
        private const val PKG_ACTIVE: Short = 1
        private const val PKG_INACTIVE: Short = 0
    }
}
