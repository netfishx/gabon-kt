package com.gabon

import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

private const val VALKEY_PORT = 6379

@SpringBootTest
abstract class AbstractIntegrationTest {
    @Autowired
    lateinit var dsl: DSLContext

    @Autowired
    lateinit var redisConnectionFactory: RedisConnectionFactory

    @BeforeEach
    fun clean() {
        dsl.execute(
            "truncate ledger_entry, ledger_txn, outbox, inbox, account, refresh_token, admin_user, " +
                "customer restart identity cascade",
        )
        // 单例容器跨测试复用，jti/计数键会残留——每测试前 flush 整库
        redisConnectionFactory.connection.use { it.serverCommands().flushDb() }
    }

    companion object {
        // 单例容器：PG 与 Valkey 整个测试 JVM 复用（不 stop，JVM 退出/Ryuk 回收）
        @JvmStatic
        private val pg: PostgreSQLContainer =
            PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine")).apply { start() }
        // 表由 Boot 启动时自动迁移（spring-boot-starter-flyway），不再手动 migrate

        // Valkey：第三批（jti 黑名单/限流）消费；本批 classpath 引入 redis 客户端 + 每测试 flush
        @JvmStatic
        private val valkey: GenericContainer<*> =
            GenericContainer(DockerImageName.parse("valkey/valkey:9.1-alpine"))
                .withExposedPorts(VALKEY_PORT)
                .apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun containers(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { pg.jdbcUrl }
            registry.add("spring.datasource.username") { pg.username }
            registry.add("spring.datasource.password") { pg.password }
            registry.add("spring.data.redis.host") { valkey.host }
            registry.add("spring.data.redis.port") { valkey.getMappedPort(VALKEY_PORT) }
            // 测试固定 32 字节全零密钥；prod 走文件注入（gabon.security.jwt.secret-file）。
            // Task 2/3 的 @ConfigurationProperties 才消费，现在注册无害。
            registry.add("gabon.security.jwt.secret-base64") { "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=" }
            registry.add("gabon.security.totp.kek-base64") { "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=" }
        }
    }
}
