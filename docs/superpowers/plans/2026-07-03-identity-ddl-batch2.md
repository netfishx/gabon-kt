# 身份域 DDL 与测试基建(第二批)实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers-extended-cc:subagent-driven-development (recommended) or superpowers-extended-cc:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 落地 spec §8 第二批——identity 三表 DDL(`V2__identity_core.sql`)+ 测试基类升级为 PG+Valkey 双单例(`AbstractIntegrationTest`),并完成第一批终审移交的边界规则强化(格子形状断言、TABLE_OWNER 反向断言、WalletProps 归位)。

**Architecture:** DDL 按 spec §5.1 建 customer/admin_user/refresh_token(canonical username、TOTP 加密材料列、refresh 旋转/吊销列),复用 V1 的 `set_updated_at()` 触发器;codegen 重新生成后由 `ModuleBoundaryTest` 完整性断言强制登记表归属(internal 前缀);测试基类改名并加 Valkey 单例容器,为第三批(jti 黑名单/限流)铺路,本批 main 代码不引入任何 Redis 客户端依赖(YAGNI)。

**Tech Stack:** Flyway(V2 versioned 迁移,一经合入不可变)/ jOOQ codegen / ArchUnit / Testcontainers(`postgres:18-alpine` + Valkey 9.1)。构建命令与环境变量见 CLAUDE.md。

**Spec:** `docs/superpowers/specs/2026-07-02-module-boundaries-identity-design.md` §5.1/§8;衔接清单见任务 #10 描述(7 条,本计划全覆盖)。

**User decisions (already made):**
- username 规范化:`username_canonical` unique 列 + 代码边界 `trim + lowercase(Locale.ROOT)`,不用 citext
- TOTP secret:AES-256-GCM 应用层加密列(`totp_secret_enc` + `totp_key_version`)+ `totp_last_used_step` 防重放(CAS 语义在第三批实现,本批只建列)
- refresh_token:明文不落库存 SHA-256、旋转/吊销/审计列与四组索引(spec §5.1 表)
- TABLE_OWNER 值语义 = 允许访问的包前缀(identity 三表 → `com.gabon.identity.internal`)
- 测试基类改名 `AbstractIntegrationTest`,PG+Valkey 双单例;CLAUDE.md 测试约定同步
- 第一批终审移交:格子形状断言、WalletProps 归位、TABLE_OWNER 反向断言(任务 #10 清单 4/6)

**计划注记:**
1. **本批不加 Redis 客户端依赖**:Valkey 容器只在测试基类启动并经 `@DynamicPropertySource` 注册 `spring.data.redis.host/port`(classpath 无 starter 时未知属性无害);`spring-boot-starter-data-redis`(Lettuce)随第三批的真实消费方进入。属性名以 Boot 4.1 官方文档为准,实现时核实,勿凭记忆。
2. **Valkey 镜像 tag 须核实**:计划按 `valkey/valkey:9.1-alpine` 写;实现时先 `docker pull` 验证 tag 存在,不存在则用 Docker Hub 上最近的官方 9.1 系 alpine tag 并在报告中注明。
3. **V2 一经合入即不可变**(CLAUDE.md Flyway 硬规则):列定义在本批内改都行,合入 main 后只能新增迁移向前修。

---

## File Structure(第二批终态)

```
src/main/resources/db/migration/V2__identity_core.sql   ← 新建(identity 三表)
src/main/kotlin/com/gabon/wallet/internal/WalletProps.kt ← 自 GabonApplication.kt 迁出
src/main/kotlin/com/gabon/GabonApplication.kt            (删除 WalletProps,仅剩启动类)
src/test/kotlin/com/gabon/AbstractIntegrationTest.kt     ← AbstractPgTest.kt 改名(+Valkey 单例)
src/test/kotlin/com/gabon/ModuleBoundaryTest.kt          (+格子形状断言、+反向断言、TABLE_OWNER +3)
src/test/kotlin/com/gabon/{RechargeIdempotency,InsufficientBalance,CoroutineBoundary,OutboxLease}Test.kt(基类名)
CLAUDE.md                                                (测试约定/测试数)
```

---

### Task 1: 边界规则强化(格子形状 + 反向断言 + WalletProps 归位)

**Goal:** 补上第一批终审移交的两条断言并把根包散类 `WalletProps` 归位,堵住"上下文根包盲区"。

**Files:**
- Create: `src/main/kotlin/com/gabon/wallet/internal/WalletProps.kt`
- Modify: `src/main/kotlin/com/gabon/GabonApplication.kt`(删除 WalletProps 定义)
- Modify: `src/test/kotlin/com/gabon/ModuleBoundaryTest.kt`(+2 个测试方法)

**Acceptance Criteria:**
- [ ] 格子形状断言:九上下文包内的类必须位于 `<ctx>.api..` 或 `<ctx>.internal..`;负向探针(临时在 `com.gabon.wallet` 根放一个类)变红,revert 恢复绿
- [ ] TABLE_OWNER 反向断言:登记了 codegen 不存在的表即失败;负向探针(临时加假条目 `"Ghost" to "com.gabon.wallet.internal"`)变红,revert 恢复绿
- [ ] `WalletProps` 位于 `com.gabon.wallet.internal`,`GabonApplication.kt` 只剩启动类;`@ConfigurationPropertiesScan`(扫根包)仍能绑定
- [ ] `./gradlew check` 全绿,测试数 14→16

**Verify:**(⚠️ 环境变量不跨 Bash 调用持久,三个 export 与 gradle 同一条命令)
```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-25.jdk/Contents/Home
export DOCKER_HOST=unix:///Users/ethanwang/.orbstack/run/docker.sock
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
./gradlew check
```
→ `BUILD SUCCESSFUL`,16 测试 0 失败

**Steps:**

- [ ] **Step 1: WalletProps 归位**

新建 `src/main/kotlin/com/gabon/wallet/internal/WalletProps.kt`:

```kotlin
package com.gabon.wallet.internal

import org.springframework.boot.context.properties.ConfigurationProperties

/** 验收①:Kotlin data class 的构造器绑定 + nullable 语义(自根包归位,spec 批次 2) */
@ConfigurationProperties("wallet")
data class WalletProps(
    val exchangeRate: Long = 100,
    val currency: String = "CNY",
)
```

`GabonApplication.kt` 删除 `WalletProps` 数据类及其 KDoc、删除不再使用的 `import org.springframework.boot.context.properties.ConfigurationProperties`(保留 `@ConfigurationPropertiesScan` 注解与其 import——它扫根包,能发现新位置)。先 `grep -rn "WalletProps" src/` 确认除定义外无其他引用。

- [ ] **Step 2: ModuleBoundaryTest 加两个测试**

在 `every jooq table class has a registered owner` 测试之后追加:

```kotlin
    /** 格子形状:上下文包内的类必须位于 api.. 或 internal..——根包散类是边界规则的盲区(第一批终审 Important #1) */
    @Test
    fun `context classes reside in api or internal`() {
        for (ctx in CONTEXTS) {
            classes()
                .that()
                .resideInAPackage("${pkg(ctx)}..")
                .should()
                .resideInAnyPackage("${pkg(ctx)}.api..", "${pkg(ctx)}.internal..")
                .check(classes)
        }
    }

    /** 规则 6 反向:登记了 codegen 不存在的表 = 白名单陈旧条目,同样失败(第一批终审 Minor #3) */
    @Test
    fun `table owner whitelist has no stale entries`() {
        val generated =
            classes
                .filter { it.packageName == "com.gabon.jooq.tables" }
                .filter { it.isTopLevelClass }
                .filter { !it.simpleName.endsWith("Kt") && !it.simpleName.endsWith("Path") }
                .map { it.simpleName }
                .toSet()
        check(generated.isNotEmpty()) { "com.gabon.jooq.tables 导入为空:archunit.main.classes 或 codegen 异常,断言失去意义" }
        val stale = TABLE_OWNER.keys.filterNot { it in generated }
        check(stale.isEmpty()) { "TABLE_OWNER 含 codegen 不存在的表(陈旧条目,须删除):$stale" }
    }
```

- [ ] **Step 3: 负向探针 ×2(不提交,报告须附证据)**

a. 形状探针:临时新建 `src/main/kotlin/com/gabon/wallet/ShapeProbe.kt`(`package com.gabon.wallet` + `internal object ShapeProbe`),跑 `./gradlew test --tests "com.gabon.ModuleBoundaryTest"` → `context classes reside in api or internal` 必须红;删除该文件,重跑恢复绿。
b. 陈旧条目探针:临时在 `TABLE_OWNER` 加 `"Ghost" to "com.gabon.wallet.internal"`,重跑 → `table owner whitelist has no stale entries` 必须红;`git checkout` 该测试文件后重新应用 Step 2 改动(或直接撤掉假条目),恢复绿。

- [ ] **Step 4: 跑完整 Verify(16 测试),Commit**

```bash
git add src/main/kotlin/com/gabon/GabonApplication.kt src/main/kotlin/com/gabon/wallet/internal/WalletProps.kt src/test/kotlin/com/gabon/ModuleBoundaryTest.kt
git commit -m "$(cat <<'EOF'
test: add shape and stale-entry boundary assertions

- Add context shape rule requiring api or internal packages
- Add reverse check failing stale TABLE_OWNER entries
- Move WalletProps from root package into wallet internal

Root-package classes evaded every boundary rule; the shape
assertion closes that blind spot and WalletProps was its one
existing instance.
EOF
)"
```

(提交规则 hook 校验:标题 <50、body 行 ≤72、bullet 祈使、有解释段落。本会话由 Claude 执行时在消息尾追加 harness 约定的 Co-Authored-By/Claude-Session 两行。)

---

### Task 2: V2__identity_core.sql + 表归属登记 + truncate 列表

**Goal:** identity 三表 DDL 落地,codegen 重新生成,TABLE_OWNER 登记(internal 前缀),测试基类 truncate 列表补全。

**Files:**
- Create: `src/main/resources/db/migration/V2__identity_core.sql`
- Modify: `src/test/kotlin/com/gabon/ModuleBoundaryTest.kt`(TABLE_OWNER +3)
- Modify: `src/test/kotlin/com/gabon/AbstractPgTest.kt`(truncate 列表)

**Acceptance Criteria:**
- [ ] 三表结构与 spec §5.1 一致:`username_canonical` unique、`totp_secret_enc`+`totp_key_version`+`totp_last_used_step`+`totp_enabled`、refresh_token 全列 + 四组索引(`token_hash` unique、`(principal_type, principal_id)`、`family_id`、`expires_at`)
- [ ] **不变量落 DB(defense-in-depth,同 V1 风格)**:status ∈ (0,1) ×2、principal_type ∈ (1,2)、`octet_length(token_hash)=32`、TOTP 密材与 key_version 同生同灭且启用时均非空、key_version>0、last_used_step≥0
- [ ] **TDD 顺序**:先只加 DDL 跑 `ModuleBoundaryTest` → 完整性断言红(报 Customer/AdminUser/RefreshToken 未登记)→ 登记后绿(红的证据留报告)
- [ ] TABLE_OWNER 新增三项值为 `com.gabon.identity.internal`(允许访问前缀语义)
- [ ] truncate 列表含三张新表,全部测试绿
- [ ] `./gradlew check` 全绿(codegen 因迁移目录变更重跑,属预期)

**Verify:** 同 Task 1 命令 → `BUILD SUCCESSFUL`,16 测试 0 失败

**Steps:**

- [ ] **Step 1: 写 V2 迁移**

`src/main/resources/db/migration/V2__identity_core.sql`(完整文件;`set_updated_at()` 已由 V1 定义,直接建触发器):

```sql
-- 身份域核心表(spec §5.1):C端账号、admin 账号、refresh token
-- username 规范化:唯一约束落在 canonical 列,规范化规则(trim+lowercase)在代码边界(第三批)

create table customer (
  id                 bigint generated always as identity primary key,
  username           text not null,                 -- 展示用原始输入
  username_canonical text not null,
  password_hash      text not null,
  invite_code        text not null,                 -- 注册时生成(第三批)
  invited_by         bigint references customer(id),
  status             smallint not null default 1,   -- 1=active 0=disabled(人工封禁)
  last_login_at      timestamptz,
  created_at         timestamptz not null default now(),
  updated_at         timestamptz not null default now(),
  unique (username_canonical),
  unique (invite_code),
  check (status in (0, 1))
);
create trigger trg_customer_updated before update on customer
  for each row execute function set_updated_at();

create table admin_user (
  id                  bigint generated always as identity primary key,
  username            text not null,
  username_canonical  text not null,
  password_hash       text not null,
  totp_secret_enc     bytea,                        -- AES-256-GCM:iv||ct||tag,KEK 注入(第三批实现)
  totp_key_version    smallint,                     -- 轮换=重加密
  totp_last_used_step bigint,                       -- 同 time step 防重放(CAS 递增,第三批)
  totp_enabled        boolean not null default false,
  status              smallint not null default 1,  -- 1=active 0=disabled
  last_login_at       timestamptz,
  created_at          timestamptz not null default now(),
  updated_at          timestamptz not null default now(),
  unique (username_canonical),
  check (status in (0, 1)),
  check ((totp_secret_enc is null) = (totp_key_version is null)),  -- 密材与版本同生同灭(第三批 AAD 绑定 key_version)
  check (not totp_enabled or totp_secret_enc is not null),         -- 启用必有密材(经上一条,版本号亦必有)
  check (totp_key_version is null or totp_key_version > 0),
  check (totp_last_used_step is null or totp_last_used_step >= 0)
);
create trigger trg_admin_user_updated before update on admin_user
  for each row execute function set_updated_at();

-- refresh token:明文不落库,存 SHA-256;旋转式 + family 吊销(spec §5.2)
create table refresh_token (
  id                 bigint generated always as identity primary key,
  family_id          uuid not null,
  principal_type     smallint not null,             -- 1=customer 2=admin
  principal_id       bigint not null,
  token_hash         bytea not null,                -- SHA-256(32 bytes)
  expires_at         timestamptz not null,
  rotated_at         timestamptz,                   -- 旋转后置位;已置位再现=重放
  revoked_at         timestamptz,                   -- 登出/改密/重放吊销
  last_used_at       timestamptz,
  created_ip         text,
  created_user_agent text,
  created_at         timestamptz not null default now(),
  unique (token_hash),
  check (principal_type in (1, 2)),
  check (octet_length(token_hash) = 32)                            -- 固化 SHA-256,防误存明文/hex 字符串
);
create index ix_refresh_token_principal on refresh_token (principal_type, principal_id);
create index ix_refresh_token_family on refresh_token (family_id);
create index ix_refresh_token_expires on refresh_token (expires_at);
```

- [ ] **Step 2: 跑 ModuleBoundaryTest 看完整性断言红(TDD 红相)**

Run(带三 export): `./gradlew test --tests "com.gabon.ModuleBoundaryTest"`
Expected: `every jooq table class has a registered owner` FAIL,消息含 `[AdminUser, Customer, RefreshToken]`(顺序不限)。红的关键行留报告。

- [ ] **Step 3: TABLE_OWNER 登记(绿相)**

`ModuleBoundaryTest.kt` 的 `TABLE_OWNER` 追加三项(值=允许访问前缀,api 包引用自家表也违规):

```kotlin
                "Customer" to "com.gabon.identity.internal",
                "AdminUser" to "com.gabon.identity.internal",
                "RefreshToken" to "com.gabon.identity.internal",
```

- [ ] **Step 4: truncate 列表补全**

`AbstractPgTest.kt` 的 `clean()`:

```kotlin
dsl.execute("truncate ledger_entry, ledger_txn, outbox, inbox, account, refresh_token, admin_user, customer restart identity cascade")
```

- [ ] **Step 5: 跑完整 Verify(16 测试绿),Commit**

```bash
git add src/main/resources/db/migration/V2__identity_core.sql src/test/kotlin/com/gabon/ModuleBoundaryTest.kt src/test/kotlin/com/gabon/AbstractPgTest.kt
git commit -m "$(cat <<'EOF'
feat: add identity core tables migration

- Add customer table with canonical username and invites
- Add admin_user table with encrypted TOTP material
- Add refresh_token table with rotation and revocation
- Register identity tables to the internal access prefix

Schema follows spec section 5.1; token plaintext never lands
in the database and TOTP secrets are stored encrypted only.
EOF
)"
```

(提交规则同 Task 1;本会话执行时追加 harness 尾注。)

---

### Task 3: AbstractIntegrationTest(PG+Valkey 双单例)+ CLAUDE.md

**Goal:** 测试基类改名并加 Valkey 单例容器(为第三批 jti 黑名单/限流铺路),文档同步。

**Files:**
- Rename+Modify: `src/test/kotlin/com/gabon/AbstractPgTest.kt` → `src/test/kotlin/com/gabon/AbstractIntegrationTest.kt`
- Modify: `src/test/kotlin/com/gabon/{RechargeIdempotencyTest,InsufficientBalanceTest,CoroutineBoundaryTest,OutboxLeaseTest}.kt`(基类名)
- Modify: `CLAUDE.md`(测试约定节、测试数)

**Acceptance Criteria:**
- [ ] 基类名/文件名为 `AbstractIntegrationTest`,PG 与 Valkey 均为整测试 JVM 复用的单例容器
- [ ] Valkey 镜像 tag 实际存在(先 `docker pull valkey/valkey:9.1-alpine` 验证;不存在则改用 Docker Hub 最近的官方 9.1 系 alpine tag 并在报告注明)
- [ ] `spring.data.redis.host/port` 属性名对照 Spring Boot 4.1 官方文档核实后再写(勿凭记忆;本批 classpath 无 redis starter,未知属性无害,但名字要留对给第三批)
- [ ] main 代码与 build.gradle.kts **零改动**(不加任何 Redis 客户端依赖)
- [ ] CLAUDE.md:测试约定节改为 AbstractIntegrationTest + PG/Valkey 双单例 + truncate 提醒保留;`check` 注释 14→16 测试
- [ ] `./gradlew check` 全绿

**Verify:** 同 Task 1 命令 → `BUILD SUCCESSFUL`,16 测试 0 失败;`docker ps` 期间可见 valkey 容器

**Steps:**

- [ ] **Step 1: git mv + 基类改造**

```bash
git mv src/test/kotlin/com/gabon/AbstractPgTest.kt src/test/kotlin/com/gabon/AbstractIntegrationTest.kt
```

文件内容改为(在 Task 2 已更新的 truncate 列表基础上):

```kotlin
package com.gabon

import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
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

    @BeforeEach
    fun clean() {
        dsl.execute("truncate ledger_entry, ledger_txn, outbox, inbox, account, refresh_token, admin_user, customer restart identity cascade")
    }

    companion object {
        // 单例容器:整个测试 JVM 复用(不 stop,JVM 退出/Ryuk 回收)
        @JvmStatic
        private val pg: PostgreSQLContainer =
            PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine")).apply { start() }

        // Valkey:第三批(jti 黑名单/限流)消费;本批仅起容器并注册连接属性,classpath 无 redis 客户端
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
        }
    }
}
```

注意:TC 2.0 的 `GenericContainer` 坐标/包名以本仓已用的 `org.testcontainers:testcontainers-postgresql` 传递引入的 core 为准(`org.testcontainers.containers.GenericContainer`);若包名不符以编译报错为准修正,**勿臆测**。另:Kotlin 对 `GenericContainer<SELF>` 自递归泛型的推断可能报 "Not enough information to infer type variable"——若遇到,用惯用解法 `class KValkeyContainer(image: DockerImageName) : GenericContainer<KValkeyContainer>(image)` 包一层,语义不变。

- [ ] **Step 2: 四个子类改基类名**

`RechargeIdempotencyTest` / `InsufficientBalanceTest` / `CoroutineBoundaryTest` / `OutboxLeaseTest`:`: AbstractPgTest()` → `: AbstractIntegrationTest()`。改完 `grep -rn "AbstractPgTest" src/` 应零命中。

- [ ] **Step 3: CLAUDE.md 两处**

① `./gradlew check` 注释:`14 测试` → `16 测试`。
② 测试约定节第一、二条替换为:

```markdown
- 集成测试一律继承 `AbstractIntegrationTest`:单例 PG + 单例 Valkey 容器整个测试 JVM 复用 + `@BeforeEach` truncate 全表;**新增表必须同步加进其 truncate 列表**,否则测试间脏数据。
- 连接注入当前用 `@DynamicPropertySource`(PG datasource + `spring.data.redis.*`;架构文档 C9 提的 `@ServiceConnection` 是目标形态,尚未采用)。Valkey 客户端依赖随第三批进入,本批仅容器与属性。
```

(第三条 ArchUnit 注意事项保留不动。)

- [ ] **Step 4: 跑完整 Verify,Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
test: rename base class and add valkey container

- Rename AbstractPgTest to AbstractIntegrationTest
- Start singleton valkey container with redis properties
- Update CLAUDE.md test conventions and test count

Batch 3 needs valkey for jti blacklist and rate limiting;
the base class provides it now so identity integration tests
land on ready infrastructure, with no main-code dependency.
EOF
)"
```

(提交规则同 Task 1;本会话执行时追加 harness 尾注。)

---

## 后续(本计划不含)

- 第三批(任务 #11):platform.security / problem+json / token / TOTP / 端点——第二批合入后另出计划
- springdoc spike(任务 #12):独立,不阻塞
