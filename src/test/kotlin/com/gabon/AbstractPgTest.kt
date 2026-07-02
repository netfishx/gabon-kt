package com.gabon

import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

@SpringBootTest
abstract class AbstractPgTest {
    @Autowired
    lateinit var dsl: DSLContext

    @BeforeEach
    fun clean() {
        dsl.execute("truncate ledger_entry, ledger_txn, outbox, inbox, account restart identity cascade")
    }

    companion object {
        // 单例容器：整个测试 JVM 复用一个 PG（不 stop，JVM 退出/Ryuk 回收）
        @JvmStatic
        private val pg: PostgreSQLContainer =
            PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine")).apply { start() }
        // 表由 Boot 启动时自动迁移（spring-boot-starter-flyway），不再手动 migrate

        @JvmStatic
        @DynamicPropertySource
        fun datasource(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { pg.jdbcUrl }
            registry.add("spring.datasource.username") { pg.username }
            registry.add("spring.datasource.password") { pg.password }
        }
    }
}
