# 模块边界第一批(格子迁移 + 边界规则)实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers-extended-cc:subagent-driven-development (recommended) or superpowers-extended-cc:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 落地 spec §8 第一批——九上下文包格子成形、现有代码迁入、ArchUnit 边界规则全图生效、文档同步,回归全绿。

**Architecture:** 单 Gradle 模块 + `com.gabon.<context>.{api,internal}` 包边界,边界由 ArchUnit 表驱动断言强制(内含表所有权白名单);现有 spike 代码(ledger/outbox/feed)迁入对应格子,feed→wallet 的调用改走新建的 `wallet.api` 接口作为 B4 示范。

**Tech Stack:** Kotlin 2.4 / Spring Boot 4.1 / jOOQ 3.21(codegen 勿手改)/ ArchUnit 1.4.2 / ktlint + detekt。构建命令见 CLAUDE.md(需 `JAVA_HOME` + OrbStack Docker 环境变量)。

**Spec:** `docs/superpowers/specs/2026-07-02-module-boundaries-identity-design.md`(已批准)

**User decisions (already made):**
- 方案 A:单模块 + 包边界 + ArchUnit;B/C 是架构改案,需新 ADR + spike,不在当前路径讨论
- 九个限界上下文格子一次建齐,现有代码迁入,空格子放真实 marker 类(非 package-info)
- ArchUnit 规则:两两 internal 禁入、跨上下文仅 api、方向白名单、reporting 单向、豁免集中、**表所有权白名单(无主表失败)**
- 实施拆四批,本计划只覆盖第一批;springdoc 独立 spike 不进主线
- 广告暂并入 content(仅限展示/配置,出现商业闭环即拆)

**计划注记(执行前请知悉):**
1. **白名单边 `content→wallet`(依赖图变更,已回填 spec §4,commit a3615db)**:现有 `FeedOrchestrator`(迁入 content)调用余额读取,按 B4 正形改造为只依赖新建的 `wallet.api.WalletBalanceApi`。该边定性为 **spike 探针保留边**(C11 验收④;CLAUDE.md:spike pattern 不推翻),内容域正式设计(子项目 4)时复审;Task 5 的 B4 决策注记同步记载。
2. **marker 文件名**:ktlint `filename` 规则要求文件名与唯一顶层声明一致,故文件为 `<Context>ApiMarker.kt` / `<Context>InternalMarker.kt`(spec 写的 `<Context>Api.kt` 会被 ktlint 拒);类名不变。
3. **提交署名**:commit 模板不硬编码执行者身份;本会话内由 Claude 执行时按 harness 约定自动附加 Co-Authored-By/Session 尾注,其他执行环境(人工/其他 agent)按真实身份署名。

---

## File Structure(第一批终态)

```
src/main/kotlin/com/gabon/
  GabonApplication.kt                     (不动;根级启动装配,ArchUnit 豁免)
  platform/outbox/OutboxRepo.kt           ← 自 outbox/ 迁入
  wallet/api/WalletBalanceApi.kt          ← 新建(B4 示范 api)
  wallet/internal/ledger/AccountKind.kt   ← 自 ledger/ 迁入
  wallet/internal/ledger/LedgerService.kt ← 自 ledger/ 迁入(实现 WalletBalanceApi)
  content/internal/feed/FeedOrchestrator.kt ← 自 feed/ 迁入(依赖改为 WalletBalanceApi)
  {identity,recharge,withdraw,reward,content,media,moderation,reporting}/api/<Context>ApiMarker.kt
  {identity,recharge,withdraw,reward,media,moderation,reporting}/internal/<Context>InternalMarker.kt
src/test/kotlin/com/gabon/
  ModuleBoundaryTest.kt                   ← 新建(spec §4 规则 1-4、6)
  ArchitectureTest.kt                     (钱核包集合更新)
  其余测试仅改 import
```

---

### Task 1: wallet 格子:ledger 迁入 + WalletBalanceApi

**Goal:** `com.gabon.ledger` 迁至 `com.gabon.wallet.internal.ledger`,新建 `wallet.api.WalletBalanceApi`,feed 改依赖 api,连带更新 codegen 配置与既有 ArchUnit 钱核规则。

**Files:**
- Create: `src/main/kotlin/com/gabon/wallet/api/WalletBalanceApi.kt`
- Move+Modify: `src/main/kotlin/com/gabon/ledger/AccountKind.kt` → `src/main/kotlin/com/gabon/wallet/internal/ledger/AccountKind.kt`
- Move+Modify: `src/main/kotlin/com/gabon/ledger/LedgerService.kt` → `src/main/kotlin/com/gabon/wallet/internal/ledger/LedgerService.kt`
- Modify: `src/main/kotlin/com/gabon/feed/FeedOrchestrator.kt`(依赖类型换 api)
- Modify: `build.gradle.kts:126-127`(forced type FQCN)
- Modify: `src/test/kotlin/com/gabon/ArchitectureTest.kt:50`(钱核包集合)
- Modify: `src/test/kotlin/com/gabon/{RechargeIdempotencyTest,InsufficientBalanceTest,CoroutineBoundaryTest}.kt`(import)

**Acceptance Criteria:**
- [ ] `com.gabon.ledger` 包不复存在,钱核代码位于 `wallet/internal/ledger`
- [ ] `FeedOrchestrator` 只 import `com.gabon.wallet.api.WalletBalanceApi`,不 import wallet.internal 任何类
- [ ] ArchUnit 钱核禁协程规则覆盖 `..wallet..`/`..recharge..`/`..withdraw..`
- [ ] `./gradlew check` 全绿(codegen 因 build.gradle.kts 变更会重跑 Testcontainers,属预期)

**Verify:**
```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-25.jdk/Contents/Home
export DOCKER_HOST=unix:///Users/ethanwang/.orbstack/run/docker.sock
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
./gradlew check
```
→ `BUILD SUCCESSFUL`,9 测试全过

**Steps:**

- [ ] **Step 1: 新建 wallet.api 接口**

`src/main/kotlin/com/gabon/wallet/api/WalletBalanceApi.kt`:

```kotlin
package com.gabon.wallet.api

/** wallet 对外余额读取口:跨上下文只经 api(B4;spec §3 AccountKind 定性)。 */
interface WalletBalanceApi {
    fun balanceOf(customerId: Long): Long
}
```

- [ ] **Step 2: git mv 两个 ledger 文件并改 package 行**

```bash
mkdir -p src/main/kotlin/com/gabon/wallet/internal/ledger
git mv src/main/kotlin/com/gabon/ledger/AccountKind.kt src/main/kotlin/com/gabon/wallet/internal/ledger/AccountKind.kt
git mv src/main/kotlin/com/gabon/ledger/LedgerService.kt src/main/kotlin/com/gabon/wallet/internal/ledger/LedgerService.kt
```

两文件首行 `package com.gabon.ledger` → `package com.gabon.wallet.internal.ledger`。

- [ ] **Step 3: LedgerService 实现 WalletBalanceApi**

`LedgerService.kt` 改动(其余不动):

```kotlin
import com.gabon.wallet.api.WalletBalanceApi
// ...
@Service
class LedgerService(
    private val dsl: DSLContext,
) : WalletBalanceApi {
    // ...
    override fun balanceOf(customerId: Long): Long =
```

- [ ] **Step 4: FeedOrchestrator 改依赖 api**

`FeedOrchestrator.kt`:import `com.gabon.ledger.LedgerService` → `com.gabon.wallet.api.WalletBalanceApi`;构造参数 `private val ledger: LedgerService` → `private val ledger: WalletBalanceApi`;KDoc 中 `(LedgerService)` 提法改为 `(wallet.api)`。方法调用 `ledger.balanceOf(customerId)` 不变。

- [ ] **Step 5: build.gradle.kts forced type FQCN**

`build.gradle.kts` 两处字符串:
- `"com.gabon.ledger.AccountKind"` → `"com.gabon.wallet.internal.ledger.AccountKind"`
- `"com.gabon.ledger.AccountKindConverter"` → `"com.gabon.wallet.internal.ledger.AccountKindConverter"`

- [ ] **Step 6: ArchitectureTest 钱核包集合**

`ArchitectureTest.kt` 中 `money core packages do not depend on coroutines` 的包列表:

```kotlin
.resideInAnyPackage("..wallet..", "..recharge..", "..withdraw..")
```

(原 `"..ledger..", "..payment..", "..withdraw.."`;KDoc 注释同步改为"资金上下文(wallet/recharge/withdraw)")

- [ ] **Step 7: 测试 import 更新**

- `RechargeIdempotencyTest.kt` / `CoroutineBoundaryTest.kt`:`com.gabon.ledger.LedgerService` → `com.gabon.wallet.internal.ledger.LedgerService`
- `InsufficientBalanceTest.kt`:`com.gabon.ledger.{AccountKind,LedgerService,OWNER_CUSTOMER}` → `com.gabon.wallet.internal.ledger.{...}`(3 个 import)

(测试类在 `com.gabon` 根包,不受边界规则约束——ArchUnit 只扫 main;守卫探针留测试层是 spec 既有定案。)

- [ ] **Step 8: 跑 check 验证**

Run: 上方 Verify 命令。Expected: `BUILD SUCCESSFUL`。若 codegen 后编译报 `AccountKind` 找不到:检查 build/generated/jooq 里生成代码的 import 是否已指向新 FQCN(改了 build.gradle.kts 就会重新生成,不许手改生成物)。

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
refactor: move ledger into wallet context with api

- Move ledger package to wallet/internal/ledger
- Add WalletBalanceApi so feed depends on wallet api only
- Update forced type FQCN and money-core ArchUnit packages

First step of the module grid (spec batch 1): money core now
lives in its bounded context and exposes balance reads via api.
EOF
)"
```

---

### Task 2: outbox → platform、feed → content

**Goal:** `OutboxRepo` 迁入 `platform.outbox`,`FeedOrchestrator` 迁入 `content.internal.feed`,测试 import 跟进。

**Files:**
- Move+Modify: `src/main/kotlin/com/gabon/outbox/OutboxRepo.kt` → `src/main/kotlin/com/gabon/platform/outbox/OutboxRepo.kt`
- Move+Modify: `src/main/kotlin/com/gabon/feed/FeedOrchestrator.kt` → `src/main/kotlin/com/gabon/content/internal/feed/FeedOrchestrator.kt`
- Modify: `src/test/kotlin/com/gabon/OutboxLeaseTest.kt`、`src/test/kotlin/com/gabon/CoroutineBoundaryTest.kt`(import)

**Acceptance Criteria:**
- [ ] `com.gabon.outbox`、`com.gabon.feed` 包不复存在
- [ ] ArchitectureTest 的 `feed orchestration layer has no transactional` 仍然生效(`..feed..` 匹配 `content.internal.feed`)
- [ ] `./gradlew check` 全绿

**Verify:** 同 Task 1 的 check 命令 → `BUILD SUCCESSFUL`

**Steps:**

- [ ] **Step 1: git mv + package 行**

```bash
mkdir -p src/main/kotlin/com/gabon/platform/outbox src/main/kotlin/com/gabon/content/internal/feed
git mv src/main/kotlin/com/gabon/outbox/OutboxRepo.kt src/main/kotlin/com/gabon/platform/outbox/OutboxRepo.kt
git mv src/main/kotlin/com/gabon/feed/FeedOrchestrator.kt src/main/kotlin/com/gabon/content/internal/feed/FeedOrchestrator.kt
```

- `OutboxRepo.kt` 首行 → `package com.gabon.platform.outbox`
- `FeedOrchestrator.kt` 首行 → `package com.gabon.content.internal.feed`

- [ ] **Step 2: 测试 import**

- `OutboxLeaseTest.kt`:`com.gabon.outbox.OutboxRepo` → `com.gabon.platform.outbox.OutboxRepo`
- `CoroutineBoundaryTest.kt`:`com.gabon.feed.FeedOrchestrator` → `com.gabon.content.internal.feed.FeedOrchestrator`

- [ ] **Step 3: 跑 check**

Run: Verify 命令。Expected: `BUILD SUCCESSFUL`(本任务不动 build.gradle.kts,codegen 应 UP-TO-DATE)。

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
refactor: move outbox to platform and feed to content

- Move OutboxRepo into platform/outbox shared kernel
- Move FeedOrchestrator into content/internal/feed

Completes the code relocation for the module grid; the feed
ArchUnit rule still matches the new package via ..feed..
EOF
)"
```

---

### Task 3: 九上下文 marker 类

**Goal:** 为空格子创建真实 marker 类,使九上下文的 api/internal 包在 classpath 上可见(ArchUnit 全图断言的前提)。

**Files:**
- Create(8 个 api marker;wallet.api 已有真实接口,不需要):
  `src/main/kotlin/com/gabon/{identity,recharge,withdraw,reward,content,media,moderation,reporting}/api/<Context>ApiMarker.kt`
- Create(7 个 internal marker;wallet/content 的 internal 已有真实类,不需要):
  `src/main/kotlin/com/gabon/{identity,recharge,withdraw,reward,media,moderation,reporting}/internal/<Context>InternalMarker.kt`

**Acceptance Criteria:**
- [ ] 九上下文的 api 包与 internal 包均含至少一个编译产物类
- [ ] `./gradlew check` 全绿(ktlint 文件名规则、detekt 均过)

**Verify:** 同 Task 1 check 命令;另 `ls build/classes/kotlin/main/com/gabon/identity/api/` 应见 `IdentityApiMarker.class`

**Steps:**

- [ ] **Step 1: 创建 15 个 marker 文件**

每个文件模式相同,以 identity 为例(其余 7 个 api marker、6 个 internal marker 仅替换上下文名,PascalCase:Identity/Recharge/Withdraw/Reward/Content/Media/Moderation/Reporting):

`src/main/kotlin/com/gabon/identity/api/IdentityApiMarker.kt`:

```kotlin
package com.gabon.identity.api

/** identity 上下文 api 包 marker:格子先行,保证 ArchUnit 全图可见(spec §3)。 */
interface IdentityApiMarker
```

`src/main/kotlin/com/gabon/identity/internal/IdentityInternalMarker.kt`:

```kotlin
package com.gabon.identity.internal

/** identity 上下文 internal 包 marker(spec §3)。 */
internal object IdentityInternalMarker
```

清单(严格按此,勿多勿少):
- api marker:identity、recharge、withdraw、reward、content、media、moderation、reporting(8 个)
- internal marker:identity、recharge、withdraw、reward、media、moderation、reporting(7 个)

- [ ] **Step 2: 跑 check**

Run: Verify 命令。Expected: `BUILD SUCCESSFUL`。若 ktlint 报 filename:确认文件名与顶层声明同名(这是计划注记 2 的由来)。

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/gabon
git commit -m "$(cat <<'EOF'
feat: add api and internal markers for all contexts

- Add 8 api marker interfaces for empty context api packages
- Add 7 internal marker objects for empty internal packages

Real classes (not package-info) so ArchUnit sees every context
package on the classpath from day one, per spec section 3.
EOF
)"
```

---

### Task 4: ModuleBoundaryTest(规则 1-4 + 表所有权)

**Goal:** 表驱动的边界断言落地:两两 internal 禁入、方向白名单、platform 不反向依赖、表所有权白名单(无主表失败),并做一次负向探针验证规则真的会咬人。

**Files:**
- Create: `src/test/kotlin/com/gabon/ModuleBoundaryTest.kt`

**Acceptance Criteria:**
- [ ] spec §4 规则 1/2/3/4/6 全部有断言(规则 5 即"进 check"本身)
- [ ] **完整性断言**:`com.gabon.jooq.tables` 每个顶层表类都在 TABLE_OWNER 登记,不依赖是否已有业务代码引用;导入为空同样失败(防空过)
- [ ] 负向探针**证明 `tables.references.ACCOUNT` 形式的访问被规则 6 拦住**;若未拦住,扩展检测方式,不得提交空过规则(探针不提交)
- [ ] `./gradlew check` 全绿

**Verify:** 同 Task 1 check 命令 → `BUILD SUCCESSFUL`,测试数 9→10+

**Steps:**

- [ ] **Step 1: 写 ModuleBoundaryTest**

`src/test/kotlin/com/gabon/ModuleBoundaryTest.kt`(完整文件):

```kotlin
package com.gabon

import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Paths

/**
 * spec §4:模块边界规则(表驱动)。
 * 豁免(规则 4):com.gabon.platform..(共享内核,人人可依)、com.gabon.jooq..(生成代码,
 * 但表所有权由规则 6 单独约束)、根包启动装配(GabonApplication 等,不在任何上下文包内,天然不受约束)。
 */
class ModuleBoundaryTest {
    private val classes =
        ClassFileImporter().importPaths(
            *System
                .getProperty("archunit.main.classes")
                .orEmpty()
                .split(File.pathSeparator)
                .filter { it.isNotBlank() }
                .map { Paths.get(it) }
                .toTypedArray(),
        )

    companion object {
        /** 九个限界上下文(B4;广告暂并入 content,见 spec §3) */
        private val CONTEXTS =
            listOf(
                "identity", "wallet", "recharge", "withdraw", "reward",
                "content", "media", "moderation", "reporting",
            )

        /**
         * 规则 3:依赖方向白名单(源上下文 → 允许依赖的目标上下文)。
         * content→wallet:spike 探针保留边(feed 编排经 wallet.api 读余额),内容域正式化(子项目 4)时复审。
         * reporting→全部:后台只读各 api;反向禁止(无人把 reporting 列为目标)。
         */
        private val ALLOWED: Map<String, Set<String>> =
            mapOf(
                "recharge" to setOf("wallet"),
                "withdraw" to setOf("wallet"),
                "reward" to setOf("wallet"),
                "moderation" to setOf("content"),
                "media" to setOf("content"),
                "content" to setOf("wallet"),
                "reporting" to (CONTEXTS - "reporting").toSet(),
            )

        /** 规则 6:表所有权(jOOQ 生成表类简单名 → 唯一 owner 包前缀)。新迁移的表必须在此登记。 */
        private val TABLE_OWNER: Map<String, String> =
            mapOf(
                "Account" to "com.gabon.wallet",
                "LedgerTxn" to "com.gabon.wallet",
                "LedgerEntry" to "com.gabon.wallet",
                "Outbox" to "com.gabon.platform",
                "Inbox" to "com.gabon.platform",
            )

        private fun pkg(ctx: String) = "com.gabon.$ctx"
    }

    /** 规则 1:任何上下文不得触碰他人 internal(与规则 3 独立断言,报错信息更准) */
    @Test
    fun `no cross-context internal access`() {
        for (a in CONTEXTS) {
            for (b in CONTEXTS) {
                if (a == b) continue
                noClasses()
                    .that()
                    .resideInAPackage("${pkg(a)}..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("${pkg(b)}.internal..")
                    .check(classes)
            }
        }
    }

    /** 规则 2+3:白名单外禁止依赖对方任何包;白名单内 internal 已被规则 1 拦,即"仅 api" */
    @Test
    fun `cross-context dependencies follow the direction whitelist`() {
        for (a in CONTEXTS) {
            for (b in CONTEXTS) {
                if (a == b || b in ALLOWED.getOrDefault(a, emptySet())) continue
                noClasses()
                    .that()
                    .resideInAPackage("${pkg(a)}..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("${pkg(b)}..")
                    .check(classes)
            }
        }
    }

    /** platform 是共享内核:不得反向依赖任何业务上下文(防内核被业务污染) */
    @Test
    fun `platform depends on no business context`() {
        for (ctx in CONTEXTS) {
            noClasses()
                .that()
                .resideInAPackage("com.gabon.platform..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("${pkg(ctx)}..")
                .check(classes)
        }
    }

    /**
     * 规则 6:业务代码只许访问自己上下文拥有的 jOOQ 表;白名单无主的表直接失败。
     * jooq 包豁免只针对包依赖(规则 2/3 不禁),表所有权在此闭环(spec §4"jooq 豁免的边界")。
     */
    @Test
    fun `jooq table access is limited to the owning module`() {
        val onlyOwnTables =
            object : ArchCondition<JavaClass>("only access jOOQ tables owned by its module") {
                override fun check(
                    clazz: JavaClass,
                    events: ConditionEvents,
                ) {
                    clazz.directDependenciesFromSelf.forEach { dep ->
                        val target = dep.targetClass
                        val inTables = target.packageName == "com.gabon.jooq.tables"
                        val inRecords = target.packageName == "com.gabon.jooq.tables.records"
                        if (!inTables && !inRecords) return@forEach
                        // KotlinGenerator 的路径/引用持有类不是表本体,跳过(表本体命名 = PascalCase 表名)
                        val tableName = target.simpleName.removeSuffix("Record").removeSuffix("Path")
                        if (tableName.endsWith("Kt")) return@forEach
                        val owner = TABLE_OWNER[tableName]
                        if (owner == null) {
                            events.add(
                                SimpleConditionEvent.violated(
                                    clazz,
                                    "jOOQ 表 $tableName 未在 TABLE_OWNER 登记归属——新迁移必须登记(spec §4 规则 6)",
                                ),
                            )
                        } else if (!clazz.packageName.startsWith(owner)) {
                            events.add(
                                SimpleConditionEvent.violated(
                                    clazz,
                                    "${clazz.name} 访问 $owner 拥有的表 $tableName:跨上下文数据必须走对方 api",
                                ),
                            )
                        }
                    }
                }
            }
        classes()
            .that()
            .resideOutsideOfPackage("com.gabon.jooq..")
            .should(onlyOwnTables)
            .check(classes)
    }

    /** 规则 6 完整性:codegen 产出的每个表类必须在 TABLE_OWNER 登记——新迁移没登记就失败,不依赖是否已有业务代码引用(spec §4) */
    @Test
    fun `every jooq table class has a registered owner`() {
        val tables =
            classes
                .filter { it.packageName == "com.gabon.jooq.tables" }
                .filter { it.isTopLevelClass }
                .filter { !it.simpleName.endsWith("Kt") && !it.simpleName.endsWith("Path") }
        check(tables.isNotEmpty()) { "com.gabon.jooq.tables 导入为空:archunit.main.classes 或 codegen 异常,断言失去意义" }
        val unregistered = tables.map { it.simpleName }.filterNot { it in TABLE_OWNER }
        check(unregistered.isEmpty()) { "jOOQ 表未在 TABLE_OWNER 登记归属:$unregistered(spec §4 规则 6:新迁移必须登记)" }
    }
}
```

- [ ] **Step 2: 跑 check,确认全绿**

Run: Verify 命令。Expected: `BUILD SUCCESSFUL`。
若规则 6 因生成类命名误报(如 KotlinGenerator 的嵌套 path 类):`ls build/generated/jooq/com/gabon/jooq/tables/` 查看真实类名,调整跳过逻辑(只准收窄跳过范围,不许放宽所有权映射)。

- [ ] **Step 3: 负向探针(不提交)——必须证明 references 形式被拦**

在 `FeedOrchestrator.kt` 临时加一行 `private val probe = com.gabon.jooq.tables.references.ACCOUNT`,跑 `./gradlew test --tests "com.gabon.ModuleBoundaryTest"`,**期望规则 6 失败**(content 访问 wallet 的表)。业务代码经 `references` 顶层属性(字节码为 `TablesKt.getACCOUNT()` 返回 `Account`)的依赖预期会被 `directDependenciesFromSelf` 捕获,**但不许靠推测**:若探针未被拦住,把检测扩展到方法调用/字段访问的目标与返回类型(`methodCallsFromSelf`/`fieldAccessesFromSelf`),直到探针变红;**禁止提交一个探针测不红的规则**。revert 探针后重跑恢复绿。

- [ ] **Step 4: Commit**

```bash
git add src/test/kotlin/com/gabon/ModuleBoundaryTest.kt
git commit -m "$(cat <<'EOF'
test: add module boundary ArchUnit rules

- Add pairwise internal-access ban across nine contexts
- Add direction whitelist with reporting one-way reads
- Add platform kernel no-reverse-dependency rule
- Add jooq table ownership whitelist failing unowned tables

Table ownership closes the loophole where the jooq package
exemption would let any context read another context's tables.
EOF
)"
```

---

### Task 5: 文档同步(B4 决策注记 + CLAUDE.md)

**Goal:** 把方案 A 决策写进架构文档 B4,CLAUDE.md 的目录结构与硬规则包名对齐新格子。

**Files:**
- Modify: `docs/architecture-redesign.md`(B4 节末尾加决策注记)
- Modify: `CLAUDE.md`(硬规则钱核包名、新增模块边界硬规则、目录结构)

**Acceptance Criteria:**
- [ ] B4 含方案 A 决策注记(用户定稿措辞)
- [ ] CLAUDE.md 硬规则含模块边界与表所有权;目录结构反映九格子
- [ ] `./gradlew check` 仍绿(纯文档,快速确认无误伤)

**Verify:** 通读两文档改动处;`git diff --stat` 只含两个 md 文件

**Steps:**

- [ ] **Step 1: architecture-redesign.md B4 末尾(限界上下文段落之后)追加**

```markdown
> **实施决策(2026-07,迁移子项目 1)**:采用方案 A——单 Gradle 模块 + `com.gabon.<context>.{api,internal}` 包边界 + ArchUnit 强制。B(多 Gradle 子项目物理隔离)/ C(Spring Modulith 提前上)都不是当前实现分支,而是架构改案:B 会重做已验证的 jOOQ/codegen/Gradle 构建链;C 违反 Modulith 二期增强的分期。若要改 B/C,必须新 ADR + spike。规则与表所有权白名单见 `src/test/kotlin/com/gabon/ModuleBoundaryTest.kt`;设计全文见 `docs/superpowers/specs/2026-07-02-module-boundaries-identity-design.md`。方向白名单当前含 `content→wallet`(spike 探针保留边:feed 编排经 wallet.api 读余额;内容域正式设计时复审)。
```

- [ ] **Step 2: CLAUDE.md 三处**

① 硬规则节,钱核条目的包名:`(``..ledger..``/``..payment..``/``..withdraw..``)` → `(``..wallet..``/``..recharge..``/``..withdraw..``)`

② 硬规则节末尾新增一条:

```markdown
- **模块边界**:九上下文 `com.gabon.<context>.{api,internal}`;跨上下文只依赖对方 api + 方向白名单 + **表所有权**(业务代码只访问自己上下文的 jOOQ 表,白名单无主的表失败)——均由 `ModuleBoundaryTest` 断言,白名单/豁免集中在该文件常量里。
```

③ 目录结构节的代码块替换为:

```
docs/architecture-redesign.md   ← 权威设计文档,先读
docs/superpowers/specs/          设计 spec(模块边界+身份域等)
src/main/kotlin/com/gabon/       ← 包根 com.gabon(GabonApplication 为入口)
  platform/   共享内核(outbox、security、web;人人可依,不得反向依赖业务上下文)
  wallet/     钱包与账本(internal/ledger:双分录、幂等、守卫、AccountKind forced type)
  identity/ recharge/ withdraw/ reward/ content/ media/ moderation/ reporting/
              九上下文格子,各含 {api,internal};content/internal/feed 为 suspend 编排层
src/main/resources/db/migration/ Flyway 迁移(schema 唯一真相)
src/test/.../ArchitectureTest.kt ArchUnit 持久层/协程边界断言
src/test/.../ModuleBoundaryTest.kt 模块边界断言(方向白名单、表所有权)
config/detekt/detekt.yml         detekt 配置
```

- [ ] **Step 3: 快速确认 + Commit**

Run: `git diff --stat` → 仅 2 个 md。

```bash
git add docs/architecture-redesign.md CLAUDE.md
git commit -m "$(cat <<'EOF'
docs: record plan A decision and module grid layout

- Add approach A decision note to architecture doc B4
- Update CLAUDE.md money-core packages and directory tree
- Add module boundary hard rule with table ownership

Keeps the two guidance documents aligned with the module grid
landed in spec batch 1 so future sessions start correct.
EOF
)"
```

---

## 后续批次(本计划不展开,spec §8)

- 第二批(任务 #10):identity DDL + PG/Valkey 测试基类 —— 第一批合入后另出计划
- 第三批(任务 #11):platform.security / problem+json / token / TOTP / 端点
- springdoc spike(任务 #12):独立进行,不阻塞主线
