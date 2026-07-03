# 身份域鉴权完全体(第三批)实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers-extended-cc:subagent-driven-development (recommended) or superpowers-extended-cc:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 落地 spec §8 第三批——platform.security(默认拒绝 + JWT + jti fail-closed)、platform.web(problem+json)、identity 完整鉴权(token 生命周期、TOTP、登录保护、/v1 端点)与全套测试。

**Architecture:** platform 层提供安全基座(过滤链、JWT 编解码、jti 黑名单、`PublicRoutesContributor` 契约、problem 模型),identity 上下文在 internal 实现全部业务(仓储/服务/控制器),经 contributor 向 platform 声明公开路由(identity→platform,方向合法)。三处并发敏感点(refresh 旋转、TOTP 接受、登录计数)全部 CAS/原子操作,禁读-改-写。安全语义为硬门禁:统一 401 防枚举、Valkey fail-closed 503、TOTP 同 step 拒绝。

**Tech Stack:** Spring Boot 4.1(Security 7 / Data Redis Lettuce / Actuator)+ jjwt 0.12.x + JDK crypto(TOTP/AES-GCM 自实现,零额外密码学依赖)。

**Spec:** `docs/superpowers/specs/2026-07-02-module-boundaries-identity-design.md` §5.2-§5.6/§6/§7;衔接清单:任务 #11 描述 9 条(本计划全覆盖)。

**User decisions (already made):**
- Valkey 不可用 → **fail-closed 503**,Valkey 进 readiness(actuator + data-redis 自动)
- 鉴权错误对外统一 **401 `/problems/invalid-credentials`**(不存在/密码错/disabled/locked/TOTP 错),内部原因只进日志;429 仅 IP/全局限流
- 密码哈希 `DelegatingPasswordEncoder`(bcrypt 默认)
- TOTP:JDK Mac 自实现 RFC 6238,生产 30s/6位/HMAC-SHA1/窗口 [-1,0,+1];算法函数 `digits` 参数化,RFC Appendix B 向量按 **8 位**验算法;接受必须 CAS(`totp_last_used_step` 单调递增,命中 1 行才通过)
- TOTP secret:AES-256-GCM,**AAD = `admin_user:{id}:totp_secret:{key_version}`**,IV 12B 随机,tag 128b,KEK 注入不进 git
- refresh:旋转原子 UPDATE 抢占(0 行且存在 = 重放 → 吊销 family);access JWT 带 `sid`(= family_id),logout 凭 sid 吊销
- username 规范化 `trim + lowercase(Locale.ROOT)`,唯一约束在 canonical 列
- 2FA 仅 admin;C 端不做
- identity 三表只许 `identity.internal..` 访问(表所有权);platform.security 不得 import identity

**计划注记(执行前请知悉):**
1. **API 核实门禁(每任务硬性)**:Boot 4.1 = Spring Framework 7 / Security 7,jjwt 0.12——计划中的参考代码**语义固定、API 形态以官方文档/编译结果为准**(CLAUDE.md"严禁依赖预训练数据")。凡 Security DSL、jjwt builder/parser、ProblemDetail、RedisTemplate、`RedisConnectionFactory` flush 的调用形态,实现前用 Context7/WebFetch 查当前版本文档或以编译器为准修正;**安全语义(CAS/fail-closed/统一 401/AAD)一个字不许变**。
2. **problem 类型两处计划级新增**(spec §6 未枚举,不违背既有决策):`/problems/username-taken`(409,注册重名——注册本身必然暴露存在性,业界标准取舍)、`/problems/unauthenticated`(401,未带票访问受保护路由)与 `/problems/forbidden`(403)。统一 401 invalid-credentials 仍然覆盖全部登录失败场景。
3. **WalletProps 定案**:保留——消费方是子项目 2 充值域(汇率换算),KDoc 注明;不删除(第一批质量审查 Minor 收口)。
4. **jti 用 UUIDv7**(spec §5.2):JDK 无内置,按 RFC 9562 自实现 ~15 行(48-bit unix ms + version/variant + random),带单测。
5. **base32**(otpauth URI 需要):JDK 无内置,RFC 4648 自实现 ~20 行,带单测;不为此引 commons-codec。
6. **限流/锁定为固定窗口(spec §5.3 已同步回填)**:计数一律 Lua 脚本**原子 INCR+PEXPIRE**(杜绝 INCR 成功后 EXPIRE 失败留下永久 key → 永久锁定/限流);IP 限流由"滑动窗口"改定为固定窗口——边界突发最坏 2×limit,对登录保护足够,换实现最简。
7. **黑名单票立即拒**:携带已吊销 jti 的请求在过滤器层直接 401 并短路,**公开路由也不放行**(吊销票再现 = 强可疑信号)。
8. **黑名单 TTL = 票的剩余有效期**(spec §5.2 原文;不是完整 access TTL):`GabonPrincipal` 携带 `expiresAt`,剩余 ≤0 不入黑名单。
9. **otpauth URI 必须编码**:label percent-encoding、secret Base32 去 padding(otpauth 惯例),用户名含空格/冒号/@ 也生成合法 URI。

---

## File Structure(第三批终态,全部新文件在 identity/internal 与 platform 下)

```
src/main/kotlin/com/gabon/
  platform/web/ProblemType.kt            problem 类型注册(稳定 URI)+ ProblemException
  platform/web/GlobalExceptionHandler.kt @RestControllerAdvice(统一渲染、fail-closed 503、500 不泄内部)
  platform/security/JwtProps.kt          @ConfigurationProperties(secret 来源二选一 fail-fast、TTL)
  platform/security/GabonPrincipal.kt    principal 模型 + PrincipalType(CUSTOMER=1/ADMIN=2)
  platform/security/UuidV7.kt            RFC 9562 v7 生成器
  platform/security/AccessTokenCodec.kt  jjwt 签发/校验(claims: sub/typ/roles/jti/sid/iat/exp)
  platform/security/JtiBlacklist.kt      Valkey 黑名单(异常上抛=fail-closed)
  platform/security/JwtAuthFilter.kt     bearer 解析→黑名单→SecurityContext;Valkey 异常→503
  platform/security/PublicRoutesContributor.kt  fun interface 契约
  platform/security/SecurityConfig.kt    默认拒绝链 + contributor 汇总 + /v1/admin/** 要 ADMIN
  identity/internal/UsernameCanonicalizer.kt
  identity/internal/Totp.kt              RFC 6238 算法(digits 参数化)+ Base32
  identity/internal/TotpSecretCrypto.kt  AES-256-GCM + AAD
  identity/internal/TotpVerifier.kt      窗口 ±1 + 常量时间比较 + CAS 消费
  identity/internal/CustomerRepository.kt / AdminUserRepository.kt / RefreshTokenRepository.kt
  identity/internal/TokenService.kt      token 对签发/旋转/重放吊销/登出/全吊销
  identity/internal/LoginProtection.kt   账号锁定 + IP 限流(Valkey 计数,异常上抛)
  identity/internal/AuthService.kt       C 端 register/login/me/changePassword
  identity/internal/AdminAuthService.kt  admin login(+TOTP)/enroll/confirm
  identity/internal/web/AuthController.kt / AdminAuthController.kt / dto.kt
  identity/internal/IdentityPublicRoutes.kt  contributor 实现
src/test/kotlin/com/gabon/
  TotpTest.kt / UuidV7Test.kt / TotpSecretCryptoTest.kt / CanonicalizerTest.kt(纯单测)
  SecurityChainTest.kt / TokenLifecycleTest.kt / AuthFlowTest.kt / AdminTotpFlowTest.kt / ValkeyDownTest.kt(集成)
```

依赖新增(仅 Task 1 动 `build.gradle.kts`):`spring-boot-starter-security`、`spring-boot-starter-data-redis`、`spring-boot-starter-actuator`、`jjwt-api/impl/jackson 0.12.x`(最新 patch 以 Maven Central 为准)。

**Verify 环境**(所有任务同,三 export 与 gradle 同一条命令):
```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-25.jdk/Contents/Home
export DOCKER_HOST=unix:///Users/ethanwang/.orbstack/run/docker.sock
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
./gradlew check
```

---

### Task 1: 依赖三件套 + Valkey flush + problem 模型

**Goal:** 安全/Redis/actuator/jjwt 依赖进场,测试基类补 Valkey flush,platform.web 的 problem 模型与全局异常处理落地。

**Files:**
- Modify: `build.gradle.kts`(依赖块 +6 行)
- Modify: `src/test/kotlin/com/gabon/AbstractIntegrationTest.kt`(clean() 补 flush;注册 `gabon.security.jwt.secret-base64` 测试键)
- Create: `src/main/kotlin/com/gabon/platform/web/ProblemType.kt`、`GlobalExceptionHandler.kt`
- Test: `src/test/kotlin/com/gabon/ProblemTypeTest.kt`

**Acceptance Criteria:**
- [ ] 依赖仅上述 6 项;jjwt 版本为 Maven Central 最新 0.12.x(查证出处留报告)
- [ ] `clean()` 每测试前 flush Valkey(替换第二批留的注释钩子)
- [ ] `ProblemType` 持有稳定 URI;响应只出 type/title/status/detail;`ProblemException.internalReason` 只进日志
- [ ] 既有 16 测试在 security/starter 进场后仍全绿(测试不走 HTTP,不受默认安全链影响)
- [ ] `./gradlew check` 全绿

**Verify:** 上方命令 → `BUILD SUCCESSFUL`,17+ 测试(16 + ProblemTypeTest)

**Steps:**

- [ ] **Step 1: build.gradle.kts 依赖**(dependencies 块内,查证 jjwt 最新 0.12.x 后替换版本号):

```kotlin
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")
```

改 build.gradle.kts 会触发 codegen 重跑(预期)。

- [ ] **Step 2: ProblemType.kt**(完整文件):

```kotlin
package com.gabon.platform.web

import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import java.net.URI

/**
 * problem 类型注册处(spec §6):type 是稳定 URI reference,enum 名不是对外契约。
 * 鉴权失败一律 INVALID_CREDENTIALS(防枚举);内部细分原因走 ProblemException.internalReason 只进日志。
 */
enum class ProblemType(
    val status: HttpStatus,
    private val uri: String,
    val title: String,
) {
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "/problems/invalid-credentials", "Invalid credentials"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "/problems/unauthenticated", "Authentication required"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "/problems/forbidden", "Access denied"),
    USERNAME_TAKEN(HttpStatus.CONFLICT, "/problems/username-taken", "Username already taken"),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "/problems/rate-limited", "Too many requests"),
    VALIDATION(HttpStatus.BAD_REQUEST, "/problems/validation", "Invalid request"),
    AUTH_STORE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "/problems/auth-store-unavailable", "Authentication store unavailable"),
    INTERNAL(HttpStatus.INTERNAL_SERVER_ERROR, "/problems/internal", "Internal error"),
    ;

    fun toProblemDetail(detail: String? = null): ProblemDetail =
        ProblemDetail.forStatus(status).also {
            it.type = URI.create(uri)
            it.title = title
            if (detail != null) it.detail = detail
        }
}

/** 业务异常:对外渲染 type 对应 problem,internalReason 只进日志(防枚举)。 */
class ProblemException(
    val type: ProblemType,
    val internalReason: String,
) : RuntimeException(internalReason)
```

- [ ] **Step 3: GlobalExceptionHandler.kt**(完整文件;`ProblemDetail` 为 Spring Framework 6+ 内置,7 上的确切 API 以文档/编译为准):

```kotlin
package com.gabon.platform.web

import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class GlobalExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(ProblemException::class)
    fun handleProblem(e: ProblemException): ResponseEntity<ProblemDetail> {
        log.info("problem={} reason={}", e.type.name, e.internalReason)
        return ResponseEntity.status(e.type.status).body(e.type.toProblemDetail())
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(e: MethodArgumentNotValidException): ResponseEntity<ProblemDetail> =
        ResponseEntity.status(ProblemType.VALIDATION.status).body(ProblemType.VALIDATION.toProblemDetail())

    /**
     * Valkey 鉴权基础设施不可用 → fail-closed 503(spec §5.2)。
     * 只认 AuthStoreUnavailableException:包装发生在 Redis 专属组件(JtiBlacklist/LoginProtection)内,
     * 超时/连接失败/系统异常全覆盖,PG 侧异常不可能进来(落兜底 500,spec §6 fail fast)。
     */
    @ExceptionHandler(AuthStoreUnavailableException::class)
    fun handleAuthStoreDown(e: AuthStoreUnavailableException): ResponseEntity<ProblemDetail> {
        log.error("auth store unavailable", e)
        return ResponseEntity
            .status(ProblemType.AUTH_STORE_UNAVAILABLE.status)
            .body(ProblemType.AUTH_STORE_UNAVAILABLE.toProblemDetail())
    }

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(e: Exception): ResponseEntity<ProblemDetail> {
        log.error("unhandled", e)
        return ResponseEntity.status(ProblemType.INTERNAL.status).body(ProblemType.INTERNAL.toProblemDetail())
    }
}
```

- [ ] **Step 4: AbstractIntegrationTest 补 flush 与测试 JWT 键**

`clean()` 里 truncate 之后(替换第二批注释钩子;`RedisConnectionFactory` 注入与 flush API 以编译为准):

```kotlin
    @Autowired
    lateinit var redisConnectionFactory: RedisConnectionFactory

    @BeforeEach
    fun clean() {
        dsl.execute(/* 现有 truncate 原样 */)
        redisConnectionFactory.connection.use { it.serverCommands().flushDb() }
    }
```

`containers()` 里追加(32 字节全零测试键的 base64;仅测试,prod 走文件注入):

```kotlin
            registry.add("gabon.security.jwt.secret-base64") { "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=" }
```

- [ ] **Step 5: ProblemTypeTest.kt**(单测:URI/status/title 渲染 + detail 缺省):

```kotlin
package com.gabon

import com.gabon.platform.web.ProblemType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.URI

class ProblemTypeTest {
    @Test
    fun `renders stable uri title and status`() {
        val pd = ProblemType.INVALID_CREDENTIALS.toProblemDetail()
        assertThat(pd.type).isEqualTo(URI.create("/problems/invalid-credentials"))
        assertThat(pd.title).isEqualTo("Invalid credentials")
        assertThat(pd.status).isEqualTo(401)
        assertThat(pd.detail).isNull()
    }
}
```

- [ ] **Step 6: check 全绿(security 进场后既有测试不受影响——它们不走 HTTP)→ Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
feat: add security deps and problem model

- Add security, data-redis, actuator and jjwt dependencies
- Flush valkey between tests replacing the batch 2 hook note
- Add problem type registry with stable URI references

Auth errors render uniform problems with internal reasons kept
in logs only, per the anti-enumeration decision in the spec.
EOF
)"
```

(所有任务提交规则同前:标题<50、body 行≤72、bullet 祈使、解释段落;本会话执行时由 Claude 追加 harness 尾注。)

---

### Task 2: platform.security 基座(默认拒绝 + JWT + jti fail-closed)

**Goal:** JWT 编解码、principal 模型、jti 黑名单(fail-closed)、过滤器、`PublicRoutesContributor` 契约与默认拒绝链落地。

**Files:**
- Create: `platform/security/{JwtProps,GabonPrincipal,UuidV7,AccessTokenCodec,JtiBlacklist,JwtAuthFilter,PublicRoutesContributor,SecurityConfig}.kt`
- Test: `src/test/kotlin/com/gabon/{UuidV7Test,SecurityChainTest}.kt`

**Acceptance Criteria:**
- [ ] 未带票访问任意路径 → 401 `/problems/unauthenticated`;actuator health 公开可达(platform 自己的 contributor)
- [ ] `AccessTokenCodec` 签发/校验往返:claims 含 sub/typ/roles/jti(UUIDv7)/sid/iat/exp(15 分钟默认,配置化);篡改/过期 token 校验失败
- [ ] 黑名单 jti 的 token → **无论目标路由是否公开一律 401**(过滤器立即拒绝短路);**Valkey 不可用时带票请求 → 503**(fail-closed,过滤器层)
- [ ] UUIDv7:version 位 = 7、variant 位 = 10、时间戳单调(单测)
- [ ] `JwtProps` secret 来源二选一,双设/双缺启动即失败(fail fast)
- [ ] `./gradlew check` 全绿

**Verify:** 同 Task 1 → `BUILD SUCCESSFUL`

**Steps:**

- [ ] **Step 1: JwtProps.kt**

```kotlin
package com.gabon.platform.security

import org.springframework.boot.context.properties.ConfigurationProperties
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Base64

/** JWT 密钥注入:prod 走 secret-file(/run/secrets,SOPS 流程),测试走 secret-base64;二选一,fail fast。 */
@ConfigurationProperties("gabon.security.jwt")
data class JwtProps(
    val secretBase64: String? = null,
    val secretFile: String? = null,
    val accessTtl: Duration = Duration.ofMinutes(15),
) {
    fun secretBytes(): ByteArray {
        require((secretBase64 != null) xor (secretFile != null)) {
            "exactly one of gabon.security.jwt.secret-base64 / secret-file must be set"
        }
        return secretBase64?.let { Base64.getDecoder().decode(it) }
            ?: Files.readAllBytes(Path.of(secretFile!!))
    }
}
```

- [ ] **Step 2: GabonPrincipal.kt + UuidV7.kt**

```kotlin
package com.gabon.platform.security

import java.util.UUID

/** 主体类型:code 与 refresh_token.principal_type 对齐(V2 DDL check (1,2))。 */
enum class PrincipalType(val code: Short, val role: String) {
    CUSTOMER(1, "ROLE_CUSTOMER"),
    ADMIN(2, "ROLE_ADMIN"),
    ;

    companion object {
        fun of(code: Short): PrincipalType = entries.first { it.code == code }
    }
}

/** 过滤器解出的已认证主体(sid = refresh family,logout 凭它吊销;expiresAt 供黑名单剩余 TTL;spec §5.2)。 */
data class GabonPrincipal(
    val id: Long,
    val type: PrincipalType,
    val sid: UUID,
    val jti: String,
    val expiresAt: java.time.Instant,
)
```

```kotlin
package com.gabon.platform.security

import java.security.SecureRandom
import java.time.Clock
import java.util.UUID

/** RFC 9562 UUIDv7:48-bit unix ms + version 7 + variant 10 + 74-bit random(spec §5.2 jti)。 */
object UuidV7 {
    private val random = SecureRandom()

    fun generate(clock: Clock): UUID {
        val ms = clock.millis()
        val randA = random.nextLong() and 0x0FFF
        val msb = (ms shl 16) or 0x7000L or randA
        val lsb = (random.nextLong() and 0x3FFFFFFFFFFFFFFFL) or Long.MIN_VALUE // variant 10
        return UUID(msb, lsb)
    }
}
```

- [ ] **Step 3: AccessTokenCodec.kt**(jjwt 0.12 API 形态以官方文档为准——`Jwts.SIG.HS256`、`verifyWith`、`parseSignedClaims` 等;语义固定):

```kotlin
package com.gabon.platform.security

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.Date
import java.util.UUID

@Component
class AccessTokenCodec(
    props: JwtProps,
    private val clock: Clock,
) {
    private val key = Keys.hmacShaKeyFor(props.secretBytes())
    private val ttl = props.accessTtl

    fun issue(principalId: Long, type: PrincipalType, sid: UUID): String {
        val now = clock.instant()
        return Jwts.builder()
            .subject(principalId.toString())
            .claim("typ", type.code.toInt())
            .claim("roles", listOf(type.role))
            .claim("sid", sid.toString())
            .id(UuidV7.generate(clock).toString())
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plus(ttl)))
            .signWith(key, Jwts.SIG.HS256)
            .compact()
    }

    /**
     * 校验失败返回 null(过滤器按未认证处理,入口点 401);不抛——无效票不是系统错误。
     * runCatching 兜住全部结构性错误(签名过但 typ 类型不对/缺 sid/未知 PrincipalType 等
     * ClassCastException/NoSuchElementException/NPE),恶意构造的畸形票绝不冒成 500。
     */
    fun verify(token: String): GabonPrincipal? =
        runCatching {
            val claims = Jwts.parser().verifyWith(key).clock { Date.from(clock.instant()) }.build()
                .parseSignedClaims(token).payload
            GabonPrincipal(
                id = claims.subject.toLong(),
                type = PrincipalType.of((claims["typ"] as Number).toShort()),
                sid = UUID.fromString(claims["sid"] as String),
                jti = claims.id,
                expiresAt = claims.expiration.toInstant(),
            )
        }.getOrNull()

    fun accessTtl() = ttl
}
```

`Clock` bean(SecurityConfig 里 `@Bean fun clock(): Clock = Clock.systemUTC()`;测试可覆盖)。

- [ ] **Step 4: JtiBlacklist.kt + JwtAuthFilter.kt**

```kotlin
package com.gabon.platform.security

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration

/** 鉴权存储(Valkey)不可用的统一包装:在 Redis 专属组件内 catch 宽基类是安全的(不可能混入 PG)。 */
class AuthStoreUnavailableException(cause: Throwable) : RuntimeException("auth store unavailable", cause)

/**
 * jti 黑名单:任何 Redis 故障形态(连接失败/系统异常/**命令超时 QueryTimeoutException**)
 * 都在源头包装为 AuthStoreUnavailableException = fail-closed(过滤器/advice 转 503;
 * 直接在外层 catch Redis 异常对会漏超时——它与 PG 超时同类,Task 2 质量审查定案)。
 */
@Component
class JtiBlacklist(private val redis: StringRedisTemplate) {
    fun revoke(jti: String, ttl: Duration) = store { redis.opsForValue().set(key(jti), "1", ttl) }

    fun isRevoked(jti: String): Boolean = store { redis.hasKey(key(jti)) }

    private fun <T> store(block: () -> T): T =
        try {
            block()
        } catch (e: DataAccessException) {
            throw AuthStoreUnavailableException(e)
        }

    private fun key(jti: String) = "auth:jti:revoked:$jti"
}
```

```kotlin
package com.gabon.platform.security

import com.gabon.platform.web.ProblemType
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.dao.DataAccessException
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.ObjectMapper

/** bearer → 校验 → jti 黑名单(fail-closed)→ SecurityContext。无效票不拦截,由默认拒绝链出 401。 */
@Component
class JwtAuthFilter(
    private val codec: AccessTokenCodec,
    private val blacklist: JtiBlacklist,
    private val objectMapper: ObjectMapper,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        val header = request.getHeader("Authorization")
        if (header == null || !header.startsWith("Bearer ")) {
            chain.doFilter(request, response)
            return
        }
        val principal = codec.verify(header.removePrefix("Bearer "))
        if (principal == null) {
            chain.doFilter(request, response)
            return
        }
        // fail-closed:JtiBlacklist 已在源头把一切 Redis 故障(含命令超时)包装为 AuthStoreUnavailableException
        val revoked =
            try {
                blacklist.isRevoked(principal.jti)
            } catch (e: AuthStoreUnavailableException) {
                logger.error("jti blacklist unavailable, failing closed", e)
                writeProblem(response, ProblemType.AUTH_STORE_UNAVAILABLE)
                return
            }
        if (revoked) {
            // 吊销票再现 = 强可疑信号:立即拒,公开路由也不放行(计划注记 7)
            writeProblem(response, ProblemType.UNAUTHENTICATED)
            return
        }
        val auth = UsernamePasswordAuthenticationToken(
            principal, null, listOf(SimpleGrantedAuthority(principal.type.role)),
        )
        SecurityContextHolder.getContext().authentication = auth
        chain.doFilter(request, response)
    }

    private fun writeProblem(response: HttpServletResponse, type: ProblemType) {
        response.status = type.status.value()
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        response.writer.write(objectMapper.writeValueAsString(type.toProblemDetail()))
    }
}
```

(ObjectMapper 的包名 Boot 4/Jackson 版本以编译为准——Boot 4 可能已迁 `tools.jackson`,不对就换回 `com.fasterxml.jackson.databind.ObjectMapper`。)

- [ ] **Step 5: PublicRoutesContributor.kt + SecurityConfig.kt**

```kotlin
package com.gabon.platform.security

/** 各上下文声明公开路由(默认拒绝的唯一放行通道;spec §5.6)。 */
fun interface PublicRoutesContributor {
    fun publicRoutes(): List<String>
}
```

```kotlin
package com.gabon.platform.security

import com.gabon.platform.web.ProblemType
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.factory.PasswordEncoderFactories
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import tools.jackson.databind.ObjectMapper
import java.time.Clock

@Configuration
@EnableWebSecurity
class SecurityConfig {
    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean
    fun passwordEncoder(): PasswordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder()

    /** platform 自己贡献 actuator 健康探针公开路由。 */
    @Bean
    fun platformPublicRoutes() = PublicRoutesContributor { listOf("/actuator/health/**", "/actuator/health") }

    @Bean
    fun filterChain(
        http: HttpSecurity,
        jwtFilter: JwtAuthFilter,
        contributors: List<PublicRoutesContributor>,
        objectMapper: ObjectMapper,
    ): SecurityFilterChain {
        val public = contributors.flatMap { it.publicRoutes() }.toTypedArray()
        http
            .csrf { it.disable() }
            .formLogin { it.disable() }
            .httpBasic { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                it.requestMatchers(*public).permitAll()
                it.requestMatchers("/v1/admin/**").hasRole("ADMIN")
                it.anyRequest().authenticated()
            }
            .exceptionHandling {
                it.authenticationEntryPoint { _, response, _ ->
                    response.status = ProblemType.UNAUTHENTICATED.status.value()
                    response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
                    response.writer.write(objectMapper.writeValueAsString(ProblemType.UNAUTHENTICATED.toProblemDetail()))
                }
                it.accessDeniedHandler { _, response, _ ->
                    response.status = ProblemType.FORBIDDEN.status.value()
                    response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
                    response.writer.write(objectMapper.writeValueAsString(ProblemType.FORBIDDEN.toProblemDetail()))
                }
            }
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter::class.java)
        return http.build()
    }
}
```

(Security 7 DSL 以官方文档为准;`@EnableConfigurationProperties(JwtProps::class)` 或依赖根包 `@ConfigurationPropertiesScan`——已有,后者即可。)

- [ ] **Step 6: 测试**

`UuidV7Test.kt`:version==7(`uuid.version()`)、variant==2、两次生成时间戳非降(注入固定/递增 Clock)。
`SecurityChainTest.kt`(extends AbstractIntegrationTest,@AutoConfigureMockMvc):
- GET `/v1/whatever` 无票 → 401 + `$.type == "/problems/unauthenticated"`
- GET `/actuator/health` 无票 → 200
- codec.issue 出票 → GET `/v1/whatever` 带票 → 404(过链,无该路由)——证明认证生效
- blacklist.revoke(jti) 后同票 → 401(**受保护路由与公开路由 `/actuator/health` 各测一次**——公开路由也不放行吊销票)
(MockMvc JSON 断言 `jsonPath`。)

- [ ] **Step 7: check 全绿 → Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
feat: add default-deny security chain with jwt

- Add jwt codec with sid claim and uuidv7 jti
- Add jti blacklist failing closed when valkey is down
- Add public routes contributor collected by the chain

Every route is denied unless a contributor declares it public;
revocation checks refuse to degrade open per the spec decision.
EOF
)"
```

---

### Task 3: identity 纯组件(canonicalizer + TOTP + secret crypto)

**Goal:** username 规范化、RFC 6238 TOTP(算法 + 验证器 CAS 接口)、AES-256-GCM secret 加密(AAD 绑定)——全部纯 JVM 单元,零 web/DB 依赖(CAS 落库在 Task 4/6 接线)。

**Files:**
- Create: `identity/internal/{UsernameCanonicalizer,Totp,TotpSecretCrypto}.kt`
- Test: `src/test/kotlin/com/gabon/{CanonicalizerTest,TotpTest,TotpSecretCryptoTest}.kt`

**Acceptance Criteria:**
- [ ] canonicalize = `trim + lowercase(Locale.ROOT)`(spec 定案),单测覆盖混合大小写/首尾空白/土耳其 I 陷阱(`"İ"` 经 ROOT 不变成 `"i̇"` 的坑——断言 ROOT 行为固定)
- [ ] TOTP:RFC 6238 Appendix B 全部 SHA1 向量按 **8 位**通过(59→94287082、1111111109→07081804、1111111111→14050471、1234567890→89005924、2000000000→69279037、20000000000→65353130);生产参数 6 位/30s/窗口±1 为常量
- [ ] Base32(RFC 4648)编码单测(含 padding)
- [ ] crypto:加解密往返;**AAD 篡改必拒**(不同 adminId / 不同 key_version 解密抛异常);IV 12B 随机(两次加密同明文密文不同)
- [ ] `./gradlew check` 全绿

**Verify:** 同 Task 1 → `BUILD SUCCESSFUL`

**Steps:**

- [ ] **Step 1: UsernameCanonicalizer.kt**

```kotlin
package com.gabon.identity.internal

import java.util.Locale

/** username 规范化(spec §5.1 定案):注册与登录唯一入口,唯一约束落在 canonical 列。 */
object UsernameCanonicalizer {
    fun canonicalize(raw: String): String = raw.trim().lowercase(Locale.ROOT)
}
```

- [ ] **Step 2: Totp.kt**(算法 + Base32,完整):

```kotlin
package com.gabon.identity.internal

import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * RFC 6238 TOTP(JDK Mac 实现,不自创算法;spec §5.4 定案)。
 * 生产参数:30s / 6 位 / HmacSHA1 / 窗口 [-1,0,+1]。digits 参数化:RFC Appendix B 向量按 8 位验算法。
 */
object Totp {
    const val STEP_SECONDS = 30L
    const val PROD_DIGITS = 6
    const val WINDOW = 1
    private val POW10 = intArrayOf(1, 10, 100, 1_000, 10_000, 100_000, 1_000_000, 10_000_000, 100_000_000)

    fun stepOf(epochSecond: Long): Long = epochSecond / STEP_SECONDS

    fun codeForStep(secret: ByteArray, step: Long, digits: Int, algorithm: String = "HmacSHA1"): String {
        val mac = Mac.getInstance(algorithm)
        mac.init(SecretKeySpec(secret, "RAW"))
        val hash = mac.doFinal(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(step).array())
        val offset = hash.last().toInt() and 0x0f
        val binary = ((hash[offset].toInt() and 0x7f) shl 24) or
            ((hash[offset + 1].toInt() and 0xff) shl 16) or
            ((hash[offset + 2].toInt() and 0xff) shl 8) or
            (hash[offset + 3].toInt() and 0xff)
        return (binary % POW10[digits]).toString().padStart(digits, '0')
    }
}

/** RFC 4648 Base32(otpauth URI 用;JDK 无内置,不为此引依赖)。 */
object Base32 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun encode(data: ByteArray): String {
        val sb = StringBuilder()
        var buffer = 0L
        var bits = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toLong() and 0xff)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                sb.append(ALPHABET[((buffer shr bits) and 0x1f).toInt()])
            }
        }
        if (bits > 0) sb.append(ALPHABET[((buffer shl (5 - bits)) and 0x1f).toInt()])
        while (sb.length % 8 != 0) sb.append('=')
        return sb.toString()
    }
}
```

- [ ] **Step 3: TotpSecretCrypto.kt**

```kotlin
package com.gabon.identity.internal

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** KEK 注入:prod 走 kek-file(/run/secrets),测试走 kek-base64;二选一 fail fast(同 JwtProps 模式)。 */
@ConfigurationProperties("gabon.security.totp")
data class TotpKekProps(
    val kekBase64: String? = null,
    val kekFile: String? = null,
    val keyVersion: Short = 1,
) {
    fun kekBytes(): ByteArray {
        require((kekBase64 != null) xor (kekFile != null)) {
            "exactly one of gabon.security.totp.kek-base64 / kek-file must be set"
        }
        return kekBase64?.let { Base64.getDecoder().decode(it) } ?: Files.readAllBytes(Path.of(kekFile!!))
    }
}

/**
 * TOTP secret 应用层加密(spec §5.4):AES-256-GCM,IV 12B 随机,tag 128b,存 iv||ct||tag;
 * AAD = "admin_user:{id}:totp_secret:{key_version}"(防密文跨用户/跨版本搬运)。
 */
@Component
class TotpSecretCrypto(props: TotpKekProps) {
    private val kek = SecretKeySpec(props.kekBytes(), "AES")
    val keyVersion: Short = props.keyVersion
    private val random = SecureRandom()

    fun encrypt(adminId: Long, plaintext: ByteArray): ByteArray {
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, kek, GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(aad(adminId, keyVersion))
        return iv + cipher.doFinal(plaintext)
    }

    /** AAD 不匹配(密文搬运/版本错)→ AEADBadTagException 上抛,fail fast。 */
    fun decrypt(adminId: Long, keyVersion: Short, blob: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, kek, GCMParameterSpec(TAG_BITS, blob.copyOfRange(0, IV_BYTES)))
        cipher.updateAAD(aad(adminId, keyVersion))
        return cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
    }

    private fun aad(adminId: Long, version: Short) = "admin_user:$adminId:totp_secret:$version".toByteArray()

    companion object {
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
    }
}
```

`AbstractIntegrationTest.containers()` 追加测试 KEK:

```kotlin
            registry.add("gabon.security.totp.kek-base64") { "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=" }
```

- [ ] **Step 4: 三个单测**(TotpTest 用 RFC 向量表驱动,seed = `"12345678901234567890".toByteArray()`;CryptoTest 直接 new TotpSecretCrypto(TotpKekProps(kekBase64=测试键));CanonicalizerTest 表驱动)。TotpTest 向量:

```kotlin
    @Test
    fun `rfc 6238 appendix b sha1 vectors pass with 8 digits`() {
        val seed = "12345678901234567890".toByteArray()
        val vectors = mapOf(
            59L to "94287082", 1111111109L to "07081804", 1111111111L to "14050471",
            1234567890L to "89005924", 2000000000L to "69279037", 20000000000L to "65353130",
        )
        vectors.forEach { (epoch, expected) ->
            assertThat(Totp.codeForStep(seed, Totp.stepOf(epoch), 8)).isEqualTo(expected)
        }
    }
```

- [ ] **Step 5: check 全绿 → Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
feat: add totp algorithm and secret encryption

- Add RFC 6238 totp with parameterized digits and base32
- Add AES-GCM secret crypto with per-admin AAD binding
- Add username canonicalizer as the single normalizer

RFC Appendix B vectors verify the algorithm at 8 digits while
production verification stays fixed at 6 digits per the spec.
EOF
)"
```

---

### Task 4: token 生命周期(repos + TokenService)

**Goal:** refresh token 仓储(CAS 旋转/重放检测/吊销)与 TokenService(签发对/旋转/登出/全吊销)落地,含并发测试。

**Files:**
- Create: `identity/internal/{RefreshTokenRepository,TokenService}.kt`
- Test: `src/test/kotlin/com/gabon/TokenLifecycleTest.kt`

**Acceptance Criteria:**
- [ ] 旋转 = 原子 `UPDATE ... WHERE token_hash=? AND rotated_at IS NULL AND revoked_at IS NULL AND expires_at > now()`(jOOQ DSL,`.eq()/.isNull/.gt()`,DB 时钟 `DSL.currentOffsetDateTime()`),命中 1 行才发新对
- [ ] 0 行且 hash 存在 → 重放 → **吊销整个 family** 后抛统一 401;0 行且不存在 → 统一 401
- [ ] **并发双 refresh 同一旧 token 仅一方成功**(双线程 CountDownLatch,风格同 OutboxLeaseTest)
- [ ] logout(sid) 吊销 family + jti 进黑名单(**TTL = 票的剩余有效期,过期票不入**);revokeAll(principal) 吊销全部 family(同 TTL 语义)
- [ ] refresh 明文只存在于返回值;库中仅 SHA-256(32B);TTL 30 天(配置化 `gabon.security.refresh.ttl`)
- [ ] `./gradlew check` 全绿

**Verify:** 同 Task 1 → `BUILD SUCCESSFUL`

**Steps:**

- [ ] **Step 1: RefreshTokenRepository.kt**(jOOQ,identity.internal——表所有权要求):

```kotlin
package com.gabon.identity.internal

import com.gabon.jooq.tables.references.REFRESH_TOKEN
import com.gabon.platform.security.PrincipalType
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

data class RotatedRow(val familyId: UUID, val principalType: PrincipalType, val principalId: Long)

@Repository
class RefreshTokenRepository(private val dsl: DSLContext) {
    fun insert(
        familyId: UUID, type: PrincipalType, principalId: Long,
        tokenHash: ByteArray, expiresAt: OffsetDateTime, ip: String?, userAgent: String?,
    ) {
        dsl.insertInto(REFRESH_TOKEN)
            .set(REFRESH_TOKEN.FAMILY_ID, familyId)
            .set(REFRESH_TOKEN.PRINCIPAL_TYPE, type.code)
            .set(REFRESH_TOKEN.PRINCIPAL_ID, principalId)
            .set(REFRESH_TOKEN.TOKEN_HASH, tokenHash)
            .set(REFRESH_TOKEN.EXPIRES_AT, expiresAt)
            .set(REFRESH_TOKEN.CREATED_IP, ip)
            .set(REFRESH_TOKEN.CREATED_USER_AGENT, userAgent)
            .execute()
    }

    /** 原子旋转抢占(spec §5.2):命中 1 行才算成功;全程 DB 时钟。 */
    fun claimForRotation(tokenHash: ByteArray): RotatedRow? =
        dsl.update(REFRESH_TOKEN)
            .set(REFRESH_TOKEN.ROTATED_AT, DSL.currentOffsetDateTime())
            .set(REFRESH_TOKEN.LAST_USED_AT, DSL.currentOffsetDateTime())
            .where(
                REFRESH_TOKEN.TOKEN_HASH.eq(tokenHash)
                    .and(REFRESH_TOKEN.ROTATED_AT.isNull)
                    .and(REFRESH_TOKEN.REVOKED_AT.isNull)
                    .and(REFRESH_TOKEN.EXPIRES_AT.gt(DSL.currentOffsetDateTime())),
            )
            .returningResult(REFRESH_TOKEN.FAMILY_ID, REFRESH_TOKEN.PRINCIPAL_TYPE, REFRESH_TOKEN.PRINCIPAL_ID)
            .fetchOne()
            ?.let { RotatedRow(it.value1()!!, PrincipalType.of(it.value2()!!), it.value3()!!) }

    fun familyOf(tokenHash: ByteArray): UUID? =
        dsl.select(REFRESH_TOKEN.FAMILY_ID).from(REFRESH_TOKEN)
            .where(REFRESH_TOKEN.TOKEN_HASH.eq(tokenHash))
            .fetchOne()?.value1()

    fun revokeFamily(familyId: UUID): Int =
        dsl.update(REFRESH_TOKEN)
            .set(REFRESH_TOKEN.REVOKED_AT, DSL.currentOffsetDateTime())
            .where(REFRESH_TOKEN.FAMILY_ID.eq(familyId).and(REFRESH_TOKEN.REVOKED_AT.isNull))
            .execute()

    fun revokeAllFor(type: PrincipalType, principalId: Long): Int =
        dsl.update(REFRESH_TOKEN)
            .set(REFRESH_TOKEN.REVOKED_AT, DSL.currentOffsetDateTime())
            .where(
                REFRESH_TOKEN.PRINCIPAL_TYPE.eq(type.code)
                    .and(REFRESH_TOKEN.PRINCIPAL_ID.eq(principalId))
                    .and(REFRESH_TOKEN.REVOKED_AT.isNull),
            )
            .execute()
}
```

- [ ] **Step 2: TokenService.kt**

```kotlin
package com.gabon.identity.internal

import com.gabon.platform.security.AccessTokenCodec
import com.gabon.platform.security.JtiBlacklist
import com.gabon.platform.security.PrincipalType
import com.gabon.platform.web.ProblemException
import com.gabon.platform.web.ProblemType
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID

@ConfigurationProperties("gabon.security.refresh")
data class RefreshProps(val ttl: Duration = Duration.ofDays(30))

data class TokenPair(val accessToken: String, val refreshToken: String, val expiresInSeconds: Long)

/** 钱核之外的阻塞事务边界:旋转+签发同事务;协程禁入(全局规则)。 */
@Service
class TokenService(
    private val repo: RefreshTokenRepository,
    private val codec: AccessTokenCodec,
    private val blacklist: JtiBlacklist,
    private val props: RefreshProps,
    private val clock: Clock,
) {
    private val random = SecureRandom()

    @Transactional
    fun issuePair(type: PrincipalType, principalId: Long, ip: String?, userAgent: String?): TokenPair {
        val family = UUID.randomUUID()
        return issueInFamily(family, type, principalId, ip, userAgent)
    }

    // noRollbackFor 必要:重放路径 revokeFamily 后抛 ProblemException(RuntimeException),
    // 裸 @Transactional 会把吊销一并回滚 = family 逃生(Task 4 实证,移除该属性恰 2 测试红)
    @Transactional(noRollbackFor = [ProblemException::class])
    fun refresh(rawRefreshToken: String, ip: String?, userAgent: String?): TokenPair {
        val hash = sha256(rawRefreshToken)
        val row = repo.claimForRotation(hash)
        if (row == null) {
            val family = repo.familyOf(hash)
            if (family != null) {
                repo.revokeFamily(family) // 重放:已旋转/已吊销的 token 再现 → 全 family 吊销(spec §5.2)
                throw ProblemException(ProblemType.INVALID_CREDENTIALS, "refresh replay, family=$family revoked")
            }
            throw ProblemException(ProblemType.INVALID_CREDENTIALS, "unknown refresh token")
        }
        return issueInFamily(row.familyId, row.principalType, row.principalId, ip, userAgent)
    }

    fun logout(sid: UUID, jti: String, expiresAt: Instant) {
        repo.revokeFamily(sid)
        blacklistRemaining(jti, expiresAt)
    }

    fun revokeAll(type: PrincipalType, principalId: Long, currentJti: String, expiresAt: Instant) {
        repo.revokeAllFor(type, principalId)
        blacklistRemaining(currentJti, expiresAt)
    }

    /** 黑名单 TTL = 票的剩余有效期(spec §5.2 原文,非完整 access TTL);已过期不入。 */
    private fun blacklistRemaining(jti: String, expiresAt: Instant) {
        val remaining = Duration.between(clock.instant(), expiresAt)
        if (!remaining.isNegative && !remaining.isZero) blacklist.revoke(jti, remaining)
    }

    private fun issueInFamily(
        family: UUID, type: PrincipalType, principalId: Long, ip: String?, userAgent: String?,
    ): TokenPair {
        val raw = ByteArray(RAW_BYTES).also { random.nextBytes(it) }
        val rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
        val expiresAt = OffsetDateTime.ofInstant(clock.instant().plus(props.ttl), ZoneOffset.UTC)
        repo.insert(family, type, principalId, sha256(rawToken), expiresAt, ip, userAgent)
        val access = codec.issue(principalId, type, family)
        return TokenPair(access, rawToken, codec.accessTtl().seconds)
    }

    private fun sha256(raw: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())

    companion object {
        private const val RAW_BYTES = 32 // 256-bit 随机(spec §5.2)
    }
}
```

- [ ] **Step 3: TokenLifecycleTest.kt**(extends AbstractIntegrationTest):旋转成功链(issue→refresh→旧 token 再 refresh = 重放 → family 吊销 → 新 token 也失效);并发双 refresh(两线程同旧 token,恰一方 TokenPair、一方 ProblemException);revokeAll 后 refresh 失败;jti 进黑名单断言 `blacklist.isRevoked`。并发测试骨架:

```kotlin
    @Test
    fun `concurrent double refresh lets exactly one side win`() {
        val pair = tokens.issuePair(PrincipalType.CUSTOMER, 1L, null, null)
        val start = CountDownLatch(1)
        val results = ConcurrentLinkedQueue<Result<TokenPair>>()
        val threads = (1..2).map {
            thread {
                start.await()
                results.add(runCatching { tokens.refresh(pair.refreshToken, null, null) })
            }
        }
        start.countDown()
        threads.forEach { it.join() }
        assertThat(results.count { it.isSuccess }).isEqualTo(1)
        assertThat(results.count { it.isFailure }).isEqualTo(1)
    }
```

(注意:失败侧因重放路径吊销 family——两线程一个 claim 成功一个 0 行,0 行侧 familyOf 命中 → 吊销 family。这意味着并发双 refresh 会把胜者的新 token 一并吊销——**这是 spec 语义的正确后果**(同 token 并发使用即视为可疑),测试断言胜者新 token refresh 也 401。)

- [ ] **Step 4: check 全绿 → Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
feat: add refresh token lifecycle with rotation

- Add repository with atomic claim and family revocation
- Add token service issuing access and refresh pairs
- Treat reuse of a rotated token as family-wide compromise

Concurrent refreshes of one token let exactly one side win and
the replay path revokes the family including the fresh pair.
EOF
)"
```

---

### Task 5: C 端鉴权(service + 登录保护 + 端点 + 集成测试)

**Goal:** register/login/refresh/logout/me/changePassword 全链路 + 账号锁定/IP 限流(Valkey)+ 统一 401 语义 + fail-closed 测试。

**Files:**
- Create: `identity/internal/{CustomerRepository,LoginProtection,AuthService}.kt`、`identity/internal/web/{dto.kt,AuthController.kt}`、`identity/internal/IdentityPublicRoutes.kt`
- Test: `src/test/kotlin/com/gabon/{AuthFlowTest,ValkeyDownTest}.kt`

**Acceptance Criteria:**
- [ ] register:canonical 唯一(重复 → 409 `/problems/username-taken`);邀请码可选,提供但无效 → 400;新用户生成 10 位 A-Z2-7 邀请码(unique 冲突重试 ≤5);成功即返 TokenPair
- [ ] login:IP 限流(固定窗口 30 次/10 分钟,可配)→ 429;账号锁定(5 次失败/15 分钟,可配)→ **锁定期内正确密码也 401**;失败/IP 计数一律 **Lua 原子 INCR+PEXPIRE**(禁 INCR 后再 EXPIRE 两步);**不存在的用户与密码错返回逐字节相同的 401 body**
- [ ] logout:凭 access 的 sid 吊销 family(无需提交 refresh),该 family 的 refresh 之后 401;jti 黑名单生效(旧 access 再访问 → 401)
- [ ] changePassword:验旧密→改 hash→revokeAll+黑名单当前 jti(其它 session 全失效)
- [ ] Valkey 停机语义:带票请求 → 503(过滤器);登录 → 503(计数器异常经 handler)——`ValkeyDownTest` 用独立坏端口上下文验证
- [ ] `./gradlew check` 全绿

**Verify:** 同 Task 1 → `BUILD SUCCESSFUL`

**Steps:**

- [ ] **Step 1: CustomerRepository.kt**(jOOQ;identity.internal):insert(返回 id,canonical/invite_code 冲突用 `.onConflictDoNothing().returningResult(ID)` 区分——canonical 冲突→USERNAME_TAKEN,invite_code 冲突→重试)、`findAuthByCanonical`(id/password_hash/status)、`findInviterIdByInviteCode`、`findMeById`(id/username)、`touchLastLogin`。全部 `.eq()` DSL。示例签名:

```kotlin
@Repository
class CustomerRepository(private val dsl: DSLContext) {
    data class AuthRow(val id: Long, val passwordHash: String, val active: Boolean)

    fun findAuthByCanonical(canonical: String): AuthRow? =
        dsl.select(CUSTOMER.ID, CUSTOMER.PASSWORD_HASH, CUSTOMER.STATUS)
            .from(CUSTOMER)
            .where(CUSTOMER.USERNAME_CANONICAL.eq(canonical))
            .fetchOne()
            ?.let { AuthRow(it.value1()!!, it.value2()!!, it.value3() == ACTIVE) }
    // insert / findInviterIdByInviteCode / findMeById / touchLastLogin 同风格
    companion object { const val ACTIVE: Short = 1 }
}
```

- [ ] **Step 2: LoginProtection.kt**

```kotlin
package com.gabon.identity.internal

import com.gabon.platform.web.ProblemException
import com.gabon.platform.web.ProblemType
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Component
import java.time.Duration

@ConfigurationProperties("gabon.security.login")
data class LoginProtectionProps(
    val maxFailures: Long = 5,
    val lockDuration: Duration = Duration.ofMinutes(15),
    val ipLimit: Long = 30,
    val ipWindow: Duration = Duration.ofMinutes(10),
)

/**
 * 登录保护(spec §5.3):固定窗口计数,Lua 脚本原子 INCR+PEXPIRE——
 * 杜绝"INCR 成功后 EXPIRE 失败留下永久 key"导致的永久锁定/限流(计划注记 6)。
 * 所有 redis 调用经 store{} 包装为 AuthStoreUnavailableException = fail-closed
 * (同 JtiBlacklist 模式,覆盖命令超时;handler 出 503)。
 */
@Component
class LoginProtection(
    private val redis: StringRedisTemplate,
    private val props: LoginProtectionProps,
) {
    // 每个 redis 调用包 store { ... }(私有辅助同 JtiBlacklist:catch DataAccessException → AuthStoreUnavailableException);
    // ProblemException(RATE_LIMITED/锁定)在 store 块外抛出,不受包装影响。
    // RedisScript 执行签名以 Spring Data Redis 当前文档为准(计划注记 1)
    private val incrWithTtl =
        DefaultRedisScript(
            """
            local c = redis.call('INCR', KEYS[1])
            if c == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end
            return c
            """.trimIndent(),
            Long::class.java,
        )

    fun checkIpLimit(ip: String) {
        val count = redis.execute(incrWithTtl, listOf("auth:ip:$ip"), props.ipWindow.toMillis().toString())!!
        if (count > props.ipLimit) throw ProblemException(ProblemType.RATE_LIMITED, "ip=$ip count=$count")
    }

    fun assertNotLocked(scope: String, canonical: String) {
        val count = redis.opsForValue().get(key(scope, canonical))?.toLong() ?: 0
        if (count >= props.maxFailures) {
            throw ProblemException(ProblemType.INVALID_CREDENTIALS, "locked scope=$scope user=$canonical")
        }
    }

    fun onFailure(scope: String, canonical: String) {
        redis.execute(incrWithTtl, listOf(key(scope, canonical)), props.lockDuration.toMillis().toString())
    }

    fun onSuccess(scope: String, canonical: String) {
        redis.delete(key(scope, canonical))
    }

    private fun key(scope: String, canonical: String) = "auth:fail:$scope:$canonical"
}
```

- [ ] **Step 3: AuthService.kt**(阻塞 `@Transactional` 写路径;统一 401 全部走 `ProblemException(INVALID_CREDENTIALS, 内部原因)`):register(canonicalize → 可选邀请码解析(无效→`ProblemException(VALIDATION,...)`)→ 生成邀请码重试插入 → issuePair)、login(checkIpLimit → assertNotLocked → findAuth:null→onFailure+401 / password 不符→onFailure+401 / !active→401 → onSuccess → touchLastLogin → issuePair)、me、changePassword(验旧→update→revokeAll)。邀请码生成:

```kotlin
    private fun generateInviteCode(): String =
        (1..INVITE_LEN).map { INVITE_ALPHABET[random.nextInt(INVITE_ALPHABET.length)] }.joinToString("")
    // INVITE_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567", INVITE_LEN = 10;插入冲突(returningResult 空)重试 ≤5,超限抛 IllegalStateException(fail fast)
```

- [ ] **Step 4: dto.kt + AuthController.kt + IdentityPublicRoutes.kt**

dto(jakarta validation 在边界):`RegisterRequest(@field:NotBlank @field:Size(min=3,max=32) username, @field:NotBlank @field:Size(min=8,max=128) password, inviteCode: String?)`、`LoginRequest(@field:NotBlank username, @field:NotBlank password)`、`RefreshRequest(@field:NotBlank refreshToken)`、`ChangePasswordRequest(@field:NotBlank currentPassword, @field:NotBlank @field:Size(min=8,max=128) newPassword)`、`TokenPairResponse/MeResponse`。

Controller(`/v1/auth`,identity.internal.web;principal 从 `SecurityContextHolder` 取 `GabonPrincipal`;客户端 IP `request.remoteAddr`——代理头解析是部署层事,一期不做):login/register/refresh 200 + TokenPairResponse;logout 204;me 200;password 204。

```kotlin
@Bean 不用——contributor 是组件:
@Component
class IdentityPublicRoutes : PublicRoutesContributor {
    override fun publicRoutes() = listOf("/v1/auth/login", "/v1/auth/register", "/v1/auth/refresh", "/v1/admin/auth/login")
}
```

(admin login 公开路由此处一并声明,Task 6 使用。)

- [ ] **Step 5: AuthFlowTest.kt**(MockMvc,extends AbstractIntegrationTest):
- register→login→me 闭环(me 返回 username)
- register 重名 → 409 `$.type=="/problems/username-taken"`
- **枚举防护**:不存在用户与错密码的 401 响应 body 字符串完全相等
- 锁定:同用户 5 次错密 → 第 6 次**正确密码**也 401;Valkey flush 后(等价锁过期)恢复可登录
- IP 限流:循环 31 次登录(不同用户名防锁定干扰)→ 第 31 次 429
- logout:登录→logout(带 access)→ 原 refresh 401 且原 access 再访问 me 401
- changePassword:改密后旧 refresh 401、旧 access 401、新密码可登录
- 校验:password 长度 <8 注册 → 400 `/problems/validation`

`ValkeyDownTest.kt`(独立类,坏端口上下文):

```kotlin
@SpringBootTest(properties = ["spring.data.redis.port=1", "spring.data.redis.host=127.0.0.1"])
@AutoConfigureMockMvc
class ValkeyDownTest {
    // 不继承 AbstractIntegrationTest 的 redis 属性;PG 属性仍需——直接复用其容器:
    companion object { /* @DynamicPropertySource 注册 AbstractIntegrationTest 同款 PG + jwt/kek 测试键,但不注册 redis */ }
    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var codec: AccessTokenCodec

    @Test
    fun `bearer request fails closed with 503 when valkey is down`() {
        val token = codec.issue(1L, PrincipalType.CUSTOMER, UUID.randomUUID())
        mockMvc.get("/v1/auth/me") { header("Authorization", "Bearer $token") }
            .andExpect { status { isServiceUnavailable() } }
    }

    @Test
    fun `login fails closed with 503 when valkey is down`() { /* POST login 任意凭证 → 503 */ }
}
```

(PG 容器复用实现:把 AbstractIntegrationTest 的容器与属性注册抽为可复用的 companion 帮助函数,或 ValkeyDownTest 自起独立 PG——实现者选更简洁者,报告注明。)

- [ ] **Step 6: check 全绿 → Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
feat: add customer auth endpoints with protection

- Add register, login, refresh, logout, me and password
- Add account lockout and ip rate limit on valkey counters
- Keep auth failures byte-identical to block enumeration

Lockout counts TOTP-style failures too and correct passwords
are rejected while locked; valkey outages fail closed as 503.
EOF
)"
```

---

### Task 6: admin 鉴权(TOTP 流程)+ 边界收尾

**Goal:** admin login(密码 + 已启用则强制 TOTP)、enroll/confirm 流程、TOTP CAS 消费落库;删除 IdentityInternalMarker、根包白名单断言、C7 文档增补、CLAUDE.md 同步。

**Files:**
- Create: `identity/internal/{AdminUserRepository,TotpVerifier,AdminAuthService}.kt`、`identity/internal/web/AdminAuthController.kt`
- Delete: `identity/internal/IdentityInternalMarker.kt`(首个真实类已出现,spec §3 惯例)
- Modify: `src/test/kotlin/com/gabon/ModuleBoundaryTest.kt`(+根包白名单断言)
- Modify: `docs/architecture-redesign.md`(C7 增补 TOTP 定案)、`CLAUDE.md`(测试数、identity 落地一句)
- Modify: `src/main/kotlin/com/gabon/wallet/internal/WalletProps.kt`(KDoc 注明消费方=子项目 2 充值域)
- Test: `src/test/kotlin/com/gabon/AdminTotpFlowTest.kt`

**Acceptance Criteria:**
- [ ] admin login:未启用 TOTP → 密码即发对;已启用 → totpCode 缺失/错误 → 统一 401(**TOTP 失败计入锁定计数**);正确 → 发对
- [ ] enroll(需 ADMIN token):生成 20B secret → AES-GCM 加密落库(enabled 保持 false,可重复 enroll 覆盖未确认 secret)→ 返回 otpauth URI:label `gabon-admin:{username}` **percent-encoded**(URLEncoder UTF-8 且 `+`→`%20`),secret Base32 **去 padding**(otpauth 惯例),形如 `otpauth://totp/{encodedLabel}?secret={b32}&issuer=gabon&algorithm=SHA1&digits=6&period=30`;**用户名含空格/冒号/@ 也生成合法 URI(测试断言无裸空格、无 '=')**
- [ ] confirm:解密 secret → 窗口 ±1 验证 + **CAS 消费 step**(命中 1 行才通过)→ `UPDATE ... SET totp_enabled=true WHERE id=? AND totp_enabled=false`
- [ ] **同 step 重放拒绝**:同一 code 第二次提交(confirm 或 login)→ 401;窗口内前一 step 的旧 code 在新 step 被接受后 → 401(单调性)
- [ ] `/v1/admin/auth/*` 除 login 外都要 ROLE_ADMIN(customer token 访问 → 403)
- [ ] 根包白名单断言:`com.gabon` 根包只许 `GabonApplication*`(含 `GabonApplicationKt`);负向探针红→revert 绿
- [ ] C7 增补(给定文本见 Step 6);CLAUDE.md 测试数更新为实际值
- [ ] `./gradlew check` 全绿

**Verify:** 同 Task 1 → `BUILD SUCCESSFUL`;测试总数以最终 XML 汇总为准(预期 30±,报告给出精确值)

**Steps:**

- [ ] **Step 1: AdminUserRepository.kt**(jOOQ):`findAuthByCanonical`(id/password_hash/status/totp_enabled/totp_secret_enc/totp_key_version/username)、`saveTotpSecret(id, enc, version)`(仅当 `TOTP_ENABLED.eq(false)` 允许覆盖,0 行抛)、`enableTotp(id)`(`WHERE ID.eq(id).and(TOTP_ENABLED.eq(false))`,返回行数)、**CAS 消费**:

```kotlin
    /** TOTP 接受的并发语义(spec §5.4):命中 1 行才算验证成功,0 行 = 并发同 code / 重放旧 step。 */
    fun casConsumeStep(adminId: Long, step: Long): Boolean =
        dsl.update(ADMIN_USER)
            .set(ADMIN_USER.TOTP_LAST_USED_STEP, step)
            .where(
                ADMIN_USER.ID.eq(adminId)
                    .and(ADMIN_USER.TOTP_LAST_USED_STEP.isNull.or(ADMIN_USER.TOTP_LAST_USED_STEP.lt(step))),
            )
            .execute() == 1
```

- [ ] **Step 2: TotpVerifier.kt**

```kotlin
package com.gabon.identity.internal

import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.time.Clock

/** 生产验证器:6 位、窗口 [-1,0,+1]、常量时间比较、CAS 消费(spec §5.4)。 */
@Component
class TotpVerifier(
    private val adminRepo: AdminUserRepository,
    private val clock: Clock,
) {
    fun verifyAndConsume(adminId: Long, secret: ByteArray, code: String): Boolean {
        val nowStep = Totp.stepOf(clock.instant().epochSecond)
        for (delta in -Totp.WINDOW..Totp.WINDOW) {
            val step = nowStep + delta
            val expected = Totp.codeForStep(secret, step, Totp.PROD_DIGITS)
            if (MessageDigest.isEqual(expected.toByteArray(), code.toByteArray())) {
                return adminRepo.casConsumeStep(adminId, step)
            }
        }
        return false
    }
}
```

- [ ] **Step 3: AdminAuthService.kt + AdminAuthController.kt**:login(checkIpLimit → assertNotLocked(scope="admin") → findAuth → 密码验证(失败 onFailure+401)→ 若 totp_enabled:code 缺失→onFailure+401;decrypt(id, key_version, enc) → verifyAndConsume 失败→onFailure+401 → onSuccess → issuePair(ADMIN))、enroll(20B SecureRandom → encrypt → saveTotpSecret → otpauth URI:`val secretB32 = Base32.encode(secret).trimEnd('=')`;`val label = URLEncoder.encode("gabon-admin:$username", StandardCharsets.UTF_8).replace("+", "%20")`;拼 `otpauth://totp/$label?secret=$secretB32&issuer=gabon&algorithm=SHA1&digits=6&period=30`)、confirm(decrypt → verifyAndConsume → enableTotp 0 行→VALIDATION)。Controller:`/v1/admin/auth/{login,logout,totp/enroll,totp/confirm}`;logout 复用 TokenService.logout。

- [ ] **Step 4: AdminTotpFlowTest.kt**(MockMvc + 可控时钟):`@TestConfiguration` 提供可变 Clock(`MutableClock` 包装 `Instant` 可 set,`@Primary`),测试直接用 dsl 插 admin 行(bcrypt hash 经 PasswordEncoder 生成):
- 未启用:login(密码)→ 对
- enroll → 解析 URI 中 base32 secret → 测试侧 `Totp.codeForStep(secret, stepOf(clock), 6)` 生成 code → confirm → totp_enabled=true
- 启用后:login 无 code → 401;错 code → 401 且计入锁定(连错 5 次后正确密码+正确 code 也 401);正确 code → 对
- **同 code 二次 login → 401**(CAS);时钟推进一个 step 后旧 code → 401(单调),新 code → 对
- customer token 访问 enroll → 403 `/problems/forbidden`

- [ ] **Step 5: 边界收尾**:删除 `identity/internal/IdentityInternalMarker.kt`;`ModuleBoundaryTest` 追加:

```kotlin
    /** 根包白名单:com.gabon 根包只许启动装配(WalletProps 教训;第二批终审 Minor #2) */
    @Test
    fun `root package only hosts application bootstrap`() {
        classes()
            .that()
            .resideInAPackage("com.gabon")
            .should()
            .haveSimpleNameStartingWith("GabonApplication")
            .check(classes)
    }
```

负向探针:临时在 `com.gabon` 根放 `internal object RootProbe` → 红 → 删除恢复绿(证据留报告)。
`WalletProps.kt` KDoc 追加一行:`消费方:子项目 2 充值域(汇率换算);在此之前保留(2026-07-03 定案)。`

- [ ] **Step 6: 文档**:`docs/architecture-redesign.md` C7 节"后台 2FA"条目后追加:

```markdown
> **TOTP 实施定案(2026-07,迁移子项目 1 第三批)**:RFC 6238,JDK Mac 自实现(不自创算法);生产参数 30s 步长 / 6 位 / HMAC-SHA1 / 验证窗口 [-1,0,+1](算法函数以 digits 参数化,RFC Appendix B 向量按 8 位验算法)。secret 应用层加密:AES-256-GCM,IV 12B 随机,tag 128b,AAD 绑定 `admin_user:{id}:totp_secret:{key_version}`,KEK 注入不进 git。防重放:`totp_last_used_step` 原子 CAS 单调递增,命中 1 行才通过。鉴权错误对外统一 401 `/problems/invalid-credentials` 防枚举(锁定/禁用/TOTP 错不区分);username 以 canonical(trim+lowercase ROOT)唯一。设计全文见 `docs/superpowers/specs/2026-07-02-module-boundaries-identity-design.md`。
```

`CLAUDE.md`:check 注释测试数改为实际值;目录结构里 identity 行补"(已落地:鉴权/token/TOTP)"。

- [ ] **Step 7: check 全绿 → Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
feat: add admin totp auth and boundary closeout

- Add admin login with mandatory totp once enrolled
- Add enroll and confirm flow with encrypted secrets
- Consume totp steps via CAS to reject replays
- Add root package assertion and record C7 decisions

Admin sessions require the second factor after confirmation
and every accepted code permanently burns its time step.
EOF
)"
```

---

## 后续(本计划不含)

- springdoc spike(任务 #12):本批合入后独立进行(与本批共享 build.gradle.kts,串行)
- 子项目 1 至此完成;子项目 2(钱核完全体)另开 brainstorm → spec → plan
