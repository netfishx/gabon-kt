package com.gabon

import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder

/**
 * prod 配置契约探针(spec §2.3):GABON_DB_* env 缺失时 prod profile 必须起不来,
 * 不得静默用 owner 凭据跑业务(owner 凭据本就不在 app 运行时配置,此处钉"必然崩")。
 * 机制注记:Boot 对 @ConfigurationProperties 的占位符是非严格解析——未解析的
 * ${GABON_DB_URL} 原样透传进 Hikari,被 url 格式校验拒(消息不点名 env;
 * 若 Boot 未来改严格解析,此断言会红,届时改回占位符消息即可)。
 * 排查提示:生产启动失败先核对 GABON_DB_URL/APP_PASSWORD/OWNER_USER/OWNER_PASSWORD 四件套。
 * 密钥属性注入测试值 + 不起 web:把探针收窄到 DB 配置路径。
 */
class ProdProfileContractTest {
    @Test
    fun `prod profile without credential env fails to start`() {
        assertThatThrownBy {
            SpringApplicationBuilder(GabonApplication::class.java)
                .web(WebApplicationType.NONE)
                .profiles("prod")
                .properties(
                    "gabon.security.jwt.secret-base64=$TEST_KEY",
                    "gabon.security.totp.kek-base64=$TEST_KEY",
                ).run()
        }.hasStackTraceContaining("'url' must start with \"jdbc\"")
    }

    companion object {
        /** 32 字节全零测试密钥,与 AbstractIntegrationTest 同值;仅为绕开密钥 fail fast,非契约对象。 */
        private const val TEST_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
    }
}
