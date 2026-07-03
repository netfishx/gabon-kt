package com.gabon

import com.gabon.platform.security.AccessTokenCodec
import com.gabon.platform.security.JtiBlacklist
import com.gabon.platform.security.JwtAuthFilter
import com.gabon.platform.security.JwtProps
import com.gabon.platform.security.PrincipalType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.dao.QueryTimeoutException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** 默认拒绝链 + JWT 过滤器验收(spec §5.2/§5.6):401 problem、公开路由、吊销短路、fail-closed。 */
@AutoConfigureMockMvc
class SecurityChainTest : AbstractIntegrationTest() {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var codec: AccessTokenCodec

    @Autowired
    lateinit var blacklist: JtiBlacklist

    @Autowired
    lateinit var jwtProps: JwtProps

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Test
    fun `unticketed request on any route gets 401 unauthenticated problem`() {
        mockMvc
            .perform(get("/v1/whatever"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.type").value("/problems/unauthenticated"))
    }

    @Test
    fun `actuator health is publicly reachable`() {
        mockMvc
            .perform(get("/actuator/health"))
            .andExpect(status().isOk)
    }

    @Test
    fun `ticketed request passes the chain to dispatch`() {
        val token = codec.issue(1L, PrincipalType.CUSTOMER, UUID.randomUUID())
        mockMvc
            .perform(get("/v1/whatever").header("Authorization", "Bearer $token"))
            .andExpect(status().isNotFound) // 过链无路由:认证生效,404 而非 401
    }

    @Test
    fun `revoked jti is rejected on protected and public routes alike`() {
        val token = codec.issue(1L, PrincipalType.CUSTOMER, UUID.randomUUID())
        val jti = codec.verify(token)!!.jti
        blacklist.revoke(jti, Duration.ofMinutes(15))
        mockMvc
            .perform(get("/v1/whatever").header("Authorization", "Bearer $token"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.type").value("/problems/unauthenticated"))
        // 公开路由同样不放行吊销票:过滤器先于授权短路
        mockMvc
            .perform(get("/actuator/health").header("Authorization", "Bearer $token"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.type").value("/problems/unauthenticated"))
    }

    @Test
    fun `tampered ticket yields 401 not 500`() {
        val token = codec.issue(1L, PrincipalType.CUSTOMER, UUID.randomUUID())
        mockMvc
            .perform(get("/v1/whatever").header("Authorization", "Bearer ${token}x"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.type").value("/problems/unauthenticated"))
    }

    @Test
    fun `customer ticket on admin route gets 403 forbidden problem`() {
        val token = codec.issue(1L, PrincipalType.CUSTOMER, UUID.randomUUID())
        mockMvc
            .perform(get("/v1/admin/whatever").header("Authorization", "Bearer $token"))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.type").value("/problems/forbidden"))
    }

    @Test
    fun `admin ticket passes the admin gate to dispatch`() {
        val token = codec.issue(2L, PrincipalType.ADMIN, UUID.randomUUID())
        mockMvc
            .perform(get("/v1/admin/whatever").header("Authorization", "Bearer $token"))
            .andExpect(status().isNotFound) // 过门禁无路由:404 而非 403
    }

    @Test
    fun `valkey outage fails closed with 503 for ticketed requests`() {
        // Lettuce 命令超时形态:Spring 翻译为 QueryTimeoutException(非 Redis 专属异常),
        // 走真实 JtiBlacklist 证明源头包装 + 过滤器 fail-closed 全覆盖
        val timingOutTemplate =
            object : StringRedisTemplate() {
                override fun hasKey(key: String): Boolean = throw QueryTimeoutException("simulated command timeout")
            }
        val filter = JwtAuthFilter(codec, JtiBlacklist(timingOutTemplate), objectMapper)
        val token = codec.issue(1L, PrincipalType.CUSTOMER, UUID.randomUUID())
        val request = MockHttpServletRequest("GET", "/v1/whatever")
        request.addHeader("Authorization", "Bearer $token")
        val response = MockHttpServletResponse()
        filter.doFilter(request, response, MockFilterChain())
        assertThat(response.status).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value())
        assertThat(response.contentAsString).contains("/problems/auth-store-unavailable")
    }

    @Test
    fun `codec round trips claims and rejects expired tokens`() {
        val sid = UUID.randomUUID()
        val token = codec.issue(42L, PrincipalType.ADMIN, sid)
        val principal = codec.verify(token)!!
        assertThat(principal.id).isEqualTo(42L)
        assertThat(principal.type).isEqualTo(PrincipalType.ADMIN)
        assertThat(principal.sid).isEqualTo(sid)
        assertThat(principal.expiresAt).isAfter(Instant.now())
        val jti = UUID.fromString(principal.jti)
        assertThat(jti.version()).isEqualTo(7)
        assertThat(jti.variant()).isEqualTo(2)
        // 过期票:用回拨 1 小时的 Clock 签发(exp 已过),真实时钟校验必须失败
        val pastClock = Clock.fixed(Instant.now().minus(Duration.ofHours(1)), ZoneOffset.UTC)
        val expired = AccessTokenCodec(jwtProps, pastClock).issue(42L, PrincipalType.ADMIN, sid)
        assertThat(codec.verify(expired)).isNull()
    }

    @Test
    fun `jwt props demand exactly one secret source`() {
        assertThatIllegalArgumentException()
            .isThrownBy { JwtProps().secretBytes() }
        assertThatIllegalArgumentException()
            .isThrownBy { JwtProps(secretBase64 = "AAAA", secretFile = "/run/secrets/jwt").secretBytes() }
        assertThat(JwtProps(secretBase64 = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=").secretBytes())
            .hasSize(32)
    }
}
