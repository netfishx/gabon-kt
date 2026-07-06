package com.gabon

import com.gabon.jooq.tables.references.INBOX
import com.gabon.jooq.tables.references.LEDGER_TXN
import com.gabon.jooq.tables.references.RECHARGE_ORDER
import com.gabon.jooq.tables.references.RECHARGE_PACKAGE
import com.gabon.platform.outbox.InboxRepo
import com.gabon.recharge.internal.ORDER_FAILED
import com.gabon.recharge.internal.ORDER_PROCESSING
import com.gabon.recharge.internal.ORDER_SUCCESS
import com.gabon.wallet.api.WalletBalanceApi
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.HexFormat
import java.util.concurrent.CountDownLatch
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/** 回调入账验收(spec §4.3/§4.4):三层防重、宽 CAS、渠道归属/金额/渠道号校验、ack 语义、不变量。 */
@AutoConfigureMockMvc
class RechargeCallbackTest : AbstractIntegrationTest() {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var wallet: WalletBalanceApi

    @Autowired
    lateinit var inboxRepo: InboxRepo

    @Test
    fun `success callback credits the customer once`() {
        val o = seedOrder(customerId = 20L, diamonds = 500, priceCents = 4_900, status = ORDER_PROCESSING, channelOrderNo = "F-20")
        postCallback(success(o, externalId = "E-20")).andExpect(status().isOk)
        assertThat(statusOf(o.id)).isEqualTo(ORDER_SUCCESS)
        assertThat(wallet.balanceOf(20L)).isEqualTo(500)
        // 重复回调:ack 且不重复入账(inbox 短路)
        postCallback(success(o, externalId = "E-20")).andExpect(status().isOk)
        assertThat(wallet.balanceOf(20L)).isEqualTo(500)
        assertThat(dsl.fetchCount(LEDGER_TXN)).isEqualTo(1)
        LedgerInvariants.assertHolds(dsl)
    }

    @Test
    fun `concurrent duplicate success callbacks credit exactly once`() {
        val o = seedOrder(customerId = 21L, diamonds = 300, priceCents = 3_000, status = ORDER_PROCESSING, channelOrderNo = "F-21")
        val body = success(o, externalId = "E-21")
        val start = CountDownLatch(1)
        val threads =
            (0..1).map {
                thread {
                    start.await()
                    postCallback(body).andExpect(status().isOk)
                }
            }
        start.countDown()
        threads.forEach { it.join() }
        assertThat(wallet.balanceOf(21L)).isEqualTo(300)
        assertThat(dsl.fetchCount(LEDGER_TXN)).isEqualTo(1)
        LedgerInvariants.assertHolds(dsl)
    }

    @Test
    fun `created order accepts success callback and backfills channel number`() {
        // 两步下单第二步崩溃场景:CREATED 且无渠道号,渠道真相优先(spec §4.3 宽 CAS + 回填)
        val o = seedOrder(customerId = 22L, diamonds = 100, priceCents = 1_000, status = 1, channelOrderNo = null)
        postCallback(success(o, externalId = "E-22", channelOrderNo = "F-LATE")).andExpect(status().isOk)
        assertThat(statusOf(o.id)).isEqualTo(ORDER_SUCCESS)
        assertThat(channelOrderNoOf(o.id)).isEqualTo("F-LATE")
        assertThat(wallet.balanceOf(22L)).isEqualTo(100)
        LedgerInvariants.assertHolds(dsl)
    }

    @Test
    fun `mismatched channel number acks without crediting`() {
        val o = seedOrder(customerId = 23L, diamonds = 100, priceCents = 1_000, status = ORDER_PROCESSING, channelOrderNo = "F-23")
        postCallback(success(o, externalId = "E-23", channelOrderNo = "F-OTHER")).andExpect(status().isOk)
        assertThat(statusOf(o.id)).isEqualTo(ORDER_PROCESSING)
        assertThat(wallet.balanceOf(23L)).isEqualTo(0)
        assertThat(dsl.fetchCount(INBOX)).isEqualTo(0) // 错配不落 inbox(渠道重试仍可达)
    }

    @Test
    fun `amount mismatch acks without crediting`() {
        val o = seedOrder(customerId = 24L, diamonds = 100, priceCents = 1_000, status = ORDER_PROCESSING, channelOrderNo = "F-24")
        postCallback(success(o, externalId = "E-24", paidCents = 1)).andExpect(status().isOk)
        assertThat(statusOf(o.id)).isEqualTo(ORDER_PROCESSING)
        assertThat(wallet.balanceOf(24L)).isEqualTo(0)
        assertThat(dsl.fetchCount(INBOX)).isEqualTo(0)
    }

    @Test
    fun `failed terminal state does not flip on late success`() {
        val o = seedOrder(customerId = 25L, diamonds = 100, priceCents = 1_000, status = ORDER_PROCESSING, channelOrderNo = "F-25")
        postCallback(failure(o, externalId = "E-25a")).andExpect(status().isOk)
        assertThat(statusOf(o.id)).isEqualTo(ORDER_FAILED)
        postCallback(success(o, externalId = "E-25b")).andExpect(status().isOk) // 终态冲突:ack + WARN 不翻案
        assertThat(statusOf(o.id)).isEqualTo(ORDER_FAILED)
        assertThat(wallet.balanceOf(25L)).isEqualTo(0)
        LedgerInvariants.assertHolds(dsl)
    }

    @Test
    fun `bad signature is rejected and nothing is recorded`() {
        val o = seedOrder(customerId = 26L, diamonds = 100, priceCents = 1_000, status = ORDER_PROCESSING, channelOrderNo = "F-26")
        mockMvc
            .perform(
                post("/v1/recharge/callback/1")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(success(o, externalId = "E-26"))
                    .header("x-fake-signature", "deadbeef"),
            ).andExpect(status().isUnauthorized)
        assertThat(dsl.fetchCount(INBOX)).isEqualTo(0)
        assertThat(statusOf(o.id)).isEqualTo(ORDER_PROCESSING)
    }

    @Test
    fun `pending status is acked without inbox record`() {
        val o = seedOrder(customerId = 27L, diamonds = 100, priceCents = 1_000, status = ORDER_PROCESSING, channelOrderNo = "F-27")
        postCallback("""{"externalId":"E-27","orderNo":"${o.orderNo}","status":"PENDING"}""").andExpect(status().isOk)
        assertThat(dsl.fetchCount(INBOX)).isEqualTo(0) // Ignored 不落 inbox(spec §5.2)
        assertThat(statusOf(o.id)).isEqualTo(ORDER_PROCESSING)
    }

    @Test
    fun `callback on another channels order acks without side effects`() {
        // 渠道归属校验:渠道 1 的合法签名不得结算 channel=2 的订单(多渠道横向越权)
        val o =
            seedOrder(
                customerId = 29L,
                diamonds = 100,
                priceCents = 1_000,
                status = ORDER_PROCESSING,
                channelOrderNo = "F-29",
                channel = 2,
            )
        postCallback(success(o, externalId = "E-29")).andExpect(status().isOk)
        assertThat(statusOf(o.id)).isEqualTo(ORDER_PROCESSING)
        assertThat(wallet.balanceOf(29L)).isEqualTo(0)
        assertThat(dsl.fetchCount(INBOX)).isEqualTo(0)
    }

    @Test
    fun `failure with mismatched channel number acks without failing the order`() {
        // 带号 Failure 必须过同一渠道号校验(spec §4.3-2),不得绕过一致性直接打 FAILED
        val o = seedOrder(customerId = 28L, diamonds = 100, priceCents = 1_000, status = ORDER_PROCESSING, channelOrderNo = "F-28")
        val body = """{"externalId":"E-28","orderNo":"${o.orderNo}","status":"FAILED","channelOrderNo":"F-WRONG","reason":"declined"}"""
        postCallback(body).andExpect(status().isOk)
        assertThat(statusOf(o.id)).isEqualTo(ORDER_PROCESSING)
        assertThat(dsl.fetchCount(INBOX)).isEqualTo(0)
    }

    @Test
    fun `same external id dedups independently across source namespaces`() {
        assertThat(inboxRepo.tryRecord(1_001, "X")).isTrue() // recharge channel 1
        assertThat(inboxRepo.tryRecord(2_001, "X")).isTrue() // withdraw channel 1(批 3):跨域不互吞
        assertThat(inboxRepo.tryRecord(1_001, "X")).isFalse()
    }

    // ---- helpers ----

    data class Seeded(
        val id: Long,
        val orderNo: String,
    )

    private fun seedOrder(
        customerId: Long,
        diamonds: Long,
        priceCents: Long,
        status: Short,
        channelOrderNo: String?,
        channel: Short = 1,
    ): Seeded {
        val orderNo = "R-T$customerId"
        val id =
            dsl
                .insertInto(RECHARGE_ORDER)
                .set(RECHARGE_ORDER.ORDER_NO, orderNo)
                .set(RECHARGE_ORDER.CUSTOMER_ID, customerId)
                .set(RECHARGE_ORDER.PACKAGE_ID, seededPackageId())
                .set(RECHARGE_ORDER.DIAMONDS, diamonds)
                .set(RECHARGE_ORDER.PRICE_CENTS, priceCents)
                .set(RECHARGE_ORDER.CURRENCY, "CNY")
                .set(RECHARGE_ORDER.CHANNEL, channel)
                .set(RECHARGE_ORDER.CHANNEL_ORDER_NO, channelOrderNo)
                .set(RECHARGE_ORDER.STATUS, status)
                .returningResult(RECHARGE_ORDER.ID)
                .fetchOne()!!
                .value1()!!
        return Seeded(id, orderNo)
    }

    /** 每测试前 truncate,档位现用现建(FK 需要)。 */
    private fun seededPackageId(): Long =
        dsl
            .insertInto(RECHARGE_PACKAGE)
            .set(RECHARGE_PACKAGE.DIAMONDS, 1L)
            .set(RECHARGE_PACKAGE.PRICE_CENTS, 1L)
            .set(RECHARGE_PACKAGE.CURRENCY, "CNY")
            .returningResult(RECHARGE_PACKAGE.ID)
            .fetchOne()!!
            .value1()!!

    private fun success(
        o: Seeded,
        externalId: String,
        channelOrderNo: String? = null,
        paidCents: Long? = null,
    ): String {
        val row = dsl.selectFrom(RECHARGE_ORDER).where(RECHARGE_ORDER.ID.eq(o.id)).fetchOne()!!
        val no = channelOrderNo ?: row.channelOrderNo ?: "F-DEFAULT"
        val cents = paidCents ?: row.priceCents
        return """{"externalId":"$externalId","orderNo":"${o.orderNo}","status":"SUCCESS",""" +
            """"channelOrderNo":"$no","paidCents":$cents,"currency":"CNY"}"""
    }

    /** Failure 不带渠道号(合法路径,spec §5.2 channelOrderNo 可空);带号错配另有专测。 */
    private fun failure(
        o: Seeded,
        externalId: String,
    ): String = """{"externalId":"$externalId","orderNo":"${o.orderNo}","status":"FAILED","reason":"declined"}"""

    private fun postCallback(body: String) =
        mockMvc.perform(
            post("/v1/recharge/callback/1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("x-fake-signature", sign(body)),
        )

    private fun statusOf(id: Long): Short =
        dsl
            .select(RECHARGE_ORDER.STATUS)
            .from(RECHARGE_ORDER)
            .where(RECHARGE_ORDER.ID.eq(id))
            .fetchOne()!!
            .value1()!!

    private fun channelOrderNoOf(id: Long): String? =
        dsl
            .select(RECHARGE_ORDER.CHANNEL_ORDER_NO)
            .from(RECHARGE_ORDER)
            .where(RECHARGE_ORDER.ID.eq(id))
            .fetchOne()!!
            .value1()

    companion object {
        private const val SECRET = "test-channel-secret" // 与 AbstractIntegrationTest 注入值一致

        fun sign(body: String): String =
            HexFormat.of().formatHex(
                Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(SECRET.toByteArray(), "HmacSHA256")) }.doFinal(body.toByteArray()),
            )
    }
}
