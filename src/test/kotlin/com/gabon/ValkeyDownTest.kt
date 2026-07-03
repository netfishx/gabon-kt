package com.gabon

import com.gabon.identity.internal.web.LoginRequest
import com.gabon.platform.security.AccessTokenCodec
import com.gabon.platform.security.PrincipalType
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * fail-closed 验收(spec §5.2):Valkey 不可用时,带票请求(过滤器黑名单查询)与登录(计数器)都 503。
 * 独立坏端口上下文——redis 指向 127.0.0.1:1(连接必拒);PG 自起独立容器(不复用 AbstractIntegrationTest 的
 * redis 属性,故不继承它;报告注明"择自起 PG"这一简化路径)。
 */
@SpringBootTest(properties = ["spring.data.redis.host=127.0.0.1", "spring.data.redis.port=1"])
@AutoConfigureMockMvc
class ValkeyDownTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var codec: AccessTokenCodec

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Test
    fun `bearer request fails closed with 503 when valkey is down`() {
        val token = codec.issue(1L, PrincipalType.CUSTOMER, UUID.randomUUID())
        mockMvc
            .perform(get("/v1/auth/me").header("Authorization", "Bearer $token"))
            .andExpect(status().isServiceUnavailable)
    }

    @Test
    fun `login fails closed with 503 when valkey is down`() {
        val body = objectMapper.writeValueAsString(LoginRequest("someone", "password123"))
        mockMvc
            .perform(post("/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isServiceUnavailable)
    }

    companion object {
        @JvmStatic
        private val pg: PostgreSQLContainer =
            PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine")).apply { start() }

        // 测试固定 32 字节全零密钥(同 AbstractIntegrationTest);redis 属性由 @SpringBootTest 注坏端口。
        private const val TEST_KEY_BASE64 = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { pg.jdbcUrl }
            registry.add("spring.datasource.username") { pg.username }
            registry.add("spring.datasource.password") { pg.password }
            registry.add("gabon.security.jwt.secret-base64") { TEST_KEY_BASE64 }
            registry.add("gabon.security.totp.kek-base64") { TEST_KEY_BASE64 }
        }
    }
}
