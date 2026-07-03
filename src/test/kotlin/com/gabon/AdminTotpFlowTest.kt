package com.gabon

import com.gabon.identity.internal.Totp
import com.gabon.identity.internal.TotpSecretCrypto
import com.gabon.identity.internal.UsernameCanonicalizer
import com.gabon.identity.internal.web.AdminLoginRequest
import com.gabon.identity.internal.web.TotpConfirmRequest
import com.gabon.identity.internal.web.TotpEnrollResponse
import com.gabon.jooq.tables.references.ADMIN_USER
import com.gabon.platform.security.AccessTokenCodec
import com.gabon.platform.security.PrincipalType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** 固定基准时刻:TOTP step 单调性测试靠推进此 clock 触发(与服务共用同一注入 Clock)。 */
private val BASE_INSTANT: Instant = Instant.parse("2026-07-03T00:00:00Z")

/** 可控 Clock(@Primary 覆盖 SecurityConfig 的 systemUTC):测试推进它验证 TOTP step 单调。 */
class MutableClock(
    start: Instant,
) : Clock() {
    private val ref = AtomicReference(start)

    fun set(instant: Instant) {
        ref.set(instant)
    }

    fun advance(duration: Duration) {
        ref.updateAndGet { it.plus(duration) }
    }

    override fun instant(): Instant = ref.get()

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this
}

/**
 * admin 鉴权 + 强 TOTP 验收(spec §5.4):未启用密码即入、enroll/confirm、CAS 消费防重放、step 单调、统一 401。
 */
@AutoConfigureMockMvc
class AdminTotpFlowTest : AbstractIntegrationTest() {
    @TestConfiguration
    class MutableClockConfig {
        @Bean
        @Primary
        fun mutableClock(): MutableClock = MutableClock(BASE_INSTANT)
    }

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Autowired
    lateinit var passwordEncoder: PasswordEncoder

    @Autowired
    lateinit var crypto: TotpSecretCrypto

    @Autowired
    lateinit var codec: AccessTokenCodec

    @Autowired
    lateinit var clock: MutableClock

    @BeforeEach
    fun resetClock() {
        clock.set(BASE_INSTANT)
    }

    @Test
    fun `admin without totp logs in with password only`() {
        insertAdmin("plainadmin")
        adminLogin("plainadmin", PASSWORD)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.accessToken").isNotEmpty)
    }

    @Test
    fun `enroll then confirm enables totp and overwrites unconfirmed secret`() {
        val id = insertAdmin("enrollee")
        val token = adminToken(id)
        enroll(token) // 第一次 enroll(未确认)
        val secret = secretOf(enroll(token)) // 重复 enroll 覆盖未确认 secret,以最后一次为准
        assertThat(totpEnabledOf(id)).isFalse()
        confirm(token, codeNow(secret)).andExpect(status().isNoContent)
        assertThat(totpEnabledOf(id)).isTrue()
    }

    @Test
    fun `enrolled admin requires a fresh valid totp code each login`() {
        insertAdminWithTotp("root")
        // 缺 code / 错 code → 统一 401
        adminLogin("root", PASSWORD).andExpect(status().isUnauthorized)
        adminLogin("root", PASSWORD, wrongCode(SEED)).andExpect(status().isUnauthorized)
        // 正确 code → 对(消费当前 step)
        val code0 = codeNow(SEED)
        adminLogin("root", PASSWORD, code0).andExpect(status().isOk)
        // 同 code 二次 → 401(CAS 重放拒绝)
        adminLogin("root", PASSWORD, code0).andExpect(status().isUnauthorized)
        // 时钟推进一个 step:旧 code → 401(单调),新 code → 对
        clock.advance(Duration.ofSeconds(Totp.STEP_SECONDS))
        adminLogin("root", PASSWORD, code0).andExpect(status().isUnauthorized)
        adminLogin("root", PASSWORD, codeNow(SEED)).andExpect(status().isOk)
    }

    @Test
    fun `totp failures count toward admin lockout`() {
        insertAdminWithTotp("locky")
        repeat(MAX_FAILURES) { adminLogin("locky", PASSWORD, wrongCode(SEED)).andExpect(status().isUnauthorized) }
        // 锁定期内即使正确密码 + 正确 code 也 401
        adminLogin("locky", PASSWORD, codeNow(SEED)).andExpect(status().isUnauthorized)
    }

    @Test
    fun `customer token cannot enroll admin totp`() {
        val token = codec.issue(1L, PrincipalType.CUSTOMER, UUID.randomUUID())
        mockMvc
            .perform(post("/v1/admin/auth/totp/enroll").header(AUTH, "Bearer $token"))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.type").value("/problems/forbidden"))
    }

    @Test
    fun `otpauth uri percent encodes tricky usernames`() {
        val id = insertAdmin("weird user:name@host")
        val uri = enroll(adminToken(id))
        assertThat(uri).doesNotContain(" ") // 无裸空格
        assertThat(secretParamOf(uri)).doesNotContain("=") // base32 去 padding
        assertThat(uri).contains("gabon-admin%3Aweird%20user%3Aname%40host")
    }

    private fun insertAdmin(username: String): Long =
        dsl
            .insertInto(ADMIN_USER)
            .set(ADMIN_USER.USERNAME, username)
            .set(ADMIN_USER.USERNAME_CANONICAL, UsernameCanonicalizer.canonicalize(username))
            .set(ADMIN_USER.PASSWORD_HASH, passwordEncoder.encode(PASSWORD))
            .returningResult(ADMIN_USER.ID)
            .fetchOne()!!
            .value1()!!

    /** 直接以已知 secret 启用 TOTP(绕过 enroll/confirm),供强制 TOTP / 锁定用例控制 code。 */
    private fun insertAdminWithTotp(username: String): Long {
        val id = insertAdmin(username)
        dsl
            .update(ADMIN_USER)
            .set(ADMIN_USER.TOTP_SECRET_ENC, crypto.encrypt(id, SEED))
            .set(ADMIN_USER.TOTP_KEY_VERSION, crypto.keyVersion)
            .set(ADMIN_USER.TOTP_ENABLED, true)
            .where(ADMIN_USER.ID.eq(id))
            .execute()
        return id
    }

    private fun adminToken(id: Long): String = codec.issue(id, PrincipalType.ADMIN, UUID.randomUUID())

    private fun adminLogin(
        username: String,
        password: String,
        totpCode: String? = null,
    ): ResultActions =
        mockMvc.perform(
            post("/v1/admin/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(AdminLoginRequest(username, password, totpCode))),
        )

    private fun enroll(token: String): String {
        val body =
            mockMvc
                .perform(post("/v1/admin/auth/totp/enroll").header(AUTH, "Bearer $token"))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        return objectMapper.readValue(body, TotpEnrollResponse::class.java).otpauthUri
    }

    private fun confirm(
        token: String,
        code: String,
    ): ResultActions =
        mockMvc.perform(
            post("/v1/admin/auth/totp/confirm")
                .header(AUTH, "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(TotpConfirmRequest(code))),
        )

    private fun totpEnabledOf(id: Long): Boolean =
        dsl
            .select(ADMIN_USER.TOTP_ENABLED)
            .from(ADMIN_USER)
            .where(ADMIN_USER.ID.eq(id))
            .fetchOne()!!
            .value1()!!

    private fun codeNow(secret: ByteArray): String = Totp.codeForStep(secret, Totp.stepOf(clock.instant().epochSecond), Totp.PROD_DIGITS)

    /** 生成一个绝不落在当前窗口 [-1,0,+1] 任一 step 的 6 位码(测试确定性,不靠概率)。 */
    private fun wrongCode(secret: ByteArray): String {
        val step = Totp.stepOf(clock.instant().epochSecond)
        val valid = (-Totp.WINDOW..Totp.WINDOW).map { Totp.codeForStep(secret, step + it, Totp.PROD_DIGITS) }.toSet()
        return (0 until CODE_SPACE)
            .asSequence()
            .map { it.toString().padStart(Totp.PROD_DIGITS, '0') }
            .first { it !in valid }
    }

    private fun secretParamOf(uri: String): String = Regex("secret=([^&]+)").find(uri)!!.groupValues[1]

    private fun secretOf(uri: String): ByteArray = base32Decode(secretParamOf(uri))

    /** RFC 4648 base32 解码(测试侧,还原 authenticator 的解码路径;prod Base32 只需 encode)。 */
    private fun base32Decode(encoded: String): ByteArray {
        var buffer = 0L
        var bits = 0
        val out = ArrayList<Byte>()
        for (c in encoded.trimEnd('=')) {
            val v = BASE32_ALPHABET.indexOf(c)
            require(v >= 0) { "bad base32 char: $c" }
            buffer = (buffer shl BASE32_BITS) or v.toLong()
            bits += BASE32_BITS
            if (bits >= BYTE_BITS) {
                bits -= BYTE_BITS
                out.add(((buffer shr bits) and BYTE_MASK).toByte())
            }
        }
        return out.toByteArray()
    }

    companion object {
        private const val AUTH = "Authorization"
        private const val PASSWORD = "password123"
        private const val MAX_FAILURES = 5
        private const val CODE_SPACE = 1_000_000
        private const val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        private const val BASE32_BITS = 5
        private const val BYTE_BITS = 8
        private const val BYTE_MASK = 0xffL

        /** 20 字节任意 secret(RFC 6238 seed),测试直接持有明文以生成 code。 */
        private val SEED = "12345678901234567890".toByteArray()
    }
}
