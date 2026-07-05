package com.gabon

import org.jooq.CloseableDSLContext
import org.jooq.DSLContext
import org.jooq.impl.DSL
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

    /** owner 连接:truncate(受限 role 无 TRUNCATE 权限)与越权种子专用;业务路径一律走 runtime dsl。 */
    protected val ownerDsl: DSLContext
        get() = ownerDslStatic

    @BeforeEach
    fun clean() {
        ownerDsl.execute(
            "truncate ledger_entry, ledger_txn, outbox, inbox, account, refresh_token, admin_user, " +
                "customer, recharge_order, recharge_package, withdraw_order restart identity cascade",
        )
        // 单例容器跨测试复用，jti/计数键会残留——每测试前 flush 整库
        redisConnectionFactory.connection.use { it.serverCommands().flushDb() }
    }

    companion object {
        // 单例容器：PG 与 Valkey 整个测试 JVM 复用（不 stop，JVM 退出/Ryuk 回收）
        @JvmStatic
        private val pg: PostgreSQLContainer =
            PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine")).apply {
                start()
                // 预建 app role(spec §2.3 生产推荐二选一之"预先创建");V4 只授权
                createConnection("").use { it.createStatement().execute("create role $APP_ROLE login password '$APP_PASSWORD'") }
            }
        // 表由 Boot 启动时自动迁移（spring-boot-starter-flyway），不再手动 migrate

        // Valkey：第三批（jti 黑名单/限流）消费；本批 classpath 引入 redis 客户端 + 每测试 flush
        @JvmStatic
        private val valkey: GenericContainer<*> =
            GenericContainer(DockerImageName.parse("valkey/valkey:9.1-alpine"))
                .withExposedPorts(VALKEY_PORT)
                .apply { start() }

        private const val APP_ROLE = "gabon_app"
        private const val APP_PASSWORD = "test"

        /**
         * owner 单连接(CloseableDSLContext,非池化):全 JVM 生命周期供 truncate/越权种子使用,
         * 有意不 close(JVM 退出回收,与单例容器同寿);中途失效会连锁炸全部测试——
         * 若出现,先查 PG 容器健康,再考虑挂 shutdown hook/改池化。
         */
        @JvmStatic
        private val ownerDslStatic: CloseableDSLContext by lazy { DSL.using(pg.jdbcUrl, pg.username, pg.password) }

        @JvmStatic
        @DynamicPropertySource
        fun containers(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { pg.jdbcUrl }
            registry.add("spring.datasource.username") { APP_ROLE }
            registry.add("spring.datasource.password") { APP_PASSWORD }
            // Flyway 显式走 owner,不与 datasource 混用(spec §2.3 连接拓扑)
            registry.add("spring.flyway.url") { pg.jdbcUrl }
            registry.add("spring.flyway.user") { pg.username }
            registry.add("spring.flyway.password") { pg.password }
            registry.add("spring.data.redis.host") { valkey.host }
            registry.add("spring.data.redis.port") { valkey.getMappedPort(VALKEY_PORT) }
            // 测试固定 32 字节全零密钥；prod 走文件注入（gabon.security.jwt.secret-file）。
            // Task 2/3 的 @ConfigurationProperties 才消费，现在注册无害。
            registry.add("gabon.security.jwt.secret-base64") { "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=" }
            registry.add("gabon.security.totp.kek-base64") { "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=" }
        }
    }
}
