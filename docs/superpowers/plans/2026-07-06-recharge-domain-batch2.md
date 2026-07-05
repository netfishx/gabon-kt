# 充值域 批 2 实施计划(档位 + SPI/假渠道 + 下单 + 回调)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers-extended-cc:subagent-driven-development (recommended) or superpowers-extended-cc:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 落地 spec §7.5 批 2——recharge 域全链路:档位列表、渠道 SPI + 假渠道、两步下单、回调入账(inbox 去重/金额与渠道号校验/宽 CAS/三层防重)、CUSTOMER 角色规则。

**Architecture:** 模块化单体;recharge 域只经 `wallet.api` 记账(`creditRecharge`)、只经 `platform.outbox.InboxRepo` 写 inbox(表所有权:`Inbox` 归 platform);回调 = 验签(raw body)→ 金额/渠道号校验 → inbox+CAS+入账同一事务;下单三段式(建单/渠道调用/回填),网络调用不裹 DB 事务。

**Tech Stack:** Kotlin 2.4 / Spring Boot 4.1 / jOOQ 3.21 / PG18 Testcontainers / tools.jackson(readTree,不依赖 Kotlin 绑定)/ Micrometer(actuator 传递)。

**User decisions (already made):**
- 渠道形态 = SPI + 假渠道(真实渠道归子项目 7);充值为固定档位;档位管理端点不在 spec(运营侧 SQL 录入,本批测试自种)。
- 回调宽 CAS `CREATED|PROCESSING → 终态`;Failure 仅渠道明确终态;`Ignored` 不落 inbox;重复/冲突/错配一律 2xx ack;验签失败 401、解析失败 400 且不落 inbox。
- 金额币种校验仅 Success;渠道号回填/校验含带号 Failure;错配 = ERROR + 指标 + ack,不改状态不入账。
- inbox source = 域基数 + channel(recharge 基数 1000)。
- 本批主题按既定顺序判定为批 2(澄清问题超时未答复,用户可随时纠偏)。

**上游 spec:** `docs/superpowers/specs/2026-07-03-wallet-core-recharge-withdraw-design.md`(§4/§5/§7 为本批权威)。批 1 已交付:V3 表、`WalletLedgerApi.creditRecharge`、`LedgerInvariants`、`ownerDsl`、CUSTOMER/ADMIN 票据签发(`codec.issue`)。

**环境(每个 Verify 命令都需要):**
```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-25.jdk/Contents/Home
export DOCKER_HOST=unix:///Users/ethanwang/.orbstack/run/docker.sock
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
```

---

### Task 1: 档位列表 + CUSTOMER 角色门

**Goal:** `GET /v1/recharge/packages` 端点与 `/v1/recharge/**` 的 CUSTOMER 角色规则落地,recharge internal 出现真实类(删 internal marker)。

**Files:**
- Create: `src/main/kotlin/com/gabon/recharge/internal/RechargePackageRepository.kt`
- Create: `src/main/kotlin/com/gabon/recharge/internal/web/RechargeController.kt`
- Create: `src/main/kotlin/com/gabon/recharge/internal/web/RechargeDtos.kt`
- Modify: `src/main/kotlin/com/gabon/platform/security/SecurityConfig.kt`(hasRole 规则一行 + 注释)
- Delete: `src/main/kotlin/com/gabon/recharge/internal/RechargeInternalMarker.kt`(真实类落地即删,spec 模块边界 §3;api 格子仍空,RechargeApiMarker 保留)
- Create: `src/test/kotlin/com/gabon/RechargeFlowTest.kt`

**Acceptance Criteria:**
- [ ] customer 票据可列上架档位(价格升序,下架不出现)
- [ ] admin 票据访问 `/v1/recharge/**` → 403 forbidden problem;无票 → 401
- [ ] `./gradlew check` 全绿

**Verify:** `./gradlew check` → BUILD SUCCESSFUL

**Steps:**

- [ ] **Step 1: 写测试(RED)**

Create `src/test/kotlin/com/gabon/RechargeFlowTest.kt`:

```kotlin
package com.gabon

import com.gabon.jooq.tables.references.RECHARGE_PACKAGE
import com.gabon.platform.security.AccessTokenCodec
import com.gabon.platform.security.PrincipalType
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/** recharge 域验收(spec §4):档位、角色门;下单/列表用例随批 2 Task 3 补入。 */
@AutoConfigureMockMvc
class RechargeFlowTest : AbstractIntegrationTest() {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var codec: AccessTokenCodec

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
```

Run: `./gradlew test --tests "com.gabon.RechargeFlowTest"`
Expected: FAIL——`active packages...` 404(无路由);`admin token...` 期望 403 实得 404(hasRole 规则未加,admin 票过 authenticated 后无路由)。任一红即可确认端点/规则缺失。

- [ ] **Step 2: 档位仓储**

Create `src/main/kotlin/com/gabon/recharge/internal/RechargePackageRepository.kt`:

```kotlin
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
```

- [ ] **Step 3: DTO 与 controller**

Create `src/main/kotlin/com/gabon/recharge/internal/web/RechargeDtos.kt`:

```kotlin
package com.gabon.recharge.internal.web

import com.gabon.recharge.internal.RechargePackageRepository

data class PackageResponse(
    val id: Long,
    val diamonds: Long,
    val priceCents: Long,
    val currency: String,
) {
    companion object {
        fun from(row: RechargePackageRepository.PackageRow): PackageResponse = PackageResponse(row.id, row.diamonds, row.priceCents, row.currency)
    }
}
```

Create `src/main/kotlin/com/gabon/recharge/internal/web/RechargeController.kt`:

```kotlin
package com.gabon.recharge.internal.web

import com.gabon.recharge.internal.RechargePackageRepository
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** C 端充值端点(spec §4.1):/v1/recharge/** 由安全链限 CUSTOMER;回调另走公开 controller(Task 4)。 */
@RestController
@RequestMapping("/v1/recharge")
class RechargeController(
    private val packages: RechargePackageRepository,
) {
    @GetMapping("/packages")
    fun listPackages(): List<PackageResponse> = packages.listActive().map(PackageResponse::from)
}
```

- [ ] **Step 4: 安全链加 CUSTOMER 规则 + 删 internal marker**

`SecurityConfig.filterChain` 的 authorize 块改为(仅加一行,顺序钉死:permitAll 先、admin 次、recharge 再、兜底最后):

```kotlin
                registry.requestMatchers("/v1/admin/**").hasRole("ADMIN")
                // C 端资金路由只许 CUSTOMER(spec §7.2-3,堵 admin token 串门);
                // 回调精确模式经 contributor 在上方先行 permitAll(spec §7.2-1)
                registry.requestMatchers("/v1/recharge/**").hasRole("CUSTOMER")
                registry.anyRequest().authenticated()
```

删除 `src/main/kotlin/com/gabon/recharge/internal/RechargeInternalMarker.kt`(`git rm`)。

- [ ] **Step 5: 验证绿 + 全量**

Run: `./gradlew test --tests "com.gabon.RechargeFlowTest"` → PASS(3 tests)
Run: `./gradlew check` → BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/gabon/recharge/ src/main/kotlin/com/gabon/platform/security/SecurityConfig.kt src/test/kotlin/com/gabon/RechargeFlowTest.kt
git commit -m "feat: add recharge packages with customer gate"
```

---

### Task 2: 渠道 SPI + 假渠道

**Goal:** `PaymentChannel` SPI(raw body 验签契约)、registry(code 重复 fail-fast)、HMAC 假渠道与配置落地。

**Files:**
- Create: `src/main/kotlin/com/gabon/recharge/internal/channel/PaymentChannel.kt`(接口 + 快照/结果/回调类型)
- Create: `src/main/kotlin/com/gabon/recharge/internal/channel/PaymentChannelRegistry.kt`
- Create: `src/main/kotlin/com/gabon/recharge/internal/channel/FakePaymentChannel.kt`
- Modify: `src/test/kotlin/com/gabon/AbstractIntegrationTest.kt`(注入假渠道属性两行)
- Create: `src/test/kotlin/com/gabon/FakePaymentChannelTest.kt`(纯单元,无容器)

**Acceptance Criteria:**
- [ ] `verifyAndParse` 基于 raw body + 小写 headers 验签,失败 401、解析失败 400,均抛 ProblemException
- [ ] Success 必带 channelOrderNo/paidCents/currency,缺字段 → 400;`FAILED` 之外的非 `SUCCESS` 状态 → Ignored
- [ ] registry 重复 code 启动 fail-fast;未知 code → 400 validation
- [ ] `./gradlew check` 全绿

**Verify:** `./gradlew check` → BUILD SUCCESSFUL

**Steps:**

- [ ] **Step 1: 写单元测试(RED)**

Create `src/test/kotlin/com/gabon/FakePaymentChannelTest.kt`:

```kotlin
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
    fun `unparseable body and success missing amount are validation errors`() {
        val junk = "not-json"
        assertThatThrownBy { channel.verifyAndParse(junk.toByteArray(), mapOf("x-fake-signature" to sign(junk))) }
            .isInstanceOfSatisfying(ProblemException::class.java) { assertThat(it.type).isEqualTo(ProblemType.VALIDATION) }
        val noAmount = """{"externalId":"E5","orderNo":"R-5","status":"SUCCESS","channelOrderNo":"F-5"}"""
        assertThatThrownBy { channel.verifyAndParse(noAmount.toByteArray(), mapOf("x-fake-signature" to sign(noAmount))) }
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
```

Run: `./gradlew compileTestKotlin` → 预期 FAIL(SPI 类型不存在)。

- [ ] **Step 2: SPI 类型**

Create `src/main/kotlin/com/gabon/recharge/internal/channel/PaymentChannel.kt`:

```kotlin
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
     * 验签 + 解析:基于 raw body + headers(key 已小写归一),**先验后解析**(反序列化再验签会被
     * 字段顺序/空白/编码破坏);验签失败抛 UNAUTHENTICATED(401),解析失败抛 VALIDATION(400)——均不落 inbox。
     */
    fun verifyAndParse(
        rawBody: ByteArray,
        headers: Map<String, String>,
    ): PaymentCallback
}
```

- [ ] **Step 3: registry**

Create `src/main/kotlin/com/gabon/recharge/internal/channel/PaymentChannelRegistry.kt`:

```kotlin
package com.gabon.recharge.internal.channel

import com.gabon.platform.web.ProblemException
import com.gabon.platform.web.ProblemType
import org.springframework.stereotype.Component

/** 渠道注册表(spec §5.1):Spring 注入全部 PaymentChannel;code 重复启动 fail-fast(后注册静默覆盖是危险态)。 */
@Component
class PaymentChannelRegistry(
    channels: List<PaymentChannel>,
) {
    private val byCode: Map<Short, PaymentChannel> =
        channels.groupBy { it.code }.mapValues { (code, list) ->
            check(list.size == 1) { "duplicate payment channel code $code: ${list.map { c -> c::class.simpleName }}" }
            list.single()
        }

    /** 未知 channel = 边界输入错误 → 400(spec §5.1)。 */
    fun byCode(code: Short): PaymentChannel = byCode[code] ?: throw ProblemException(ProblemType.VALIDATION, "unknown payment channel: $code")
}
```

- [ ] **Step 4: 假渠道 + 配置**

Create `src/main/kotlin/com/gabon/recharge/internal/channel/FakePaymentChannel.kt`:

```kotlin
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
 * 测试/联调用假收款渠道(spec §5.3):HMAC-SHA256 对 raw body 验签(真实可测,非空壳 return true)。
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
        val given =
            headers[SIGNATURE_HEADER]
                ?: throw ProblemException(ProblemType.UNAUTHENTICATED, "fake callback missing signature header")
        val expected = hmacHex(rawBody)
        if (!MessageDigest.isEqual(expected.toByteArray(), given.toByteArray())) {
            throw ProblemException(ProblemType.UNAUTHENTICATED, "fake callback bad signature")
        }
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
                    channelOrderNo = node.path("channelOrderNo").takeIf { it.isTextual }?.asString(),
                    reason = node.path("reason").asString("failed"),
                )
            else -> PaymentCallback.Ignored(externalId) // pending/unknown → ack,不落 inbox,不动状态(spec §5.2)
        }
    }

    private fun hmacHex(body: ByteArray): String =
        HexFormat.of().formatHex(
            Mac.getInstance(HMAC_ALG).apply { init(SecretKeySpec(props.secret.toByteArray(), HMAC_ALG)) }.doFinal(body),
        )

    private fun JsonNode.requiredText(field: String): String =
        path(field).takeIf { it.isTextual }?.asString()
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
```

注:tools.jackson(Jackson 3)的 JsonNode 文本取值方法若非 `asString` 而是 `asText`(2.x 名),以编译器为准就地替换并在汇报中注明——语义不变,仅方法名差异。

`AbstractIntegrationTest.containers()` 加两行(密钥属性旁):

```kotlin
            registry.add("gabon.channel.fake.enabled") { "true" }
            registry.add("gabon.channel.fake.secret") { "test-channel-secret" }
```

- [ ] **Step 5: 单测绿 + 全量**

单测的 SECRET 需与构造入参一致(`unit-test-secret`,不依赖 Spring 属性)。
Run: `./gradlew test --tests "com.gabon.FakePaymentChannelTest"` → PASS(5 tests)
Run: `./gradlew check` → BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/gabon/recharge/internal/channel/ src/test/kotlin/com/gabon/FakePaymentChannelTest.kt src/test/kotlin/com/gabon/AbstractIntegrationTest.kt
git commit -m "feat: add payment channel spi with fake channel"
```

---

### Task 3: 两步下单 + 订单列表

**Goal:** `POST /v1/recharge/orders`(建单 → 渠道 → CAS PROCESSING,渠道失败留 CREATED)与 keyset 订单列表,新增 `PAYMENT_CHANNEL_ERROR` problem。

**Files:**
- Create: `src/main/kotlin/com/gabon/recharge/internal/RechargeOrderRepository.kt`
- Create: `src/main/kotlin/com/gabon/recharge/internal/RechargeService.kt`
- Modify: `src/main/kotlin/com/gabon/recharge/internal/web/RechargeController.kt`
- Modify: `src/main/kotlin/com/gabon/recharge/internal/web/RechargeDtos.kt`
- Modify: `src/main/kotlin/com/gabon/platform/web/ProblemType.kt`
- Modify: `src/test/kotlin/com/gabon/RechargeFlowTest.kt`

**Acceptance Criteria:**
- [ ] 下单快照档位三列,渠道成功后 `PROCESSING` + 渠道号回填;渠道失败订单留 `CREATED`、对外 502 `/problems/payment-channel-error`
- [ ] 下架/不存在档位、未知 channel → 400 validation
- [ ] 列表 keyset:`customer_id = me AND id < cursor ORDER BY id DESC LIMIT 20`,nextCursor = 满页时最后一条 id
- [ ] `./gradlew check` 全绿

**Verify:** `./gradlew check` → BUILD SUCCESSFUL

**Steps:**

- [ ] **Step 1: 补测试(RED)**

`RechargeFlowTest` 追加(类顶部补 imports:`com.gabon.jooq.tables.references.RECHARGE_ORDER`、`com.gabon.recharge.internal.ORDER_CREATED`、`com.gabon.recharge.internal.ORDER_PROCESSING`、`com.gabon.recharge.internal.channel.PaymentCallback`、`com.gabon.recharge.internal.channel.PaymentChannel`、`com.gabon.recharge.internal.channel.PaymentInstruction`、`com.gabon.recharge.internal.channel.PaymentOrderSnapshot`、`com.gabon.platform.web.ProblemException`、`com.gabon.platform.web.ProblemType`、`org.assertj.core.api.Assertions.assertThat`、`org.springframework.boot.test.context.TestConfiguration`、`org.springframework.context.annotation.Bean`、`org.springframework.http.MediaType`、`org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post`、`tools.jackson.databind.ObjectMapper`):

```kotlin
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
        val ids = dsl.select(RECHARGE_ORDER.ID).from(RECHARGE_ORDER).orderBy(RECHARGE_ORDER.ID.desc()).fetch().map { it.value1()!! }
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
```

注:jOOQ Record 属性名(`row.status` 等)按 codegen 产物为准(KotlinGenerator 生成属性访问器);若为 `row.get(RECHARGE_ORDER.STATUS)` 形态,等价替换。

Run: `./gradlew compileTestKotlin` → 预期 FAIL(ORDER_* 常量、端点不存在)。

- [ ] **Step 2: ProblemType 加 PAYMENT_CHANNEL_ERROR**

`INSUFFICIENT_BALANCE` 行后加(spec §7.1,标题 Channel error,覆盖收款下单与批 3 出款提交失败):

```kotlin
    PAYMENT_CHANNEL_ERROR(HttpStatus.BAD_GATEWAY, "/problems/payment-channel-error", "Channel error"),
```

- [ ] **Step 3: 订单仓储**

Create `src/main/kotlin/com/gabon/recharge/internal/RechargeOrderRepository.kt`:

```kotlin
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
}
```

- [ ] **Step 4: 应用服务**

Create `src/main/kotlin/com/gabon/recharge/internal/RechargeService.kt`:

```kotlin
package com.gabon.recharge.internal

import com.gabon.platform.security.UuidV7
import com.gabon.platform.web.ProblemException
import com.gabon.platform.web.ProblemType
import com.gabon.recharge.internal.channel.PaymentChannelRegistry
import com.gabon.recharge.internal.channel.PaymentOrderSnapshot
import org.springframework.stereotype.Service
import java.time.Clock

/** 下单结果:orderNo + 渠道支付凭据(透传前端)。 */
data class CreateOrderResult(
    val orderNo: String,
    val payload: Map<String, String>,
)

/**
 * 充值应用服务(spec §4)。createOrder 刻意**无整体事务**:本地建单与渠道网络调用分离
 * (网络调用不裹 DB 事务,与 spec §6.4 同则),单条 insert/update 自身原子。
 */
@Service
class RechargeService(
    private val packages: RechargePackageRepository,
    private val orders: RechargeOrderRepository,
    private val registry: PaymentChannelRegistry,
    private val clock: Clock,
) {
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
        orders.markProcessing(orderNo, instruction.channelOrderNo)
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
```

- [ ] **Step 5: DTO 与端点**

`RechargeDtos.kt` 追加:

```kotlin
data class CreateOrderRequest(
    @field:Positive
    val packageId: Long,
    @field:Positive
    val channel: Short,
)

data class CreateOrderResponse(
    val orderNo: String,
    val payload: Map<String, String>,
)

data class OrderResponse(
    val orderNo: String,
    val diamonds: Long,
    val priceCents: Long,
    val currency: String,
    val status: Short,
) {
    companion object {
        fun from(row: RechargeOrderRepository.OrderRow): OrderResponse = OrderResponse(row.orderNo, row.diamonds, row.priceCents, row.currency, row.status)
    }
}

data class OrdersPageResponse(
    val items: List<OrderResponse>,
    val nextCursor: Long?,
)
```

(顶部补 `import jakarta.validation.constraints.Positive`、`import com.gabon.recharge.internal.RechargeOrderRepository`。)

`RechargeController` 追加(构造器加 `private val service: RechargeService`;顶部补 imports:`GabonPrincipal`/`SecurityContextHolder`/`Valid`/`PostMapping`/`RequestBody`/`RequestParam`/`RechargeService`):

```kotlin
    @PostMapping("/orders")
    fun createOrder(
        @Valid @RequestBody req: CreateOrderRequest,
    ): CreateOrderResponse {
        val result = service.createOrder(currentPrincipal().id, req.packageId, req.channel)
        return CreateOrderResponse(result.orderNo, result.payload)
    }

    @GetMapping("/orders")
    fun listOrders(
        @RequestParam(required = false) cursor: Long?,
    ): OrdersPageResponse {
        val rows = service.listOrders(currentPrincipal().id, cursor)
        return OrdersPageResponse(
            items = rows.map(OrderResponse::from),
            nextCursor = if (rows.size == RechargeService.PAGE_SIZE) rows.last().id else null,
        )
    }

    /** 需票路由:授权链已保证认证存在,直取 principal(同 AuthController 先例)。 */
    private fun currentPrincipal(): GabonPrincipal = SecurityContextHolder.getContext().authentication!!.principal as GabonPrincipal
```

- [ ] **Step 6: 验证绿 + 全量**

Run: `./gradlew test --tests "com.gabon.RechargeFlowTest"` → PASS(7 tests)
Run: `./gradlew check` → BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/gabon/recharge/ src/main/kotlin/com/gabon/platform/web/ProblemType.kt src/test/kotlin/com/gabon/RechargeFlowTest.kt
git commit -m "feat: add recharge order creation and listing"
```

---

### Task 4: 回调入账全链路

**Goal:** 公开回调端点落地:验签 → 金额/渠道号校验 → inbox 去重(platform 收口)+ 宽 CAS + `creditRecharge` 同一事务;异常路径全部 ack + 结构化日志 + 指标。

**Files:**
- Create: `src/main/kotlin/com/gabon/platform/outbox/InboxRepo.kt`(表所有权:`Inbox` 归 platform,recharge 不得直插)
- Create: `src/main/kotlin/com/gabon/recharge/internal/web/RechargeCallbackController.kt`
- Create: `src/main/kotlin/com/gabon/recharge/internal/RechargePublicRoutes.kt`
- Modify: `src/main/kotlin/com/gabon/recharge/internal/RechargeService.kt`(handleCallback)
- Modify: `src/main/kotlin/com/gabon/recharge/internal/RechargeOrderRepository.kt`(回调侧三方法)
- Create: `src/test/kotlin/com/gabon/RechargeCallbackTest.kt`

**Acceptance Criteria:**
- [ ] 成功回调入账一次(重复/并发重复只入账一次),`CREATED` 直收成功回调可入账并回填渠道号
- [ ] 金额错配/渠道号错配 → 2xx ack + 不落 inbox + 不改状态 + 不入账;终态冲突 → 2xx + WARN 不翻案
- [ ] 验签失败 401、解析失败 400,均不落 inbox;`PENDING` → 2xx 且 inbox 空
- [ ] 同 `external_id` 跨域 source 不互吞(source = 1000 + channel)
- [ ] 资金用例终局 `LedgerInvariants.assertHolds`;`./gradlew check` 全绿

**Verify:** `./gradlew check` → BUILD SUCCESSFUL

**Steps:**

- [ ] **Step 1: 写测试(RED)**

Create `src/test/kotlin/com/gabon/RechargeCallbackTest.kt`:

```kotlin
package com.gabon

import com.gabon.jooq.tables.references.INBOX
import com.gabon.jooq.tables.references.LEDGER_TXN
import com.gabon.jooq.tables.references.RECHARGE_ORDER
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

/** 回调入账验收(spec §4.3/§4.4):三层防重、宽 CAS、金额/渠道号校验、ack 语义、不变量。 */
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
    ): Seeded {
        val orderNo = "R-T$customerId"
        val id =
            dsl
                .insertInto(RECHARGE_ORDER)
                .set(RECHARGE_ORDER.ORDER_NO, orderNo)
                .set(RECHARGE_ORDER.CUSTOMER_ID, customerId)
                .set(RECHARGE_ORDER.PACKAGE_ID, seededPackageId)
                .set(RECHARGE_ORDER.DIAMONDS, diamonds)
                .set(RECHARGE_ORDER.PRICE_CENTS, priceCents)
                .set(RECHARGE_ORDER.CURRENCY, "CNY")
                .set(RECHARGE_ORDER.CHANNEL, 1)
                .set(RECHARGE_ORDER.CHANNEL_ORDER_NO, channelOrderNo)
                .set(RECHARGE_ORDER.STATUS, status)
                .returningResult(RECHARGE_ORDER.ID)
                .fetchOne()!!
                .value1()!!
        return Seeded(id, orderNo)
    }

    /** 每测试前 truncate,档位按需重建。 */
    private val seededPackageId: Long by lazy {
        dsl
            .insertInto(com.gabon.jooq.tables.references.RECHARGE_PACKAGE)
            .set(com.gabon.jooq.tables.references.RECHARGE_PACKAGE.DIAMONDS, 1L)
            .set(com.gabon.jooq.tables.references.RECHARGE_PACKAGE.PRICE_CENTS, 1L)
            .set(com.gabon.jooq.tables.references.RECHARGE_PACKAGE.CURRENCY, "CNY")
            .returningResult(com.gabon.jooq.tables.references.RECHARGE_PACKAGE.ID)
            .fetchOne()!!
            .value1()!!
    }

    private fun success(
        o: Seeded,
        externalId: String,
        channelOrderNo: String? = null,
        paidCents: Long? = null,
    ): String {
        val row = dsl.selectFrom(RECHARGE_ORDER).where(RECHARGE_ORDER.ID.eq(o.id)).fetchOne()!!
        val no = channelOrderNo ?: row.channelOrderNo ?: "F-DEFAULT"
        val cents = paidCents ?: row.priceCents
        return """{"externalId":"$externalId","orderNo":"${o.orderNo}","status":"SUCCESS","channelOrderNo":"$no","paidCents":$cents,"currency":"CNY"}"""
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
```

Run: `./gradlew compileTestKotlin` → 预期 FAIL(InboxRepo/端点不存在)。

- [ ] **Step 2: platform InboxRepo**

Create `src/main/kotlin/com/gabon/platform/outbox/InboxRepo.kt`:

```kotlin
package com.gabon.platform.outbox

import com.gabon.jooq.tables.references.INBOX
import org.jooq.DSLContext
import org.springframework.stereotype.Repository

/**
 * 入站回调去重(spec C2.5/§4.3-3):`Inbox` 表归 platform(规则 6),业务域一律经此收口。
 * 必须在调用方业务事务内调用——inbox 记录与业务效果同生共死,拆开会吞掉渠道重试(spec §4.3-5)。
 * source 是全局命名空间:域基数 + channel(recharge 1000 / withdraw 2000,spec §4.3-3)。
 */
@Repository
class InboxRepo(
    private val dsl: DSLContext,
) {
    /** true=首见;false=同 (source, external_id) 已处理(重复回调,调用方短路 ack)。 */
    fun tryRecord(
        source: Short,
        externalId: String,
    ): Boolean =
        dsl
            .insertInto(INBOX, INBOX.SOURCE, INBOX.EXTERNAL_ID)
            .values(source, externalId)
            .onConflictDoNothing()
            .execute() == 1
}
```

- [ ] **Step 3: 订单仓储回调侧三方法**

`RechargeOrderRepository` 追加:

```kotlin
    data class CallbackRow(
        val id: Long,
        val customerId: Long,
        val diamonds: Long,
        val priceCents: Long,
        val currency: String,
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
                RECHARGE_ORDER.CHANNEL_ORDER_NO,
            ).from(RECHARGE_ORDER)
            .where(RECHARGE_ORDER.ORDER_NO.eq(orderNo))
            .fetchOne()
            ?.let { CallbackRow(it.value1()!!, it.value2()!!, it.value3()!!, it.value4()!!, it.value5()!!, it.value6()) }

    /** 渠道号回填(spec §4.3-2):仅本地为空时写入(两步下单第二步崩溃场景)。 */
    fun backfillChannelOrderNo(
        id: Long,
        channelOrderNo: String,
    ): Int =
        dsl
            .update(RECHARGE_ORDER)
            .set(RECHARGE_ORDER.CHANNEL_ORDER_NO, channelOrderNo)
            .where(RECHARGE_ORDER.ID.eq(id).and(RECHARGE_ORDER.CHANNEL_ORDER_NO.isNull))
            .execute()

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
```

- [ ] **Step 4: 回调服务(RechargeService 追加)**

构造器追加 `private val inbox: InboxRepo`、`private val ledger: WalletLedgerApi`、`private val meters: MeterRegistry`;顶部补 imports:`com.gabon.platform.outbox.InboxRepo`、`com.gabon.wallet.api.WalletLedgerApi`、`com.gabon.recharge.internal.channel.PaymentCallback`、`io.micrometer.core.instrument.MeterRegistry`、`org.slf4j.LoggerFactory`、`org.springframework.transaction.annotation.Transactional`。追加方法:

```kotlin
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 回调处理(spec §4.3,顺序钉死):验签/解析(SPI 抛 401/400,不落 inbox)→ 金额校验(仅 Success)
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

    private fun applySuccess(
        channelCode: Short,
        cb: PaymentCallback.Success,
    ) {
        val order = orders.findByOrderNo(cb.orderNo) ?: return anomaly("unknown-order", "orderNo=${cb.orderNo}")
        // 金额币种校验(安全关键,spec §4.3-1):防"真实小额支付回调错配到大额订单"入账
        if (cb.paidCents != order.priceCents || cb.currency != order.currency) {
            return anomaly("amount-mismatch", "orderNo=${cb.orderNo} paid=${cb.paidCents}${cb.currency} expect=${order.priceCents}${order.currency}")
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

    private fun applyFailure(
        channelCode: Short,
        cb: PaymentCallback.Failure,
    ) {
        val order = orders.findByOrderNo(cb.orderNo) ?: return anomaly("unknown-order", "orderNo=${cb.orderNo}")
        // Failure 无金额字段;渠道号带了就必须过同一校验(spec §4.3-2)
        if (!reconcileChannelOrderNo(order, cb.channelOrderNo)) {
            return anomaly("channel-no-mismatch", "orderNo=${cb.orderNo} got=${cb.channelOrderNo} local=${order.channelOrderNo}")
        }
        if (!inbox.tryRecord(sourceOf(channelCode), cb.externalId)) return
        if (orders.casToTerminal(order.id, ORDER_FAILED) == 0) {
            conflict("failure callback on terminal order ${cb.orderNo}")
        }
    }

    /** 渠道号回填/校验(spec §4.3-2):本地为空 → 同事务回填;已有且不同 → 错配。 */
    private fun reconcileChannelOrderNo(
        order: RechargeOrderRepository.CallbackRow,
        channelOrderNo: String?,
    ): Boolean {
        if (channelOrderNo == null) return true
        val existing = order.channelOrderNo ?: run {
            orders.backfillChannelOrderNo(order.id, channelOrderNo)
            return true
        }
        return existing == channelOrderNo
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
```

companion 追加:

```kotlin
        /** inbox source 命名空间基数(spec §4.3-3):recharge 1000,withdraw 2000(批 3)。 */
        const val SOURCE_BASE = 1_000
```

- [ ] **Step 5: 公开路由 + 回调 controller**

Create `src/main/kotlin/com/gabon/recharge/internal/RechargePublicRoutes.kt`:

```kotlin
package com.gabon.recharge.internal

import com.gabon.platform.security.PublicRoutesContributor
import org.springframework.stereotype.Component

/** 渠道回调公开路由(spec §7.2-1,精确模式):渠道服务器不带 JWT,安全靠 SPI 验签。 */
@Component
class RechargePublicRoutes : PublicRoutesContributor {
    override fun publicRoutes(): List<String> = listOf("/v1/recharge/callback/*")
}
```

Create `src/main/kotlin/com/gabon/recharge/internal/web/RechargeCallbackController.kt`:

```kotlin
package com.gabon.recharge.internal.web

import com.gabon.recharge.internal.RechargeService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/**
 * 渠道回调入口(spec §4.3):公开路由(RechargePublicRoutes),raw body 原样透传 SPI 验签
 * (先验后解析);headers key 小写归一(servlet 容器大小写差异,spec §5.2)。
 * 正常返回 = 2xx ack;401/400 由 SPI 抛 ProblemException 经全局 handler 渲染。
 */
@RestController
class RechargeCallbackController(
    private val service: RechargeService,
) {
    @PostMapping("/v1/recharge/callback/{channel}")
    fun callback(
        @PathVariable channel: Short,
        @RequestBody rawBody: ByteArray,
        request: HttpServletRequest,
    ) {
        val headers = request.headerNames.asSequence().associate { it.lowercase() to request.getHeader(it) }
        service.handleCallback(channel, rawBody, headers)
    }
}
```

- [ ] **Step 6: 验证绿 + 全量**

Run: `./gradlew test --tests "com.gabon.RechargeCallbackTest"` → PASS(10 tests)
Run: `./gradlew check` → BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/gabon/platform/outbox/InboxRepo.kt src/main/kotlin/com/gabon/recharge/ src/test/kotlin/com/gabon/RechargeCallbackTest.kt
git commit -m "feat: settle recharge callbacks into the ledger"
```

---

## 收尾

批 2 完成后:`./gradlew check` 全绿即验收;批 3(withdraw 域 + outbox worker)以同一 spec 为纲另出计划(依赖本批的 SPI 三态模式、InboxRepo、source 命名空间)。
