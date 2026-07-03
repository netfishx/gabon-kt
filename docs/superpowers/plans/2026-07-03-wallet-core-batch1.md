# 钱核完全体 批 1 实施计划(订单表 + 权限分离 + 记账 API)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers-extended-cc:subagent-driven-development (recommended) or superpowers-extended-cc:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 落地 spec §7.5 批 1——V3/V4 迁移、app role 权限分离与双连接测试基建、wallet 语义化记账 API 完全体(五方法 + 三层不变量 + 并发/幂等测试矩阵)。

**Architecture:** 模块化单体 + jOOQ-only 持久层;钱核阻塞 `@Transactional`(禁协程);双分录 + 幂等键 + 守卫 UPDATE;migration owner 与 `gabon_app` runtime role 分离,append-only 由权限保证,Σ=0 由 service 断言 + deferred trigger 双保险。

**Tech Stack:** Kotlin 2.4 / Spring Boot 4.1 / jOOQ 3.21 / Flyway 11.3 / PostgreSQL 18 (Testcontainers) / JUnit 5。

**User decisions (already made):**
- 子项目 2 = 钱核完全体;方案 A(单 spec、域垂直三批、语义化记账 API)。
- 渠道形态 = SPI + 假渠道;admin 审批端点纳入本子项目;充值为固定档位。
- 幂等键/业务来源焊死在 API 签名;调用方不传 entries、不选账户类型。
- 三层不变量(service 断言/权限/deferred trigger)第一期全上,V1 trigger 保留不动。
- 批次切分:批 1 = 本计划;批 2 recharge 域、批 3 withdraw+outbox 各自出计划。

**上游 spec:** `docs/superpowers/specs/2026-07-03-wallet-core-recharge-withdraw-design.md`(§2/§3/§7 为本批权威)。

**环境(每个 Verify 命令都需要):**
```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-25.jdk/Contents/Home
export DOCKER_HOST=unix:///Users/ethanwang/.orbstack/run/docker.sock
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
```

---

### Task 1: V3 订单表迁移 + 表所有权登记

**Goal:** 三张订单表(档位/充值单/提现单)DDL 落库,jOOQ codegen 产出类并完成表所有权登记与 truncate 列表登记。

**Files:**
- Create: `src/main/resources/db/migration/V3__wallet_orders.sql`
- Modify: `src/test/kotlin/com/gabon/ModuleBoundaryTest.kt`(TABLE_OWNER 常量,约 :69-76)
- Modify: `src/test/kotlin/com/gabon/AbstractIntegrationTest.kt`(truncate 列表,:26-29)

**Acceptance Criteria:**
- [ ] V3 含 spec §2.2 全部 check(正数、币种 regex、非空白、jsonb object、审批留痕一致性、channel > 0)与索引(keyset、status、部分唯一)
- [ ] `ModuleBoundaryTest` 完整性断言在登记前失败、登记后通过(key 为 jOOQ 类 simpleName)
- [ ] `./gradlew check` 全绿

**Verify:** `./gradlew check` → BUILD SUCCESSFUL

**Steps:**

- [ ] **Step 1: 写 V3 迁移**

```sql
-- V3__wallet_orders.sql:充值档位/充值订单/提现订单(spec §2.2)
-- 法币金额只在订单侧(cents),不进钻石账本;customer_id 仅存 id,不跨上下文 FK

create table recharge_package (
  id          bigint generated always as identity primary key,
  diamonds    bigint   not null check (diamonds > 0),
  price_cents bigint   not null check (price_cents > 0),
  currency    char(3)  not null check (currency ~ '^[A-Z]{3}$'),
  status      smallint not null default 1 check (status in (0, 1)),  -- 1=上架 0=下架
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now()
);
create trigger trg_recharge_package_updated before update on recharge_package
  for each row execute function set_updated_at();

create table recharge_order (
  id               bigint generated always as identity primary key,
  order_no         text     not null check (order_no ~ '\S'),
  customer_id      bigint   not null,
  package_id       bigint   not null references recharge_package(id),
  diamonds         bigint   not null check (diamonds > 0),      -- 档位快照,防在途改价
  price_cents      bigint   not null check (price_cents > 0),
  currency         char(3)  not null check (currency ~ '^[A-Z]{3}$'),
  channel          smallint not null check (channel > 0),
  channel_order_no text     check (channel_order_no ~ '\S'),
  status           smallint not null default 1 check (status in (1, 2, 3, 4, 5)),
    -- 1=CREATED 2=PROCESSING 3=SUCCESS 4=FAILED 5=CANCELLED(C2.4)
  created_at       timestamptz not null default now(),
  updated_at       timestamptz not null default now(),
  unique (order_no)
);
create trigger trg_recharge_order_updated before update on recharge_order
  for each row execute function set_updated_at();
create index ix_recharge_order_customer on recharge_order (customer_id, id);
-- 一渠道流水号只映射一本地单(防重之外的映射约束,spec §2.2)
create unique index ux_recharge_order_channel_no
  on recharge_order (channel, channel_order_no) where channel_order_no is not null;

create table withdraw_order (
  id                bigint generated always as identity primary key,
  order_no          text     not null check (order_no ~ '\S'),
  customer_id       bigint   not null,
  diamonds          bigint   not null check (diamonds > 0),
  payout_cents      bigint   not null check (payout_cents > 0),  -- 固定汇率换算快照
  currency          char(3)  not null check (currency ~ '^[A-Z]{3}$'),
  payout_account    jsonb    not null check (jsonb_typeof(payout_account) = 'object'),
  channel           smallint not null check (channel > 0),
  channel_payout_no text     check (channel_payout_no ~ '\S'),
  review_memo       text,
  reviewed_by       bigint,
  reviewed_at       timestamptz,
  status            smallint not null default 1 check (status in (1, 2, 3, 4, 5, 6)),
    -- 1=PENDING 2=APPROVED 3=PROCESSING 4=SUCCESS 5=FAILED 6=REJECTED(C2.4)
  created_at        timestamptz not null default now(),
  updated_at        timestamptz not null default now(),
  unique (order_no),
  check ((reviewed_by is null) = (reviewed_at is null)),  -- 同空同非空
  check (status = 1 or reviewed_at is not null)           -- PENDING 之外必有审批留痕
);
create trigger trg_withdraw_order_updated before update on withdraw_order
  for each row execute function set_updated_at();
create index ix_withdraw_order_customer on withdraw_order (customer_id, id);
create index ix_withdraw_order_status on withdraw_order (status, id);
create unique index ux_withdraw_order_channel_no
  on withdraw_order (channel, channel_payout_no) where channel_payout_no is not null;
```

- [ ] **Step 2: codegen 后跑边界测试,验证完整性断言变红**

Run: `./gradlew jooqCodegen && ./gradlew test --tests "com.gabon.ModuleBoundaryTest"`
Expected: FAIL,消息含 `jOOQ 表未在 TABLE_OWNER 登记归属:[RechargeOrder, RechargePackage, WithdrawOrder]`(顺序可能不同)

- [ ] **Step 3: 登记表所有权**

`ModuleBoundaryTest.kt` 的 `TABLE_OWNER` map 中(`"Outbox"` 条目附近)加三行,key 是 jOOQ 类 simpleName:

```kotlin
"RechargePackage" to "com.gabon.recharge.internal",
"RechargeOrder" to "com.gabon.recharge.internal",
"WithdrawOrder" to "com.gabon.withdraw.internal",
```

- [ ] **Step 4: truncate 列表加三张新表**

`AbstractIntegrationTest.clean()` 的 truncate 语句改为:

```kotlin
dsl.execute(
    "truncate ledger_entry, ledger_txn, outbox, inbox, account, refresh_token, admin_user, " +
        "customer, recharge_order, recharge_package, withdraw_order restart identity cascade",
)
```

- [ ] **Step 5: 全量验证**

Run: `./gradlew check`
Expected: BUILD SUCCESSFUL(codegen 重跑属预期,约几十秒)

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/db/migration/V3__wallet_orders.sql src/test/kotlin/com/gabon/ModuleBoundaryTest.kt src/test/kotlin/com/gabon/AbstractIntegrationTest.kt
git commit -m "feat: add wallet order tables with ownership"
```

---

### Task 2: V4 权限分离 + 双连接测试基建 + 守卫探针

**Goal:** `gabon_app` runtime role 与 migration owner 分离落地:V4 授权迁移、测试双连接(runtime=gabon_app / cleanup=owner)、权限与 trigger 探针。

**Files:**
- Create: `src/main/resources/db/migration/V4__app_role_grants.sql`
- Create: `src/test/kotlin/com/gabon/AppRoleGuardTest.kt`
- Modify: `src/test/kotlin/com/gabon/AbstractIntegrationTest.kt`
- Modify: `src/main/resources/application.yml`(flyway 段注释)

**Acceptance Criteria:**
- [ ] `gabon_app` 对 `ledger_txn`/`ledger_entry` UPDATE/DELETE 被权限拒(探针钉死)
- [ ] 不平分录即使有 INSERT 权限也在提交时被 deferred trigger 拒(探针钉死)
- [ ] 全部既有测试在 runtime=gabon_app 下通过(权限被全量测试天天验证)
- [ ] Flyway 走 owner 连接(`spring.flyway.user`),truncate 走 owner DSLContext

**Verify:** `./gradlew check` → BUILD SUCCESSFUL

**Steps:**

- [ ] **Step 1: 写探针测试(先红)**

Create `src/test/kotlin/com/gabon/AppRoleGuardTest.kt`。种子数据一律走 `ownerDsl`(与钱核 service 签名解耦):

```kotlin
package com.gabon

import com.gabon.jooq.tables.references.ACCOUNT
import com.gabon.jooq.tables.references.LEDGER_ENTRY
import com.gabon.jooq.tables.references.LEDGER_TXN
import com.gabon.wallet.internal.ledger.AccountKind
import com.gabon.wallet.internal.ledger.OWNER_CUSTOMER
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.support.TransactionTemplate

/**
 * 三层不变量的 ②/③ 层探针(spec §2.1/§2.3):
 * ② gabon_app 对 ledger 表无 UPDATE/DELETE("只追加"由权限保证);
 * ③ 有 INSERT 权限也插不进不平分录(deferred trigger 提交时拒)。
 * 种子走 ownerDsl(owner 绕过 grants 但绕不过 trigger)。
 */
class AppRoleGuardTest : AbstractIntegrationTest() {
    @Autowired
    lateinit var transactionTemplate: TransactionTemplate

    @Test
    fun `app role cannot update or delete ledger rows`() {
        val entryId = seedBalancedTxn()
        assertThatThrownBy {
            dsl.update(LEDGER_ENTRY).set(LEDGER_ENTRY.AMOUNT, 0L).where(LEDGER_ENTRY.ID.eq(entryId)).execute()
        }.hasStackTraceContaining("permission denied")
        assertThatThrownBy {
            dsl.deleteFrom(LEDGER_ENTRY).where(LEDGER_ENTRY.ID.eq(entryId)).execute()
        }.hasStackTraceContaining("permission denied")
        assertThatThrownBy {
            dsl.update(LEDGER_TXN).set(LEDGER_TXN.MEMO, "tampered").execute()
        }.hasStackTraceContaining("permission denied")
    }

    @Test
    fun `unbalanced insert is rejected at commit even though insert is granted`() {
        val account = seedAccount(OWNER_902)
        assertThatThrownBy {
            transactionTemplate.execute {
                val txnId =
                    dsl
                        .insertInto(LEDGER_TXN)
                        .set(LEDGER_TXN.BIZ_TYPE, PROBE_BIZ)
                        .set(LEDGER_TXN.BIZ_NO, "PROBE-UNBALANCED")
                        .returningResult(LEDGER_TXN.ID)
                        .fetchOne()!!
                        .value1()!!
                dsl
                    .insertInto(LEDGER_ENTRY, LEDGER_ENTRY.TXN_ID, LEDGER_ENTRY.ACCOUNT_ID, LEDGER_ENTRY.AMOUNT)
                    .values(txnId, account, 5L)
                    .execute() // 单行不平:deferred trigger 在提交时拒
            }
        }.hasStackTraceContaining("invalid")
        assertThat(dsl.fetchCount(LEDGER_ENTRY)).isEqualTo(0)
    }

    private fun seedAccount(ownerId: Long): Long =
        ownerDsl
            .insertInto(ACCOUNT, ACCOUNT.OWNER_KIND, ACCOUNT.OWNER_ID, ACCOUNT.KIND)
            .values(OWNER_CUSTOMER, ownerId, AccountKind.AVAILABLE)
            .returningResult(ACCOUNT.ID)
            .fetchOne()!!
            .value1()!!

    private fun seedBalancedTxn(): Long {
        val account = seedAccount(OWNER_901)
        val txnId =
            ownerDsl
                .insertInto(LEDGER_TXN)
                .set(LEDGER_TXN.BIZ_TYPE, PROBE_BIZ)
                .set(LEDGER_TXN.BIZ_NO, "PROBE-SEED")
                .returningResult(LEDGER_TXN.ID)
                .fetchOne()!!
                .value1()!!
        return ownerDsl
            .insertInto(LEDGER_ENTRY, LEDGER_ENTRY.TXN_ID, LEDGER_ENTRY.ACCOUNT_ID, LEDGER_ENTRY.AMOUNT)
            .values(txnId, account, 100L)
            .values(txnId, account, -100L) // 同账户两行,Σ=0,trigger 放行
            .returningResult(LEDGER_ENTRY.ID)
            .fetch()
            .first()
            .value1()!!
    }

    companion object {
        private const val PROBE_BIZ: Short = 99
        private const val OWNER_901 = 901L
        private const val OWNER_902 = 902L
    }
}
```

同一步在 `AbstractIntegrationTest` 加 owner 连接(探针种子与后续 truncate 共用),`import org.jooq.impl.DSL`。**形态钉死**:companion 里只放 private static,对子类暴露实例级 protected 属性(避免 companion 成员可见性/编译坑):

```kotlin
    // 实例侧(与 dsl 字段并列):
    /** owner 连接:truncate(受限 role 无 TRUNCATE 权限)与越权种子专用;业务路径一律走 runtime dsl。 */
    protected val ownerDsl: DSLContext
        get() = ownerDslStatic

    // companion 内(容器声明之后):
    @JvmStatic
    private val ownerDslStatic: DSLContext by lazy { DSL.using(pg.jdbcUrl, pg.username, pg.password) }
```

- [ ] **Step 2: 跑探针验证红**

Run: `./gradlew test --tests "com.gabon.AppRoleGuardTest"`
Expected: FAIL——`app role cannot update or delete ledger rows` 断言 `Expecting code to raise a throwable`(此刻 runtime 还是 owner,UPDATE 成功);`unbalanced insert...` 已绿(trigger 是 V1 既有物)

- [ ] **Step 3: 写 V4 迁移**

```sql
-- V4__app_role_grants.sql:app runtime role 与 migration owner 分离(spec §2.1 ②/§2.3)
-- 前提:本文件由 migration owner 执行;Flyway/codegen/测试三处 owner 必须一致——
-- alter default privileges 只影响执行者(owner)后续创建的对象,owner 不一致则默认授权不生效。
-- 生产部署前提(二选一):预先创建 gabon_app(推荐),或给迁移 role CREATEROLE;
-- DO guard 只负责幂等,不绕过集群权限模型。生产密码由部署侧 ALTER ROLE 预置/secret 管理。
do $$
begin
  if not exists (select from pg_roles where rolname = 'gabon_app') then
    create role gabon_app login;
  end if;
end $$;

grant usage on schema public to gabon_app;
grant select, insert, update, delete on all tables in schema public to gabon_app;
grant usage, select on all sequences in schema public to gabon_app;  -- identity 列底层 sequence

-- append-only:先 REVOKE 再收窄 GRANT(不依赖默认权限状态);"只追加"由此保证
revoke all on table ledger_txn, ledger_entry from gabon_app;
grant select, insert on table ledger_txn, ledger_entry to gabon_app;

-- 迁移史表不属于 app
revoke all on table flyway_schema_history from gabon_app;

-- 未来迁移新建对象的默认授权(免 V5+ 每张新表手工 GRANT);
-- 未来 append-only 新表在其自己的迁移里显式 REVOKE
alter default privileges in schema public grant select, insert, update, delete on tables to gabon_app;
alter default privileges in schema public grant usage, select on sequences to gabon_app;
```

- [ ] **Step 4: 测试基建切双连接**

`AbstractIntegrationTest` 三处改动:

容器初始化预建 role(生产推荐路径同款,V4 DO guard 幂等跳过;codegen 的独立 Flyway 跑道无预建,覆盖 guard 创建分支):

```kotlin
@JvmStatic
private val pg: PostgreSQLContainer =
    PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine")).apply {
        start()
        // 预建 app role(spec §2.3 生产推荐二选一之"预先创建");V4 只授权
        createConnection("").use { it.createStatement().execute("create role gabon_app login password 'test'") }
    }
```

`containers()` 里 datasource/flyway 属性拆开:

```kotlin
registry.add("spring.datasource.url") { pg.jdbcUrl }
registry.add("spring.datasource.username") { "gabon_app" }
registry.add("spring.datasource.password") { "test" }
// Flyway 显式走 owner,不与 datasource 混用(spec §2.3 连接拓扑)
registry.add("spring.flyway.url") { pg.jdbcUrl }
registry.add("spring.flyway.user") { pg.username }
registry.add("spring.flyway.password") { pg.password }
```

`clean()` 的 truncate 切 owner 连接(gabon_app 无 TRUNCATE 权限):

```kotlin
ownerDsl.execute(
    "truncate ledger_entry, ledger_txn, outbox, inbox, account, refresh_token, admin_user, " +
        "customer, recharge_order, recharge_package, withdraw_order restart identity cascade",
)
```

`application.yml` 末尾追加 **prod profile 配置契约**(不只注释——runtime/迁移用户拆分在生产是强制的,env 缺失即占位符解析失败 → 启动 fail fast;测试/ValkeyDownTest 不激活 prod profile,不受影响):

```yaml
---
# 生产配置契约(spec §2.3 连接拓扑):runtime=gabon_app,迁移走 owner;
# owner 必须与 codegen/测试一致(V4 alter default privileges 的生效前提)。
# 任一 env 缺失 → 占位符解析失败 → 启动失败(fail fast),不会静默用 owner 跑业务。
spring:
  config:
    activate:
      on-profile: prod
  datasource:
    username: gabon_app
    password: ${GABON_DB_APP_PASSWORD}
  flyway:
    user: ${GABON_DB_OWNER_USER}
    password: ${GABON_DB_OWNER_PASSWORD}
```

注:`ValkeyDownTest` 不动——它自起 PG 且 datasource=owner,Flyway 默认沿用 datasource 凭据,V4 建 role 后无人使用,不在权限探针覆盖面。

- [ ] **Step 5: 探针转绿**

Run: `./gradlew test --tests "com.gabon.AppRoleGuardTest"`
Expected: PASS(2 tests)

- [ ] **Step 6: 全量验证(整套测试在 gabon_app 下跑)**

Run: `./gradlew check`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/db/migration/V4__app_role_grants.sql src/test/kotlin/com/gabon/AppRoleGuardTest.kt src/test/kotlin/com/gabon/AbstractIntegrationTest.kt src/main/resources/application.yml
git commit -m "feat: split app role from migration owner"
```

---

### Task 3: WalletLedgerApi + LedgerService 骨架重构

**Goal:** 语义化写 API 接口落地,`creditRecharge` 迁入统一骨架(幂等门 → 开户 → 投影 → postEntries 漏斗),新增 `INSUFFICIENT_BALANCE` problem 与 `frozenOf` 读口。

**Files:**
- Create: `src/main/kotlin/com/gabon/wallet/api/WalletLedgerApi.kt`
- Modify: `src/main/kotlin/com/gabon/wallet/api/WalletBalanceApi.kt`
- Modify: `src/main/kotlin/com/gabon/platform/web/ProblemType.kt`
- Modify: `src/main/kotlin/com/gabon/wallet/internal/ledger/LedgerService.kt`
- Modify: `src/test/kotlin/com/gabon/RechargeIdempotencyTest.kt`(新签名 + 异金额重放测试)
- Modify: `src/test/kotlin/com/gabon/InsufficientBalanceTest.kt`(新签名调用点)
- Modify: `src/test/kotlin/com/gabon/ModuleBoundaryTest.kt`(postEntries 唯一入口 ArchUnit 钉子)

**Acceptance Criteria:**
- [ ] `creditRecharge(orderNo, customerId, diamonds)` 签名生效(幂等键在前)
- [ ] 同 `biz_no` 异金额重放 → false 且余额/分录不变(幂等门在守卫之前的钉子)
- [ ] `postEntries` 是唯一写 `ledger_entry` 的生产入口,写前断言 ≥2 行且 Σ=0,且由 ArchUnit 规则钉死(除 LedgerService 外生产类不得触碰 LedgerEntry 表类)
- [ ] `frozenOf` 契约同 `balanceOf`(无账户返回 0)

**Verify:** `./gradlew check` → BUILD SUCCESSFUL

**Steps:**

- [ ] **Step 1: 先改测试(新签名 + 异金额重放),验证红**

`RechargeIdempotencyTest` 改为:

```kotlin
@Test
fun `duplicate recharge credits exactly once`() {
    val customer = 100L
    val first = ledger.creditRecharge("CR-1", customer, 500)
    val second = ledger.creditRecharge("CR-1", customer, 500) // 同一 orderNo

    assertThat(first).isTrue()
    assertThat(second).isFalse() // 幂等短路
    assertThat(ledger.balanceOf(customer)).isEqualTo(500) // 只加一次
    assertThat(dsl.fetchCount(LEDGER_TXN)).isEqualTo(1) // txn 只一行
}

@Test
fun `replay with different amount is still a no-op`() {
    val customer = 101L
    ledger.creditRecharge("CR-DUP", customer, 500)
    // 同 biz_no 异金额:幂等门必须在守卫/入账之前,重放不得产生任何账务效果
    assertThat(ledger.creditRecharge("CR-DUP", customer, 999)).isFalse()
    assertThat(ledger.balanceOf(customer)).isEqualTo(500)
    assertThat(dsl.fetchCount(LEDGER_ENTRY)).isEqualTo(2)
}
```

(顶部补 `import com.gabon.jooq.tables.references.LEDGER_ENTRY`。)`InsufficientBalanceTest` 调用点改 `ledger.creditRecharge("CR-2", customer, 100)`。

Run: `./gradlew compileTestKotlin`
Expected: FAIL(新签名不存在,编译红)

- [ ] **Step 2: ProblemType 加 INSUFFICIENT_BALANCE**

`ProblemType.kt` 的 `USERNAME_TAKEN` 行后加(spec §7.1;ORDER_STATE_CONFLICT/PAYMENT_CHANNEL_ERROR 随批 2/3 消费方落地):

```kotlin
INSUFFICIENT_BALANCE(HttpStatus.CONFLICT, "/problems/insufficient-balance", "Insufficient balance"),
```

- [ ] **Step 3: 新建 WalletLedgerApi 接口**

```kotlin
package com.gabon.wallet.api

/**
 * wallet 对外记账口(spec §3.1):幂等键与业务来源焊死在签名,分录组装留在钱核内部。
 *
 * 通用契约:
 * - 返回 true=本次入账,false=同幂等键已入账(幂等短路,含异金额重放);
 * - 扣减侧余额不足抛 ProblemException(INSUFFICIENT_BALANCE),事务回滚不留任何账务痕迹;
 * - diamonds 必须为正(跨上下文契约 require fail-fast;端点输入由调用方 DTO 层先校验成 400);
 * - settle 与 release 对同一笔提现的互斥不由钱核保证,由 withdraw 状态机 CAS 单一终态保证。
 */
interface WalletLedgerApi {
    /** 充值入账:available +N / payment_clearing −N。幂等键 (RECHARGE, orderNo)。 */
    fun creditRecharge(orderNo: String, customerId: Long, diamonds: Long): Boolean

    /** 提现冻结:available −N(守卫)/ frozen +N。幂等键 (WITHDRAW_FREEZE, withdrawNo)。 */
    fun freezeForWithdraw(withdrawNo: String, customerId: Long, diamonds: Long): Boolean

    /** 提现结算:frozen −N(守卫)/ payout_clearing +N。幂等键 (WITHDRAW_SETTLE, withdrawNo)。 */
    fun settleWithdraw(withdrawNo: String, customerId: Long, diamonds: Long): Boolean

    /** 冻结解冻:frozen −N(守卫)/ available +N。幂等键 (WITHDRAW_RELEASE, withdrawNo)。 */
    fun releaseFrozen(withdrawNo: String, customerId: Long, diamonds: Long): Boolean

    /** 发奖:available +N / platform_equity −N(平台侧可为负)。幂等键 (REWARD, rewardNo)。 */
    fun grantReward(rewardNo: String, customerId: Long, diamonds: Long): Boolean
}
```

`WalletBalanceApi` 加读口:

```kotlin
    /**
     * 读取客户冻结中余额(提现在途)。契约同 balanceOf:账户不存在返回 0。
     */
    fun frozenOf(customerId: Long): Long
```

- [ ] **Step 4: LedgerService 重构(本任务先落骨架 + creditRecharge;其余四方法 Task 4)**

完整替换 `LedgerService.kt`:

```kotlin
package com.gabon.wallet.internal.ledger

import com.gabon.jooq.tables.references.ACCOUNT
import com.gabon.jooq.tables.references.LEDGER_ENTRY
import com.gabon.jooq.tables.references.LEDGER_TXN
import com.gabon.wallet.api.WalletBalanceApi
import com.gabon.wallet.api.WalletLedgerApi
import org.jooq.DSLContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

const val OWNER_CUSTOMER: Short = 1
const val OWNER_PLATFORM: Short = 0

// 业务类型 = 幂等键 biz_type(spec §3.1);已入库的值不可改
const val BIZ_RECHARGE: Short = 1
const val BIZ_WITHDRAW_FREEZE: Short = 2
const val BIZ_WITHDRAW_SETTLE: Short = 3
const val BIZ_WITHDRAW_RELEASE: Short = 4
const val BIZ_REWARD: Short = 5

/**
 * 钱核:纯阻塞 + @Transactional,禁协程(B5.1)。写入统一收口(三层不变量①,spec §2.1):
 * 五方法同构骨架 = 幂等门(txn 头冲突短路)→ 开户/守卫/投影 → postEntries 唯一分录入口。
 */
@Service
class LedgerService(
    private val dsl: DSLContext,
) : WalletBalanceApi, WalletLedgerApi {
    @Transactional
    override fun creditRecharge(
        orderNo: String,
        customerId: Long,
        diamonds: Long,
    ): Boolean =
        post(BIZ_RECHARGE, orderNo, diamonds) {
            val avail = accountId(OWNER_CUSTOMER, customerId, AccountKind.AVAILABLE)
            val clearing = accountId(OWNER_PLATFORM, 0, AccountKind.PAYMENT_CLEARING)
            bump(avail, diamonds)
            bump(clearing, -diamonds)
            listOf(avail to diamonds, clearing to -diamonds)
        }

    override fun balanceOf(customerId: Long): Long = projected(customerId, AccountKind.AVAILABLE)

    override fun frozenOf(customerId: Long): Long = projected(customerId, AccountKind.FROZEN)

    /**
     * 同构骨架(spec §3.2):require 正数 → 幂等门最先(重复请求不碰守卫)→ moves(开户/守卫/投影,
     * 返回分录清单)→ postEntries。守卫 0 行抛出 → 整个事务回滚,txn 头一并消失,不留垃圾头。
     */
    private fun post(
        bizType: Short,
        bizNo: String,
        diamonds: Long,
        moves: () -> List<Pair<Long, Long>>,
    ): Boolean {
        require(diamonds > 0) { "diamonds must be positive: $bizNo -> $diamonds" }
        val txnId =
            dsl
                .insertInto(LEDGER_TXN)
                .set(LEDGER_TXN.BIZ_TYPE, bizType)
                .set(LEDGER_TXN.BIZ_NO, bizNo)
                .onConflictDoNothing()
                .returningResult(LEDGER_TXN.ID)
                .fetchOne()
                ?.value1()
                ?: return false // 幂等短路:同 (biz_type, biz_no) 已入账
        postEntries(txnId, moves())
        return true
    }

    /** 唯一写 ledger_entry 的生产入口(spec §3.2-4):写前断言 ≥2 行且 Σ=0(三层不变量①)。 */
    private fun postEntries(
        txnId: Long,
        entries: List<Pair<Long, Long>>,
    ) {
        require(entries.size >= 2 && entries.sumOf { it.second } == 0L) {
            "unbalanced ledger txn $txnId: $entries"
        }
        entries
            .fold(
                dsl.insertInto(LEDGER_ENTRY, LEDGER_ENTRY.TXN_ID, LEDGER_ENTRY.ACCOUNT_ID, LEDGER_ENTRY.AMOUNT),
            ) { insert, (accountId, amount) -> insert.values(txnId, accountId, amount) }
            .execute()
    }

    private fun bump(
        accountId: Long,
        delta: Long,
    ) {
        dsl
            .update(ACCOUNT)
            .set(ACCOUNT.BALANCE, ACCOUNT.BALANCE.plus(delta))
            .set(ACCOUNT.VERSION, ACCOUNT.VERSION.plus(1))
            .where(ACCOUNT.ID.eq(accountId))
            .execute()
    }

    private fun projected(
        customerId: Long,
        kind: AccountKind,
    ): Long =
        dsl
            .select(ACCOUNT.BALANCE)
            .from(ACCOUNT)
            .where(
                ACCOUNT.OWNER_KIND
                    .eq(OWNER_CUSTOMER)
                    .and(ACCOUNT.OWNER_ID.eq(customerId))
                    .and(ACCOUNT.KIND.eq(kind)),
            ).fetchOne()
            ?.value1() ?: 0L

    /** 取或建账户,返回其 id。 */
    private fun accountId(
        ownerKind: Short,
        ownerId: Long,
        kind: AccountKind,
    ): Long {
        val inserted =
            dsl
                .insertInto(ACCOUNT, ACCOUNT.OWNER_KIND, ACCOUNT.OWNER_ID, ACCOUNT.KIND)
                .values(ownerKind, ownerId, kind)
                .onConflictDoNothing()
                .returningResult(ACCOUNT.ID)
                .fetchOne()
                ?.value1()
        if (inserted != null) return inserted
        return dsl
            .select(ACCOUNT.ID)
            .from(ACCOUNT)
            .where(
                ACCOUNT.OWNER_KIND
                    .eq(ownerKind)
                    .and(ACCOUNT.OWNER_ID.eq(ownerId))
                    .and(ACCOUNT.KIND.eq(kind)),
            ).fetchOne()!!
            .value1()!!
    }
}
```

注:`guardedDebit`(守卫扣减)与 `ProblemException`/`ProblemType` import 刻意不在上面清单里——本任务无调用方,未使用的 private 方法/import 会被 lint 拦下;完整实现随 Task 4 Step 3 与消费方一起进。

- [ ] **Step 4b: ArchUnit 钉死 postEntries 唯一入口(三层不变量①的自动化收口)**

`ModuleBoundaryTest.kt` 加一条规则(复用既有 `tableNameOf` 三路收集,与规则 6 同机制;放在 `jooq table access is limited to the owning module` 之后):

```kotlin
    /**
     * 三层不变量①收口钉子(钱核 spec §3.2-4):分录只能经 LedgerService.postEntries 写入——
     * 除 LedgerService 外任何生产类(含 wallet.internal 其它类)不得触碰 LedgerEntry 表类。
     * 表所有权(规则 6)只限到 wallet.internal 包,本规则收窄到单类;测试类不受限(探针在测试层)。
     */
    @Test
    fun `ledger entry table is funneled through ledger service`() {
        val onlyLedgerService =
            object : ArchCondition<JavaClass>("not touch LedgerEntry outside LedgerService") {
                override fun check(
                    clazz: JavaClass,
                    events: ConditionEvents,
                ) {
                    if (clazz.fullName == "com.gabon.wallet.internal.ledger.LedgerService") return
                    val candidates =
                        clazz.directDependenciesFromSelf.asSequence().map { it.targetClass } +
                            clazz.methodCallsFromSelf.asSequence().map { it.target.rawReturnType } +
                            clazz.fieldAccessesFromSelf.asSequence().map { it.target.rawType }
                    if (candidates.any { tableNameOf(it) == "LedgerEntry" }) {
                        events.add(
                            SimpleConditionEvent.violated(
                                clazz,
                                "${clazz.name} 触碰 LedgerEntry:分录只能经 LedgerService.postEntries 写入(spec §3.2-4)",
                            ),
                        )
                    }
                }
            }
        classes()
            .that()
            .resideOutsideOfPackage("com.gabon.jooq..")
            .should(onlyLedgerService)
            .check(classes)
    }
```

该规则对现状即绿(spike 起 LedgerService 就是唯一触碰者),是防退化钉子;其检测能力由同机制的规则 6 既有覆盖背书,不必人为制造红。

- [ ] **Step 5: 验证绿**

Run: `./gradlew test --tests "com.gabon.RechargeIdempotencyTest" --tests "com.gabon.InsufficientBalanceTest"`
Expected: PASS(3 tests)

- [ ] **Step 6: 全量验证**

Run: `./gradlew check`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/gabon/wallet/api/ src/main/kotlin/com/gabon/wallet/internal/ledger/LedgerService.kt src/main/kotlin/com/gabon/platform/web/ProblemType.kt src/test/kotlin/com/gabon/RechargeIdempotencyTest.kt src/test/kotlin/com/gabon/InsufficientBalanceTest.kt src/test/kotlin/com/gabon/ModuleBoundaryTest.kt
git commit -m "feat: expose semantic wallet ledger api"
```

---

### Task 4: 冻结/结算/解冻/发奖 + 并发幂等矩阵 + 不变量工具

**Goal:** 补齐四个写方法(含守卫扣减),交付并发/幂等测试矩阵与全量不变量断言工具(后续批次复用的测试资产)。

**Files:**
- Modify: `src/main/kotlin/com/gabon/wallet/internal/ledger/LedgerService.kt`
- Create: `src/test/kotlin/com/gabon/LedgerInvariants.kt`
- Create: `src/test/kotlin/com/gabon/WalletLedgerFlowTest.kt`

**Acceptance Criteria:**
- [ ] 四方法全部走 `post()` 骨架,守卫侧用 `guardedDebit`
- [ ] 并发双 freeze(余额仅够一次)恰一成功,败方 `INSUFFICIENT_BALANCE`
- [ ] 并发同 `biz_no` 双 credit 恰一入账
- [ ] 五方法重放各返回 false 且账务不变
- [ ] 每个测试终局 `LedgerInvariants.assertHolds`(全量账户 balance==Σ明细 + 全局 Σ=0)

**Verify:** `./gradlew check` → BUILD SUCCESSFUL

**Steps:**

- [ ] **Step 1: 写不变量工具**

Create `src/test/kotlin/com/gabon/LedgerInvariants.kt`:

```kotlin
package com.gabon

import com.gabon.jooq.tables.references.ACCOUNT
import com.gabon.jooq.tables.references.LEDGER_ENTRY
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.jooq.impl.DSL

/**
 * 全量不变量断言(spec §3.3,测试共享资产):
 * 每账户 balance == Σ ledger_entry.amount(全部账户,不只 customer)+ 全局 Σ = 0。
 * 资金链路 E2E 测试终局必跑(批 2/3 复用)。
 */
object LedgerInvariants {
    fun assertHolds(dsl: DSLContext) {
        val entrySums: Map<Long, Long> =
            dsl
                .select(LEDGER_ENTRY.ACCOUNT_ID, DSL.sum(LEDGER_ENTRY.AMOUNT))
                .from(LEDGER_ENTRY)
                .groupBy(LEDGER_ENTRY.ACCOUNT_ID)
                .fetch()
                .associate { it.value1()!! to it.value2()!!.toLong() }
        dsl.select(ACCOUNT.ID, ACCOUNT.BALANCE).from(ACCOUNT).fetch().forEach { row ->
            assertThat(row.value2()!!)
                .describedAs("account ${row.value1()} balance vs Σentries")
                .isEqualTo(entrySums[row.value1()!!] ?: 0L)
        }
        assertThat(entrySums.values.sum()).describedAs("global Σ entries").isEqualTo(0L)
    }
}
```

- [ ] **Step 2: 写测试矩阵(先红)**

Create `src/test/kotlin/com/gabon/WalletLedgerFlowTest.kt`:

```kotlin
package com.gabon

import com.gabon.jooq.tables.references.LEDGER_TXN
import com.gabon.platform.web.ProblemException
import com.gabon.platform.web.ProblemType
import com.gabon.wallet.internal.ledger.LedgerService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/** 钱核写方法验收(spec §3):守卫、并发、五方法幂等、不变量恒成立。 */
class WalletLedgerFlowTest : AbstractIntegrationTest() {
    @Autowired
    lateinit var ledger: LedgerService

    @Test
    fun `freeze then settle moves funds through frozen to clearing`() {
        ledger.creditRecharge("CR-10", 10L, 1000)
        assertThat(ledger.freezeForWithdraw("W-10", 10L, 400)).isTrue()
        assertThat(ledger.balanceOf(10L)).isEqualTo(600)
        assertThat(ledger.frozenOf(10L)).isEqualTo(400)
        assertThat(ledger.settleWithdraw("W-10", 10L, 400)).isTrue()
        assertThat(ledger.frozenOf(10L)).isEqualTo(0)
        LedgerInvariants.assertHolds(dsl)
    }

    @Test
    fun `freeze then release returns funds intact`() {
        ledger.creditRecharge("CR-11", 11L, 1000)
        ledger.freezeForWithdraw("W-11", 11L, 400)
        assertThat(ledger.releaseFrozen("W-11", 11L, 400)).isTrue()
        assertThat(ledger.balanceOf(11L)).isEqualTo(1000)
        assertThat(ledger.frozenOf(11L)).isEqualTo(0)
        LedgerInvariants.assertHolds(dsl)
    }

    @Test
    fun `insufficient balance freeze throws and leaves no trace`() {
        ledger.creditRecharge("CR-12", 12L, 100)
        assertThatThrownBy { ledger.freezeForWithdraw("W-12", 12L, 500) }
            .isInstanceOfSatisfying(ProblemException::class.java) {
                assertThat(it.type).isEqualTo(ProblemType.INSUFFICIENT_BALANCE)
            }
        assertThat(ledger.balanceOf(12L)).isEqualTo(100)
        assertThat(dsl.fetchCount(LEDGER_TXN)).isEqualTo(1) // 失败冻结的 txn 头随回滚消失
        LedgerInvariants.assertHolds(dsl)
    }

    @Test
    fun `concurrent freezes with funds for one let exactly one win`() {
        ledger.creditRecharge("CR-13", 13L, 500)
        val outcomes = arrayOfNulls<Result<Boolean>>(2)
        val start = CountDownLatch(1)
        val threads =
            (0..1).map { i ->
                thread {
                    start.await()
                    outcomes[i] = runCatching { ledger.freezeForWithdraw("W-13-$i", 13L, 400) }
                }
            }
        start.countDown()
        threads.forEach { it.join() }

        val (won, lost) = outcomes.map { it!! }.partition { it.isSuccess }
        assertThat(won).hasSize(1)
        assertThat((lost.single().exceptionOrNull() as ProblemException).type)
            .isEqualTo(ProblemType.INSUFFICIENT_BALANCE)
        assertThat(ledger.balanceOf(13L)).isEqualTo(100)
        assertThat(ledger.frozenOf(13L)).isEqualTo(400)
        LedgerInvariants.assertHolds(dsl)
    }

    @Test
    fun `concurrent credits with one biz_no post exactly once`() {
        val outcomes = arrayOfNulls<Result<Boolean>>(2)
        val start = CountDownLatch(1)
        val threads =
            (0..1).map { i ->
                thread {
                    start.await()
                    outcomes[i] = runCatching { ledger.creditRecharge("CR-14", 14L, 300) }
                }
            }
        start.countDown()
        threads.forEach { it.join() }

        // 败方 onConflictDoNothing 命中 0 行 → false;不允许异常路径
        assertThat(outcomes.map { it!!.getOrThrow() }.sorted()).containsExactly(false, true)
        assertThat(ledger.balanceOf(14L)).isEqualTo(300)
        LedgerInvariants.assertHolds(dsl)
    }

    @Test
    fun `grant reward funds customer from platform equity`() {
        assertThat(ledger.grantReward("TASK-1", 15L, 50)).isTrue()
        assertThat(ledger.balanceOf(15L)).isEqualTo(50) // platform_equity 转负(非用户账户无非负约束)
        LedgerInvariants.assertHolds(dsl)
    }

    @Test
    fun `every write method replays as a no-op`() {
        ledger.creditRecharge("CR-16", 16L, 1000)
        ledger.freezeForWithdraw("W-16", 16L, 400)
        ledger.settleWithdraw("W-16", 16L, 100)
        ledger.releaseFrozen("W-16", 16L, 300) // 同 withdrawNo 三 biz_type 独立幂等空间
        ledger.grantReward("R-16", 16L, 10)

        assertThat(ledger.creditRecharge("CR-16", 16L, 1000)).isFalse()
        assertThat(ledger.freezeForWithdraw("W-16", 16L, 400)).isFalse()
        assertThat(ledger.settleWithdraw("W-16", 16L, 100)).isFalse()
        assertThat(ledger.releaseFrozen("W-16", 16L, 300)).isFalse()
        assertThat(ledger.grantReward("R-16", 16L, 10)).isFalse()
        // 1000 − 400(freeze) + 300(release) + 10(reward) = 910;settle 只动 frozen
        assertThat(ledger.balanceOf(16L)).isEqualTo(910)
        assertThat(ledger.frozenOf(16L)).isEqualTo(0)
        LedgerInvariants.assertHolds(dsl)
    }
}
```

Run: `./gradlew compileTestKotlin`
Expected: FAIL(freezeForWithdraw 等方法不存在)

- [ ] **Step 3: LedgerService 补四方法 + guardedDebit**

在 `creditRecharge` 后加四个方法,并在 `bump` 前加 `guardedDebit`(同时补 Task 3 省略的 `import com.gabon.platform.web.ProblemException` / `import com.gabon.platform.web.ProblemType`):

```kotlin
    /** 守卫扣减:0 行 = 余额不足 → 409 problem(spec §3.1);仅用于 customer 非负账户。 */
    private fun guardedDebit(
        accountId: Long,
        amount: Long,
        context: String,
    ) {
        val rows =
            dsl
                .update(ACCOUNT)
                .set(ACCOUNT.BALANCE, ACCOUNT.BALANCE.minus(amount))
                .set(ACCOUNT.VERSION, ACCOUNT.VERSION.plus(1))
                .where(ACCOUNT.ID.eq(accountId).and(ACCOUNT.BALANCE.ge(amount)))
                .execute()
        if (rows != 1) throw ProblemException(ProblemType.INSUFFICIENT_BALANCE, "$context: account=$accountId amount=$amount")
    }

    @Transactional
    override fun freezeForWithdraw(
        withdrawNo: String,
        customerId: Long,
        diamonds: Long,
    ): Boolean =
        post(BIZ_WITHDRAW_FREEZE, withdrawNo, diamonds) {
            val avail = accountId(OWNER_CUSTOMER, customerId, AccountKind.AVAILABLE)
            val frozen = accountId(OWNER_CUSTOMER, customerId, AccountKind.FROZEN)
            guardedDebit(avail, diamonds, "freeze $withdrawNo")
            bump(frozen, diamonds)
            listOf(avail to -diamonds, frozen to diamonds)
        }

    @Transactional
    override fun settleWithdraw(
        withdrawNo: String,
        customerId: Long,
        diamonds: Long,
    ): Boolean =
        post(BIZ_WITHDRAW_SETTLE, withdrawNo, diamonds) {
            val frozen = accountId(OWNER_CUSTOMER, customerId, AccountKind.FROZEN)
            val clearing = accountId(OWNER_PLATFORM, 0, AccountKind.PAYOUT_CLEARING)
            guardedDebit(frozen, diamonds, "settle $withdrawNo")
            bump(clearing, diamonds)
            listOf(frozen to -diamonds, clearing to diamonds)
        }

    @Transactional
    override fun releaseFrozen(
        withdrawNo: String,
        customerId: Long,
        diamonds: Long,
    ): Boolean =
        post(BIZ_WITHDRAW_RELEASE, withdrawNo, diamonds) {
            val frozen = accountId(OWNER_CUSTOMER, customerId, AccountKind.FROZEN)
            val avail = accountId(OWNER_CUSTOMER, customerId, AccountKind.AVAILABLE)
            guardedDebit(frozen, diamonds, "release $withdrawNo")
            bump(avail, diamonds)
            listOf(frozen to -diamonds, avail to diamonds)
        }

    @Transactional
    override fun grantReward(
        rewardNo: String,
        customerId: Long,
        diamonds: Long,
    ): Boolean =
        post(BIZ_REWARD, rewardNo, diamonds) {
            val avail = accountId(OWNER_CUSTOMER, customerId, AccountKind.AVAILABLE)
            val equity = accountId(OWNER_PLATFORM, 0, AccountKind.PLATFORM_EQUITY)
            bump(avail, diamonds)
            bump(equity, -diamonds)
            listOf(avail to diamonds, equity to -diamonds)
        }
```

- [ ] **Step 4: 验证绿**

Run: `./gradlew test --tests "com.gabon.WalletLedgerFlowTest"`
Expected: PASS(7 tests)

- [ ] **Step 5: 全量验证**

Run: `./gradlew check`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/gabon/wallet/internal/ledger/LedgerService.kt src/test/kotlin/com/gabon/LedgerInvariants.kt src/test/kotlin/com/gabon/WalletLedgerFlowTest.kt
git commit -m "feat: complete ledger write methods with guards"
```

---

## 收尾

批 1 完成后:`./gradlew check` 全绿即为本计划验收;批 2(recharge 域)与批 3(withdraw + outbox worker)以同一 spec 为纲各自出计划。
