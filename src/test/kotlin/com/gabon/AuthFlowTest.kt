package com.gabon

import com.gabon.identity.internal.web.ChangePasswordRequest
import com.gabon.identity.internal.web.LoginRequest
import com.gabon.identity.internal.web.RefreshRequest
import com.gabon.identity.internal.web.RegisterRequest
import com.gabon.identity.internal.web.TokenPairResponse
import com.gabon.jooq.tables.references.CUSTOMER
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.ObjectMapper

/**
 * C 端鉴权全链路验收(spec §5.1/§5.3):注册/登录/me/logout/改密 + 锁定/IP 限流 + 防枚举统一 401。
 */
@AutoConfigureMockMvc
class AuthFlowTest : AbstractIntegrationTest() {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Test
    fun `register then login then me returns the display username`() {
        register("Alice")
        val pair = login("Alice", PASSWORD).andExpect(status().isOk).let(::readPair)
        mockMvc
            .perform(get("/v1/auth/me").header(AUTH, "Bearer ${pair.accessToken}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.username").value("Alice"))
    }

    @Test
    fun `duplicate canonical registration is rejected as conflict`() {
        register("bob")
        postJson("/v1/auth/register", RegisterRequest("BOB", PASSWORD, null))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.type").value("/problems/username-taken"))
    }

    @Test
    fun `unknown user and wrong password yield byte-identical 401`() {
        register("carol")
        val wrong = login("carol", "wrongpassword").andExpect(status().isUnauthorized).andReturn()
        val ghost = login("nobody", "whatever").andExpect(status().isUnauthorized).andReturn()
        // 防枚举核心:两个不同失败原因的响应体逐字节相同
        assertThat(wrong.response.contentAsString).isEqualTo(ghost.response.contentAsString)
        assertThat(wrong.response.contentAsString).contains("/problems/invalid-credentials")
    }

    @Test
    fun `account locks after five failures and correct password is rejected while locked`() {
        register("dave")
        repeat(MAX_FAILURES) { login("dave", "wrongpassword").andExpect(status().isUnauthorized) }
        login("dave", PASSWORD).andExpect(status().isUnauthorized) // 锁定期内正确密码也 401
        redisConnectionFactory.connection.use { it.serverCommands().flushDb() } // 等价锁过期
        login("dave", PASSWORD).andExpect(status().isOk)
    }

    @Test
    fun `ip rate limit trips on the attempt past the window limit`() {
        repeat(IP_LIMIT) { i -> login("user$i", "whatever").andExpect(status().isUnauthorized) }
        login("userLast", "whatever").andExpect(status().isTooManyRequests)
    }

    @Test
    fun `logout revokes the family and blacklists the access ticket`() {
        val pair = register("erin")
        mockMvc
            .perform(post("/v1/auth/logout").header(AUTH, "Bearer ${pair.accessToken}"))
            .andExpect(status().isNoContent)
        postJson("/v1/auth/refresh", RefreshRequest(pair.refreshToken)).andExpect(status().isUnauthorized)
        mockMvc
            .perform(get("/v1/auth/me").header(AUTH, "Bearer ${pair.accessToken}"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `change password revokes sessions and only the new password works`() {
        val pair = register("frank")
        mockMvc
            .perform(
                post("/v1/auth/password")
                    .header(AUTH, "Bearer ${pair.accessToken}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(ChangePasswordRequest(PASSWORD, NEW_PASSWORD))),
            ).andExpect(status().isNoContent)
        postJson("/v1/auth/refresh", RefreshRequest(pair.refreshToken)).andExpect(status().isUnauthorized)
        mockMvc
            .perform(get("/v1/auth/me").header(AUTH, "Bearer ${pair.accessToken}"))
            .andExpect(status().isUnauthorized)
        login("frank", NEW_PASSWORD).andExpect(status().isOk)
    }

    @Test
    fun `invalid request bodies fail validation with 400`() {
        // password 长度 <8
        postJson("/v1/auth/register", RegisterRequest("grace", "short", null))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.type").value("/problems/validation"))
        // 邀请码提供但无效
        postJson("/v1/auth/register", RegisterRequest("heidi", PASSWORD, "ZZZZZZZZZZ"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.type").value("/problems/validation"))
    }

    @Test
    fun `valid invite links the inviter and generated codes match the alphabet`() {
        register("ivan")
        val inviteCode = inviteCodeOf("ivan")
        assertThat(inviteCode).matches("[A-Z2-7]{10}")
        register("judy", invite = inviteCode)
        assertThat(invitedByOf("judy")).isEqualTo(idOf("ivan"))
    }

    private fun register(
        username: String,
        password: String = PASSWORD,
        invite: String? = null,
    ): TokenPairResponse = readPair(postJson("/v1/auth/register", RegisterRequest(username, password, invite)).andExpect(status().isOk))

    private fun login(
        username: String,
        password: String,
    ): ResultActions = postJson("/v1/auth/login", LoginRequest(username, password))

    private fun postJson(
        path: String,
        body: Any,
    ): ResultActions =
        mockMvc.perform(
            post(path).contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)),
        )

    private fun readPair(actions: ResultActions): TokenPairResponse =
        objectMapper.readValue(actions.andReturn().response.contentAsString, TokenPairResponse::class.java)

    private fun inviteCodeOf(canonical: String): String =
        dsl
            .select(CUSTOMER.INVITE_CODE)
            .from(CUSTOMER)
            .where(CUSTOMER.USERNAME_CANONICAL.eq(canonical))
            .fetchOne()!!
            .value1()!!

    private fun idOf(canonical: String): Long =
        dsl
            .select(CUSTOMER.ID)
            .from(CUSTOMER)
            .where(CUSTOMER.USERNAME_CANONICAL.eq(canonical))
            .fetchOne()!!
            .value1()!!

    private fun invitedByOf(canonical: String): Long? =
        dsl
            .select(CUSTOMER.INVITED_BY)
            .from(CUSTOMER)
            .where(CUSTOMER.USERNAME_CANONICAL.eq(canonical))
            .fetchOne()!!
            .value1()

    companion object {
        private const val AUTH = "Authorization"
        private const val PASSWORD = "password123"
        private const val NEW_PASSWORD = "newpassword456"
        private const val MAX_FAILURES = 5
        private const val IP_LIMIT = 30
    }
}
