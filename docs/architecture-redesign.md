# gabon 平台架构重构设计文档

> 版本：v3 ｜ 日期：2026-07-02 ｜ 范围：整体架构风格 + 技术选型 + 前后对比 + 实施蓝图
> 部署基线：AWS 单区域；除 S3（源站）与被迫的阿里云 CDN 外，全部 EC2 + Docker 自建，避开所有国内云。

## 文档结构（三层）

本文档分三层，读者按需切入：

- **Part A — 止血清单**：在当前旧系统上立即执行、**不依赖重构**的高危修复。
- **Part B — 目标架构 ADR**：架构主轴、设计原则、风格、技术选型。版本采用**候选基线 + spike 验收**，不是硬锁。
- **Part C — 实施蓝图**：迁移策略、账本模型、队列规范、feed 设计、生产硬化、安全、可观测、测试等落地细节。

> 版本图例：✅ = 2026-07 已联网核实的当前稳定版；🧪 = 候选基线，实施前需 spike（建一个最小工程跑通依赖链）验收；其余为方向性目标版本，落地以官方文档为准。

---

# Part A — 止血清单（独立执行，不等重构）

这些是 review 查实的高危项，**在旧系统上就要立刻做**，不能等重构落地：

| # | 动作 | 针对的 review 问题 |
|---|---|---|
| A1 | **轮换全部入库密钥**（JWT/AWS/DB/Redis/支付），并外置到 SOPS+age，代码库清零 | 全套生产密钥明文进 git |
| A2 | **内部提现密钥**改强随机 + 常量时间比较（`MessageDigest.isEqual`）；prod 显式覆盖 | 内部密钥用仓库公开 dev 默认值 → 可无凭证批提现 |
| A3 | **鉴权翻 fail-closed**：给漏标的 `CashOrderController`/`CustomerController` 补齐角色校验，并改为默认拒绝 | opt-in 授权 fail-open，普通角色可批提现/改任意密码 |
| A4 | **对外接口默认只出 APPROVED**：`getVideoDetail` 等改为过滤 `deleted_flag` + `status=APPROVED` | `selectById` 泄露未审核/已删视频 |
| A5 | **登录限流 + 账号锁定** | 登录无限流，可暴力撞库 |
| A6 | **关掉泄密日志**：`LoggingInterceptor` 停止用 `System.out` 打印全部请求头 | Authorization / 内部密钥明文进 stdout |

> A1–A4 是资金盗取与合规事故的直接堵口，优先级最高；A5–A6 紧随其后。这些与下面的目标架构解耦，可并行推进。

---

# Part B — 目标架构 ADR

## B1. 背景与目标

现状是一套 Maven 多模块 Spring Boot 3 应用（`gabon-common` / `gabon-admin` / `gabon-service`，Java 17，Spring Boot 3.2.4）。全项目 review 暴露的问题集中在：资金一致性（双重入账、丢失更新、双花）、鉴权 fail-open、密钥入库、ID 跨节点冲突、无异步基础设施（轮询代替队列）、无可观测性。

重构**不追新**，目标是用架构把"靠人记纪律"的地方变成**强制约束**，并按平台真实重心分配工程复杂度：

> **内容/feed ＞ 媒体/CDN 成本 ＞ 互动计数 ＞ 内容审核 ＞ 增长 ＞ 变现（钱）**

钱是正确性门槛最高的一个模块，但不是产品中心；主战场在内容、feed、媒体、审核与成本。**本文档据此把 feed 主路径提升为 Part C 的一等小节（C4），与账本（C2）并列。**

## B2. 约束与部署基线

| 约束 | 取值 |
|---|---|
| 规模 | 真实生产，中小规模（日活几万~几十万），单区域 |
| 云 | AWS 绑定，预算敏感 |
| 自建策略 | 大部分基础设施在 EC2 上以 Docker 自建 |
| 强制项 | 对象存储用 **S3**；CDN 用 **阿里云 CDN**（公司统一规定，唯一阿里云依赖） |
| 规避项 | 除被迫的阿里云 CDN 外，**避开所有国内云服务** |
| 审核合规 | 海外/东南亚（通用内容安全即可，无中文政治敏感硬要求） |
| 语言/构建/持久层 | **Kotlin/JVM + Gradle Kotlin DSL + jOOQ**（目标系统唯一组合；迁移期一次性适配器例外见 C1） |

## B2.1 分期范围（防止过度设计）

> 为避免"观测/密钥/队列基础设施自己变成一个新项目"，明确分期：第一期只上"修当前真实风险"所必需的，增强项进二期。

- **第一期技术栈（收敛）**：**JDK 25 LTS** + Kotlin/JVM 2.4 + Gradle Kotlin DSL 9.6 + Spring Boot 4.1 + jOOQ KotlinGenerator + Flyway + PostgreSQL 18 + Valkey + Caddy + SOPS+age + 自建 ffmpeg worker + Prometheus/Grafana（指标+面板+告警）+ 结构化日志 + ArchUnit（边界强制）+ PostgreSQL 全文检索 + kotlinx.coroutines（仅编排层，见 B5.1）。
- **JDK 基线（写死）**：开发 / CI / Docker build / runtime **全部 JDK 25 LTS**；Gradle toolchain 固定 `JavaLanguageVersion.of(25)`、Kotlin `jvmTarget = 25`；**不兼容 JDK 17、不承诺 JDK 21**（JDK 21 仅历史讨论）。Spring Boot"最低 Java 17"只作官方兼容事实，不作项目基线。
- **二期增强（按触发条件上）**：Spring Modulith、Tempo 链路 + Loki 日志平台、独立 worker PG 实例 / NATS、OpenBao、审计 WORM、Meilisearch/OpenSearch、mTLS、Spot 深度优化。
- **不算过度设计（第一期必做，因为在修真实风险）**：jOOQ + Flyway + PG、PITR/热备、outbox/inbox、幂等键、CAS、keyset、S3/CDN 源站 POC、双分录账本。

---

## B3. 核心设计原则

1. **一致性分区**：钱走强一致（同库 ACID + 幂等键 + 状态 CAS）；内容/计数/feed 走最终一致（领域事件 + 缓存 + 定期回刷）。全局第一原则，也是旧架构最缺的。
2. **禁止读-改-写整实体**：金额/状态一律走显式列级原子 SQL 或带前态守卫的 CAS，绝不 `SELECT *` → 内存改 → 全实体写回。
3. **对外副作用移出事务**：出款、回调、通知等外部 HTTP 走事务性 **outbox**，不在 DB 事务内同步调用。
4. **默认拒绝**：鉴权默认关闭、显式白名单放行。
5. **密钥零入库**：一律运行时注入。
6. **边界靠工具强制，不靠约定**：模块边界用 ArchUnit 测试守护（Modulith 二期，见 B4）。
7. **关键事件必须持久可重放**：资金相关的跨模块反应走 outbox → 队列，不用进程内 fire-and-forget（见 B5）。

## B4. 架构风格：模块化单体 + 边界强制

| | 旧架构 | 新架构 | 理由 |
|---|---|---|---|
| 风格 | 两个应用（admin:10002 / service:8082）**共享同一 MySQL 库** `gabon_admin` | **模块化单体**：单可部署件，内部按限界上下文分模块；admin 作为其中一个模块/入口，**不再共享同库直写** | 共享库直写是最脆的耦合（review 确认 admin 改密会覆盖 service 的余额列）。中小规模 + 支付重域，微服务会把本可一个 ACID 事务解决的加/扣钻石拆成分布式事务——正是要避免的。模块化单体享受单进程 ACID，同时切好边界，未来 feed 真成瓶颈可低成本抽出（届时用 Go 只读服务） |
| 模块协作 | 跨模块直接调 mapper / 共享表 | **模块显式 service API** + **进程内领域事件**（见 B5）；资金上下文共享账本 API 而非直写表 | 解耦但不引入分布式事务 |
| **边界强制** | 无 | **第一期 ArchUnit 测试**：禁止跨模块注入 Mapper、禁止跨模块访问 `*.internal` 包、模块依赖方向在测试里断言（Spring Modulith 作二期增强） | 光靠“约定禁止直写表”挡不住腐化；三个月就退回大泥球。ArchUnit 足以硬性守护边界，无需一期就上 Modulith |

**限界上下文**：身份鉴权 · 钱包与账本 · 充值 · 提现出款 · 奖励（任务/签到/VIP） · 内容（视频/评论/点赞/关注/feed） · 媒体流水线 · 内容审核 · 报表后台。每个模块暴露 `api` 包，内部实现放 `internal` 包，跨模块只允许经 `api` + 领域事件；ArchUnit 断言这条。

> **实施决策(2026-07,迁移子项目 1)**:采用方案 A——单 Gradle 模块 + `com.gabon.<context>.{api,internal}` 包边界 + ArchUnit 强制。B(多 Gradle 子项目物理隔离)/ C(Spring Modulith 提前上)都不是当前实现分支,而是架构改案:B 会重做已验证的 jOOQ/codegen/Gradle 构建链;C 违反 Modulith 二期增强的分期。若要改 B/C,必须新 ADR + spike。规则与表所有权白名单见 `src/test/kotlin/com/gabon/ModuleBoundaryTest.kt`;设计全文见 `docs/superpowers/specs/2026-07-02-module-boundaries-identity-design.md`。方向白名单当前含 `content→wallet`(spike 探针保留边:feed 编排经 wallet.api 读余额;内容域正式设计时复审)。

## B5. 一致性与事件模型（三种异步机制的分工）

文档同时用到三种机制，**必须分清何时用哪个**，否则会重蹈旧版 `tryAutoClaimReward` 那种"同步事务里塞副作用 → rollback-only"的坑：

| 机制 | 用途 | 可靠性 | 何时用 |
|---|---|---|---|
| **进程内领域事件**（`@TransactionalEventListener` AFTER_COMMIT） | 同进程、可重建/幂等的轻反应 | ⚠️ **崩溃即丢**（内存态，提交后到处理完之间挂掉就没了） | 仅限**非关键、可从真相源重建**的反应（如刷新缓存） |
| **事务性 outbox → 队列** | 跨模块、**关键**反应 | ✅ 持久 + 重试 + 死信 | **资金相关**跨模块反应（充值到账 → 发奖、提现 → 出款派发、回调 → 通知）**必须走这条** |
| **PG `SKIP LOCKED` 队列** | 后台 worker 拉取型任务 | ✅ 持久 + lease + 重试 | 转码、审核、报表等 worker 任务（见 C3） |

**铁律**：只要"丢了会导致用户付了钱没拿到东西"，就必须持久化（outbox/队列），不能用进程内事件。

## B5.1 并发模型（协程边界，硬规则）

> 引入 `kotlinx.coroutines` 作为**受限能力**（仅编排层）。**理由必须写清**：用于**结构化并发、超时、取消**（feed 多源 fan-out、外部 IO 编排），**不是**为了"提升吞吐"——廉价阻塞并发由 **JDK 25 虚拟线程**覆盖。协程是语言表达力，不是吞吐手段。

| 层 | 并发方式 | 协程 |
|---|---|---|
| 资金写路径（账本 / 充值 / 提现 / 余额投影 / 提现状态机） | jOOQ blocking + `@Transactional` + 虚拟线程 | **禁止** |
| outbox / inbox | 纯阻塞、同事务写入 | **禁止** |
| feed / read 编排 | 多源 fan-out、并发合并、超时、取消 | **允许** |
| 外部 IO 编排（审核 API / 转码派发 / 支付网关查询） | 结构化并发 + 超时 / 取消 | **允许**，但**落库动作必须回到阻塞 `@Transactional` service 边界** |

**禁止规则**（ArchUnit / 约定测试钉死，见 C9）：`suspend fun` 上**不加** `@Transactional`；事务 service **不调用**挂起函数；协程代码**不直接持有**事务边界。

**允许形态**：`suspend 编排 → 阻塞 @Transactional service`；**禁止**：`@Transactional suspend → 多个挂起调用`。

## B6. 核心技术选型（候选基线 + spike 验收）

> ⚠️ 版本策略更新：本表版本为**候选基线**，不是硬锁。Kotlin + Gradle + Boot 4.1 会牵动整条依赖链（Kotlin 编译器/Gradle 配置缓存/Servlet 6.1/Tomcat 11、springdoc、jOOQ、jjwt、连接池、拦截器），**实施前必须先搭一个最小 spike 工程跑通整链**（见 C11）。这与"不追新"原则一致：选支持期内的稳定版，但用 spike 兜底新版本的生态风险。

| 组件 | 选型 | 候选版本 | 备注 |
|---|---|---|---|
| 语言 | **Kotlin/JVM** | **Kotlin 2.4.x + JDK 25 LTS** ✅ | 全链 JDK 25（开发/CI/Docker/runtime）；`jvmTarget = 25`（Kotlin 自 2.3 起可生成 JDK 25 字节码）。**不兼容 JDK 17、不承诺 JDK 21**——新项目不背旧包袱 |
| 构建 | **Gradle Kotlin DSL** | **Gradle 9.6.1** ✅ | **spike 已验证**:9.6.1 + KGP 2.4 + JDK 25 整链跑通、配置缓存 stored/reused、codegen 增量不重跑（Kotlin 官方矩阵滞后已排除）。toolchain 固定 `JavaLanguageVersion.of(25)`；启用 Wrapper、version catalog、dependency locking、configuration cache、build cache、parallel。旧 Maven 项目只作迁移输入，不进目标构建链 |
| 应用框架 | Spring Boot | **4.1.x** 🧪 | 基于 Spring Framework 7、Java 17+；选当前支持期内版本，4.1 为一期候选（官方策略：major≥3 年、minor≥12 个月，建议迁最新 supported）。**生态兼容需 spike**（C11） |
| 模块边界 | **ArchUnit**（一期）；Spring Modulith（二期） | ArchUnit 1.x | ArchUnit 断言依赖方向/包访问已够；Modulith 二期增强 |
| 并发库 | **kotlinx.coroutines**（仅编排层） | 1.10.x 🧪 | 结构化并发/超时/取消，用于 feed/外部 IO；**禁跨 `@Transactional`**，钱核用虚拟线程（见 B5.1） |
| 持久层 | **jOOQ** OSS Edition（Apache-2.0） | **3.21.5** ✅ | 目标系统**唯一**持久层。SQL-first + 编译期类型安全 + **KotlinGenerator** 从 schema 代码生成；PG 18 dialect + `ON CONFLICT`/`RETURNING`/`SKIP LOCKED`/keyset/**forced type+Converter** 均 spike 验证（C11）。codegen 用**自定义 Gradle task 调 `GenerationTool`**（testcontainers→flyway→codegen 一体，官方插件不便协调临时容器），非官方插件。**禁止 MyBatis/MyBatis-Plus/JPA/分散 JdbcClient**（见 B7.2 硬规则） |
| 连接池 | **HikariCP**（Boot 默认） | 随 Boot | 替换旧版 **Druid**：更轻，且撤掉一个阿里系依赖。若需 Druid 的 SQL 监控面板，用 Grafana + p6spy 替代 |
| Schema 迁移 | **Flyway** | 10.x/11.x 🧪 | 旧版有裸 `V1.x__` SQL，迁移治理要重做；Flyway 需确认 Boot 4 集成 |
| 主库 | PostgreSQL | **18.x（≥18.4）** ✅ | 一期目标，无回退。原生 `uuidv7()`、`FOR UPDATE SKIP LOCKED`、部分索引、生成列；PG18/JDBC/`uuidv7()` 由 C11 spike 验收，不通过则暂停评审 |
| 缓存/计数 | **Valkey**（替代 Redis） | **9.1.x** ✅ | BSD 许可、Linux Foundation 托管、Redis 命令高度兼容；淘汰策略见 B7/C4；许可分析见 B8 |
| 反向代理 | **Caddy** | **2.11.x** ✅ | 与前端保持一致；自动 HTTPS、HTTP/3 |
| API 风格 | REST + OpenAPI（springdoc） | springdoc 3.x 🧪 | 端集成层见 B7；springdoc 对 Boot 4 兼容需 spike |
| 搜索 | **PostgreSQL 全文检索**（一期，随 PG） | 随 PG | 独立搜索引擎（Meilisearch/OpenSearch）为二期触发项，不进核心栈 |
| 队列/异步 | PostgreSQL（`SKIP LOCKED`）+ outbox | 随主库 | 规范见 C3；量大再上自建 NATS 2.x；**不上 Kafka** |
| 转码 | 自建 **ffmpeg** worker | **7.x** ✅ | 固定 Docker 镜像 tag；Spot + 临时盘 scratch |
| 审核 | 自建流水线（开源模型 + 文本过滤 + 人审） | — | 一期单一路径；误杀/漏审率超阈值再作二期 ADR 引入 AWS Rekognition |
| 可观测 | **一期**：结构化日志 + Prometheus 3.x + Grafana 12.x + 告警；**二期**：Loki 3.x / Tempo 2.x / OTel Agent 2.x | — | SLO 与告警见 C8；先别把观测栈做成新项目 |
| 密钥 | **SOPS+age**（第一期，静态密钥、零运行服务） | — | 需动态/短时凭证+审计时升级 OpenBao；见 B8；代码库零密钥 |
| 对象存储 | Amazon S3 | AWS SDK for Java **2.x** | Kotlin/JVM 直接使用 Java SDK；源站保护见 C10 |
| CDN | 阿里云 CDN | — | 被迫统一规定 + 原生 URL 鉴权；PoP 覆盖需匹配 SEA 受众（C10） |
| JWT | `io.jsonwebtoken` jjwt | **0.12.x** | jti 黑名单存储见 C7 |
| 容器 | Docker Engine / Compose | 当前稳定 | |

## B7. 逐项选型：前后对比 + 理由

### B7.1 语言 / 框架

| | 旧 | 新 | 理由 |
|---|---|---|---|
| 语言 | Java 17 | **Kotlin/JVM 2.4.x（JDK 25 LTS 全链）** | 团队已统一到 Kotlin + Gradle。Kotlin 的 null-safety、data class、sealed class、扩展函数更适合把资金状态机、DTO、错误模型写成显式类型；仍运行在 JVM/Spring 生态上，不牺牲事务、连接池、jOOQ、AWS SDK 等成熟能力。已认真评估 Go：优势只在 feed/计数高并发读，那是未来抽离点，不是现在，也不是钱 |
| 构建 | Maven 多模块 | **Gradle Kotlin DSL 9.6.x** 🧪 | 构建速度是硬约束。Gradle 的 daemon、configuration cache、build cache、并行构建和任务输入缓存更适合 jOOQ codegen + Flyway + Testcontainers + ArchUnit 的目标链路；旧 Maven 项目只作为迁移输入 |
| 框架 | Spring Boot 3.2.4 | **Spring Boot 4.1.x + Kotlin support** 🧪 | 选当前支持期内版本，4.1 为一期候选（官方建议迁最新 supported release）；Spring Boot Kotlin 支持要求 Kotlin 2.2.x+，并建议 `kotlin-spring` 插件打开 Spring 代理类。**代价**：牵动 Kotlin 编译器、Gradle 配置缓存、Servlet 6.1/Tomcat 11、springdoc、jOOQ、jjwt、连接池、拦截器整链，须 spike 验收（C11） |

框架变化不只是"换语言"，而是**把状态、空值、构建和 SQL 生成都变成强约束**：消灭旧版三个支付 provider ~250 行复制粘贴（抽公共基类 + 签名/HTTP/JSON 助手）、两份重复 `JWTUtil` 合一；资金状态机用 `enum` / `sealed` 类型表达，DTO 与配置用 constructor binding，避免可空字段和字符串状态码散落。

### B7.2 持久层（最关键的一项）：主选 jOOQ

> 决策更新：主持久层由 MyBatis 调整为 **jOOQ**。理由——gabon 这次最大的 DB 风险不是"少写 SQL"，而是"**SQL 必须精确、可审查、可测试**"。jOOQ 仍是 SQL-first（非 ORM），但用**代码生成**把表/字段/类型带进**编译期**：字段改名、结果映射漂移、`SELECT *`、动态条件漏写，MyBatis 留在字符串里靠 review/测试兜底，jOOQ 直接编译不过。旧版的 bug 已证明"靠纪律"不够。

| | 旧 | 新 | 理由 |
|---|---|---|---|
| 持久层 | **MyBatis-Plus 3.5.5**，`updateById(entity)` 全实体回写 | **jOOQ 3.21.x + KotlinGenerator**（SQL-first + 编译期类型安全 + schema 代码生成） | 把"字段/映射/SELECT */动态条件"风险从运行期提前到编译期；PG 特性一等支持。**不用 JPA/Hibernate**（隐式 flush）；**禁用 MyBatis-Plus/BaseMapper** |
| 余额变更 | 读实体 → 内存改 → 写回 | `update(...).set(BAL, BAL.add(amount)).where(ID.eq(id).and(BAL.ge(amount)))` | TOCTOU 与丢失更新在同一条原子 SQL 里消除；条件类型安全 |
| 幂等 | `transaction_no` 无唯一约束 | `insertInto(...).onConflictDoNothing()` + `(biz_type, biz_no)` 唯一约束；账本只追加、余额为投影 | jOOQ 原生 `ON CONFLICT`；堵死双重入账与重复领奖（详见 C2） |

**硬规则（单一持久层，零例外）**：

> **目标系统所有数据库访问统一使用 jOOQ**。所有新模块、资金账本、outbox、队列、feed、报表、后台查询一律通过 `DSLContext` 实现。**禁止引入 MyBatis、MyBatis-Plus/BaseMapper、JPA/Hibernate、以及分散进业务代码的 JdbcClient**（需要裸 SQL 时用 jOOQ 的 plain SQL API，不另开 JdbcClient 入口）。唯一例外：迁移旧系统时如需读取旧 MySQL，可在**一次性 migration adapter** 中临时使用旧 Mapper——该 adapter **不进入目标系统运行时**（详见 C1）。ArchUnit 断言业务代码不依赖上述被禁包。Kotlin 里写 jOOQ 条件必须使用 `.eq()` / `.ge()` 等 DSL 方法，**禁止用 `==` / `>=` 误写成 Kotlin operator/equals 语义**。**分页硬规则**：分页必须**显式表达**（优先 keyset）；复杂查询的 data query 与 count query **分开手写**；**禁止对 JOIN/GROUP BY/DISTINCT/HAVING/UNION 自动推导 count**（即 MyBatis-Plus 分页插件式隐式改写——这轮真实事故来源）；最终 SQL 必须**可日志化、可测试、可 review**。

- **代码生成挂到 Flyway + Gradle**：jOOQ Kotlin 代码从**迁移后的 schema** 生成（CI 里对 Testcontainers PG 跑 Flyway → jOOQ KotlinGenerator）。`jooqCodegen` 是 Gradle 独立增量任务（声明 in/out，支持 UP-TO-DATE；`@CacheableTask`/远端 build-cache 待正式骨架评估，见 C6），输入为 Flyway migration 与 codegen 配置；schema 成为唯一真相，强化"编译期对齐 schema"。

**取舍（诚实说明）**：① 团队熟 MyBatis，jOOQ 的 DSL 有学习曲线（但心智仍是 SQL，迁移成本可控）；② Kotlin + Gradle 引入新的构建约束，但换来明显更好的增量构建/codegen 体验；③ jOOQ **OSS Edition（Apache-2.0）只支持最新版开源库/JDK**——我们用 PG 18 + JDK 25，正在支持范围内，无许可证问题（对比 Valkey/Vault 的许可坑）。

### B7.3 主数据库 / ID

| | 旧 | 新 | 理由 |
|---|---|---|---|
| 引擎 | MySQL 8，单库共享 | **PostgreSQL 18.x**（Docker on EC2） | `SKIP LOCKED`、部分索引、生成列、原生 `uuidv7()`；拆掉共享库耦合。迁移代价见 C1 |
| ID | `IDUtil` 硬编码 `Snowflake(1,1)`，多实例撞 ID | **内部 PK 用 identity/bigint；对外业务号（订单/流水号）用 UUIDv7** | review 痛点是**资金流水号**撞，不是所有 PK。分场景：高写表内部 PK 用 8 字节 bigint 省索引，只有对外号用 16 字节 UUIDv7（时间有序、无 worker 协调）。避免"全表 UUID 主键"的索引膨胀 |

### B7.4 缓存 / 计数（修正淘汰策略矛盾）

| | 旧 | 新 | 理由 |
|---|---|---|---|
| 引擎 | Redisson **+** Jedis 混用 | **Valkey 9.1**，统一一个客户端（Lettuce） | 见 B8 许可分析；混用两套客户端是无谓复杂度 |
| **计数真相 & 淘汰** | `like_count` 靠 Redis key TTL 7 天，重复计数/丢计 | **真相在 PG**；Valkey 分两类键、两种策略：<br>① **纯读缓存 / feed ZSET**（可重建）→ `allkeys-lru` 淘汰；<br>② **计数缓存 / 去重键** → **短周期回刷 PG**，可丢可重建。**边界**：关系型互动（点赞/关注/评论）的**关系记录不丢**（PG `user_like` 等唯一表是真相，count 从关系表重算）；只有**播放数/热度分可近似**（缓存丢失重建即可） | 修正 v1 的"既说只是缓存、又配 noeviction"矛盾：缓存就该淘汰；只有不可重建数据才 noeviction。这里明确真相在 PG，缓存用 LRU，计数走“写缓冲 + 频繁回刷”，且仅播放/热度可近似、关系型互动不丢 |

### B7.5 反向代理 / API / 观测 / 部署

| | 旧 | 新 | 理由 |
|---|---|---|---|
| 反向代理 | 无统一 | **Caddy 2.11.x** | 与前端一致；自动 HTTPS、HTTP/3、配置极简 |
| API / 端层 | 端 BFF（历史） | **REST + OpenAPI** 直连；端适配薄层由前端/AI 生成（呼应"AI 消除胶水层"演进），不再维护独立 BFF 进程 | 中小规模 + 单体，端直连后端 REST 足够；版本化用 URL 前缀 `/v1` |
| 观测 | `System.out` 打印全部头、`printStackTrace` 吞异常 | **一期：结构化日志 + Prometheus/Grafana + 告警**（Loki/Tempo/OTel 二期），SLO 见 C8 | 旧日志无级别又泄密 |
| 部署 | 双应用 | **EC2 + Docker Compose 分三组**：应用组（app×N + Caddy）/ 数据组（PG + 热备 + Valkey）/ worker 组（转码+审核，Spot）；HA/发布见 C6 | 转码 CPU 尖峰不与 API、钱库抢资源 |

### B7.6 媒体 / CDN / 审核（详见 C4/C10）

| | 旧 | 新 | 理由 |
|---|---|---|---|
| 转码 | MediaConvert + 每 30s 轮询 | **自建 ffmpeg 7.x**（Spot、幂等、S3 事件触发、按热度懒转码） | 预算敏感；轮询低效且脆（旧版有 `substring(0,-1)` 崩溃） |
| CDN/防盗链 | 手搓 `md5` 且已注释，HLS 裸奔 | **阿里云 CDN 原生 URL 鉴权**，密钥由 SOPS+age 管理；源站保护见 C10 | 手搓且未启用 = 形同虚设 |
| 审核 | 越权泄露未审核视频 | **自建流水线**（开源模型 + 文本过滤 + 人审），默认只出 APPROVED | 海外合规无中文政治敏感硬要求 → 开源可行；避国内云 |

## B8. License 意识选型（避免锁定 / 保持开源）

预算敏感 + 避开国内云 + 自建优先，天然要求对**许可证**敏感：

- **缓存：Valkey 9.1 替代 Redis。** Redis 8 现为 **RSALv2 / SSPLv1 / AGPLv3 三许可**——其中 AGPLv3 虽是 OSI 认可的开源，但 copyleft 对闭源商用不友好，RSAL/SSPL 更非 OSI 开源。**Valkey** 是 Redis 7.2 的 BSD 许可分支，Linux Foundation 托管、命令高度兼容、团队 Redis 经验可平移。选它是**低退出成本 + 许可证干净**。（注：Valkey 是 AWS ElastiCache 默认这一点，只作生态背书；我们自建 Docker，不用 ElastiCache，其价格优势与本部署无关。）
- **密钥：第一期定 SOPS+age。** 静态密钥加密进 git、零常驻服务、运维最轻，契合"已自建一堆有状态服务、不想再多养一个"。**升级到 OpenBao** 的触发条件：需要动态/短时数据库凭证、访问审计或租约（OpenBao 是 HashiCorp Vault 的 Linux Foundation / MPL 2.0 开源分支；Vault 本身已转 BUSL 非开源）。二者皆开源、无许可坑。
- **连接池：HikariCP 替代 Druid**——顺带撤掉阿里系依赖。
- **持久层：jOOQ OSS Edition 是 Apache-2.0**（用于 PostgreSQL 等开源库）；jOOQ runtime/meta/codegen 均 Apache-2.0、生成代码归属自有——许可证层面无 Valkey/Redis 那类问题。
- 可观测栈（Prometheus/Grafana/Loki/Tempo）均可自托管、许可证无坑（Grafana/Loki 为 AGPLv3，自托管内部使用无碍）；分期见 B2.1（一期只上 Prometheus/Grafana）。

---

# Part C — 实施蓝图

## C1. 数据迁移与切换策略

> ⚠️ **承重假设**：v1 写"未要求保留旧数据，故可换 PG"。但这是**有真实用户余额与资金流水的生产系统**——余额/账本历史**不能丢弃**。本节按"需迁移真实数据"处理；若业务确认可清零重来，则本节退化为建库脚本。

**策略：绞杀者（strangler）渐进迁移，非大爆炸。**

1. 新系统与旧系统并行，Part A 止血先在旧系统落地。
2. 按限界上下文分批切：**先切读多写少、无强资金一致的模块**（内容/feed 只读、报表），**钱包/账本最后切**并做双写对账窗口。
3. **余额/账本迁移必须对账**：迁移后按用户逐一核对 `balance == Σ ledger`，不平不切流。

**MySQL → PostgreSQL 方言迁移清单**（不只是换驱动）：

| MySQL | PostgreSQL | 处理 |
|---|---|---|
| `auto_increment` | `identity` / `bigserial` | 建表脚本改写 |
| `tinyint(1)` | `boolean` / `smallint` | 类型映射用 **jOOQ forced types / Converter / Binding** |
| `ON UPDATE CURRENT_TIMESTAMP` | 无 → **触发器** | 加 `updated_at` 触发器 |
| NULL 唯一索引语义 | 默认多 NULL 允许；PG 15+ 可 `NULLS NOT DISTINCT` | 逐个索引确认软删除语义 |
| 时区 | `timestamptz` + 统一 UTC 存储 | 修旧版时区混用 |
| 分页 `LIMIT off,cnt` | `LIMIT cnt OFFSET off` / keyset | 换 keyset（C4） |
| 旧 MyBatis SQL | 仅作**迁移输入**参考 | 目标系统不保留 Mapper/XML；旧 SQL 逻辑用 jOOQ 重写 |

**迁移治理**：旧版裸 `V1.x__` SQL 迁到 **Flyway** 管理；迁移脚本纳入 CI，禁止手改生产 schema。

**迁移适配器例外**：从旧 MySQL 读数据的**一次性 migration adapter** 可临时依赖旧 MyBatis Mapper；它是独立迁移工具，**不进入目标系统运行时**，迁移完成即废弃。这是目标架构"统一 jOOQ、禁 MyBatis"的唯一例外。

## C2. 资金账本详细模型（重构地基）

账本决定 schema、jOOQ codegen、事务边界、outbox/inbox、迁移对账与并发测试，是整个重构的地基。本节把双分录 DDL、关键 jOOQ 写法、CAS 状态流转、幂等键、对账查询定下来。（下方 jOOQ 为示意，非最终签名。）

### C2.1 账户体系与记账约定

- **账户最小集（第一期）**：`customer_available`（用户可用）、`customer_frozen`（冻结/提现锁定）、`payment_clearing`（充值在途，资产）、`payout_clearing`（出款在途，资产）、`platform_equity`（平台自有/发放来源，作发奖与调账的对手方）。**注意**：用户余额本身就是平台对用户的负债，已由 `customer_available/frozen` 表达，故**不单设 `platform_liability`**（避免与用户账户重复计量）；"全平台对用户负债" = `Σ(customer_available + customer_frozen)`，是**报表口径**，不设独立账户。`revenue`/`fee` 待有复杂手续费/收入确认再进（二期）。
- **单位**：钻石 `bigint` 整数，无小数；法币金额记在订单上，不进钻石账本（避免 BigDecimal 混入账本）。
- **符号约定**：`amount` 有符号，`+` 流入账户 / `-` 流出；**一笔记账 txn 所有行 Σamount = 0**（双分录守恒），全局所有 entry Σ = 0 恒成立。
- **非负约束只施加于** `customer_available` / `customer_frozen`（守卫 UPDATE + CHECK 双保险）；平台侧清算/自有账户按净头寸可正可负。
- **不变量强制（不能只停在注释）**：`每笔 txn 明细 Σamount = 0` 与"只追加"必须落到机制，不靠自觉——① 第一期：写入统一收口到 **ledger service**，写前在**同事务内应用断言**每笔行 Σ=0，配**契约测试**；② **DB 权限**：**schema/migration owner 与 app runtime role 分离**（关键前提——`REVOKE` 只有在 app 连接用户**不是表 owner** 时才是真约束，否则 owner 绕过一切权限）；app role 只拿必要 DML，对 `ledger_txn`/`ledger_entry` **不给 UPDATE/DELETE**（append-only 由权限保证），仅 ledger 写入角色可 INSERT；③ **第一期同时做 DB 级延迟约束触发器**（`CONSTRAINT TRIGGER ... DEFERRABLE INITIALLY DEFERRED`）在提交时校验每 txn Σ=0。三层都属第一期、defense-in-depth，**非可选**。
- **双分录示例**（均 Σ=0）：充值成功 `customer_available +N / payment_clearing -N`；发起提现冻结 `customer_available -N / customer_frozen +N`；提现结算 `customer_frozen -N / payout_clearing +N`；提现失败解冻 `customer_frozen -N / customer_available +N`；发奖 `customer_available +N / platform_equity -N`。

### C2.2 核心表结构（DDL，PostgreSQL 18）

```sql
-- 通用 updated_at 触发器（PG 无 MySQL 的 ON UPDATE CURRENT_TIMESTAMP）
create or replace function set_updated_at() returns trigger as $$
begin new.updated_at = now(); return new; end $$ language plpgsql;

-- 账户：余额投影列 + 乐观锁版本
create table account (
  id         bigint generated always as identity primary key,
  owner_kind smallint not null,        -- 1=customer, 0=platform
  owner_id   bigint   not null,        -- customer_id 或 0
  kind       smallint not null,        -- 1=available 2=frozen 3=payment_clearing 4=payout_clearing 5=platform_equity
  balance    bigint   not null default 0,
  version    bigint   not null default 0,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (owner_kind, owner_id, kind),
  check (kind not in (1,2) or balance >= 0)   -- 仅用户账户强制非负
);
create trigger trg_account_updated before update on account
  for each row execute function set_updated_at();

-- 记账事务（日记账头）：★ 幂等键 (biz_type, biz_no)
create table ledger_txn (
  id         bigint generated always as identity primary key,
  biz_type   smallint not null,
  biz_no     text     not null,
  memo       text,
  created_at timestamptz not null default now(),
  unique (biz_type, biz_no)            -- 重复业务只记一次
);

-- 记账明细（日记账行）：只追加，一笔 txn ≥2 行，Σamount=0
create table ledger_entry (
  id         bigint generated always as identity primary key,
  txn_id     bigint not null references ledger_txn(id),
  account_id bigint not null references account(id),
  amount     bigint not null,          -- 有符号：+入 / -出
  created_at timestamptz not null default now()
);
create index on ledger_entry (account_id, id);   -- 账户维度 keyset + 对账
-- 每 txn 借贷必须平（Σamount=0）：延迟到提交时校验（Flyway V1 必须包含此触发器）
create or replace function assert_txn_balanced() returns trigger as $$
declare v_sum bigint; v_cnt int;
begin
  select coalesce(sum(amount), 0), count(*) into v_sum, v_cnt
    from ledger_entry where txn_id = new.txn_id;
  if v_cnt < 2 or v_sum <> 0 then
    raise exception 'ledger txn % invalid: rows=%, sum=%', new.txn_id, v_cnt, v_sum;
  end if;
  return null;
end $$ language plpgsql;
create constraint trigger trg_ledger_balanced
  after insert on ledger_entry
  deferrable initially deferred
  for each row execute function assert_txn_balanced();
-- 注：无任何明细的空 txn（0 行）不会触发 after-insert 触发器，由 ledger service + 契约测试保证不产生
-- append-only：app role 对 ledger_txn/ledger_entry 无 UPDATE/DELETE（见 C2.1 ②，前提 owner≠app role）

-- 入站回调去重
create table inbox (
  id          bigint generated always as identity primary key,
  source      smallint not null,       -- 渠道
  external_id text     not null,       -- 渠道回调唯一 ID
  received_at timestamptz not null default now(),
  unique (source, external_id)
);

-- 出站事务性 outbox（与业务同库同事务写入）
create table outbox (
  id           bigint generated always as identity primary key,
  aggregate    text     not null,
  event_type   smallint not null,
  payload      jsonb    not null,
  status       smallint not null default 0,   -- 0=ready 1=in_flight 2=done 3=dead
  attempts     int      not null default 0,
  max_attempts int      not null default 8,
  next_run_at  timestamptz not null default now(),
  lease_until  timestamptz,
  created_at   timestamptz not null default now(),
  updated_at   timestamptz not null default now()
);
create index on outbox (status, next_run_at, id);
create trigger trg_outbox_updated before update on outbox
  for each row execute function set_updated_at();
```

### C2.3 关键 jOOQ + Kotlin 写法

```kotlin
// 1) 幂等充值入账：插 txn 头冲突即短路 → 双分录 → 余额投影，全在一个事务
@Transactional
fun creditRecharge(customerId: Long, diamonds: Long, orderNo: String) {
    val txnId = dsl.insertInto(LEDGER_TXN)
        .set(LEDGER_TXN.BIZ_TYPE, BizType.RECHARGE.code)
        .set(LEDGER_TXN.BIZ_NO, orderNo)
        .onConflictDoNothing()                       // ★ 幂等：重复回调冲突
        .returningResult(LEDGER_TXN.ID)
        .fetchOne(LEDGER_TXN.ID)
        ?: return                                    // 已处理过，短路

    val avail = accountId(customerId, AccountKind.AVAILABLE)
    val clr = platformAccountId(AccountKind.PAYMENT_CLEARING)
    dsl.insertInto(LEDGER_ENTRY, LEDGER_ENTRY.TXN_ID, LEDGER_ENTRY.ACCOUNT_ID, LEDGER_ENTRY.AMOUNT)
        .values(txnId, avail, diamonds)
        .values(txnId, clr, -diamonds)
        .execute()                                   // Σ=0
    bump(avail, diamonds)                            // 入账无需守卫
    bump(clr, -diamonds)
}

// 2) 守卫扣减：可用不足则 0 行 → 抛（根治旧版"冻结未校验"双花）
val moved = dsl.update(ACCOUNT)
    .set(ACCOUNT.BALANCE, ACCOUNT.BALANCE.minus(amount))
    .set(ACCOUNT.VERSION, ACCOUNT.VERSION.plus(1))
    .where(ACCOUNT.ID.eq(availId).and(ACCOUNT.BALANCE.ge(amount)))   // ★ 原子守卫
    .execute()
if (moved == 0) throw BizException(INSUFFICIENT_BALANCE)

// 3) 状态机 CAS：只允许从前态迁移，0 行即并发/重复
val ok = dsl.update(WITHDRAW)
    .set(WITHDRAW.STATUS, WithdrawStatus.APPROVED.code)
    .where(WITHDRAW.ID.eq(id).and(WITHDRAW.STATUS.eq(WithdrawStatus.PENDING.code)))
    .execute()
if (ok == 0) throw BizException(CASH_ORDER_STATUS_ERROR)

// 4) outbox 领取：一条 UPDATE 原子领取并置租约，避免被照抄成"只 select"的重复派发；含过期租约重捡
val jobs = dsl.update(OUTBOX)
    .set(OUTBOX.STATUS, IN_FLIGHT)
    .set(OUTBOX.LEASE_UNTIL, now.plusMinutes(5))
    .set(OUTBOX.ATTEMPTS, OUTBOX.ATTEMPTS.plus(1))
    .where(OUTBOX.ID.in(
        dsl.select(OUTBOX.ID).from(OUTBOX)
           .where(OUTBOX.NEXT_RUN_AT.le(now).and(
                OUTBOX.STATUS.eq(READY)
                .or(OUTBOX.STATUS.eq(IN_FLIGHT).and(OUTBOX.LEASE_UNTIL.lt(now)))))  // ready 或过期租约
           .orderBy(OUTBOX.NEXT_RUN_AT).limit(batch)
           .forUpdate().skipLocked()))
    .returningResult(OUTBOX.ID, OUTBOX.PAYLOAD)
    .fetch()
// 成功 → status=DONE；失败 → status=READY 且 next_run_at=退避；attempts>max → status=DEAD + 告警

// 5) inbox 去重回调
if (dsl.insertInto(INBOX).set(INBOX.SOURCE, provider).set(INBOX.EXTERNAL_ID, cbId)
       .onConflictDoNothing().execute() == 0) return    // 重复回调短路

// 6) keyset 翻页：账本流水按账户维度，走 (account_id, id) 索引（与 DDL 索引一致）
dsl.selectFrom(LEDGER_ENTRY)
    .where(LEDGER_ENTRY.ACCOUNT_ID.eq(accountId).and(LEDGER_ENTRY.ID.lt(lastId)))
    .orderBy(LEDGER_ENTRY.ID.desc())
    .limit(pageSize)
    .fetch()                    // feed 的 keyset 用各自表的 (排序键, id) 索引，见 C4

// 7) 对账：投影余额 vs 明细求和，不等即差错
dsl.select(ACCOUNT.ID, ACCOUNT.BALANCE, coalesce(sum(LEDGER_ENTRY.AMOUNT), 0L).as("calc"))
    .from(ACCOUNT).leftJoin(LEDGER_ENTRY).on(LEDGER_ENTRY.ACCOUNT_ID.eq(ACCOUNT.ID))
    .groupBy(ACCOUNT.ID, ACCOUNT.BALANCE)
    .having(ACCOUNT.BALANCE.ne(coalesce(sum(LEDGER_ENTRY.AMOUNT), 0L)))
    .fetch()
```

### C2.4 状态机（全部 CAS 迁移）

- **充值**：`CREATED → PROCESSING → SUCCESS / FAILED / CANCELLED`
- **提现**：`PENDING → APPROVED → PROCESSING → SUCCESS / FAILED`；`PENDING → REJECTED`
- 所有迁移用 `update...where status=前态`，0 行即冲突（并发/重复），不做读改写。

### C2.5 幂等键与 inbox/outbox

- **幂等键 = `(biz_type, biz_no)` 唯一**：充值=充值订单号，提现冻结/结算=提现流水号，发奖=`TASK/SIGNIN/CLAIM-{id}`。重复业务插 `ledger_txn` 冲突即短路。
- **inbox**：回调先按 `(source, external_id)` 去重再处理，堵死重复回调双重入账。
- **outbox**：出款/通知与业务**同库同事务**写入（B5 硬约束，绝不挪库）；poller 用 **`UPDATE ... RETURNING` 原子领取 + 租约**（`lease_until`，过期自动重捡，见 C2.3-4），**指数退避重试**，超 `max_attempts` 转 `dead` 并告警 + 人工重放入口。

### C2.6 对账、修账、审计

- **对账**：每日（1）全局 `Σ ledger_entry.amount == 0`；（2）每账户 `balance == Σ 明细`（C2.3-7）；（3）与各渠道账单核对，差异进对账差错表并告警。
- **人工修账**：只能通过**记一笔冲正/调整 txn**（同样双分录 + 幂等键），**禁止裸 SQL 改 `balance`**；走双人复核 + 审计留痕。
- **不可篡改审计**：`ledger_txn`/`ledger_entry` 只追加不改不删；关键操作审计日志独立、只追加存储（见 C8）。

## C3. 异步 / 队列实现规范（PG `SKIP LOCKED`）

队列表最小字段与边界：

- 字段：`id, type, payload, status, attempts, max_attempts, lease_until, next_run_at, created_at, updated_at`。
- 拉取（含**过期租约重捡**）：`... WHERE (status='ready' AND next_run_at<=now()) OR (status='in_flight' AND lease_until<now()) FOR UPDATE SKIP LOCKED LIMIT :batch`，取到即置 `status='in_flight'`、`lease_until = now()+租期`、`attempts+1`。**必须含 `in_flight AND lease_until<now()` 分支**——否则 worker 领取后崩溃，任务永久卡在 `in_flight`（spike `OutboxRepo.lease` 已按此实现并测试）。
- **worker 幂等**：处理逻辑对同一任务重复执行安全（转码写确定性 S3 key、状态 CAS）。
- **重试退避 + DLQ**：`attempts` 超 `max_attempts` 转死信；退避按 `next_run_at` 指数递增。
- 索引：`(status, next_run_at)`；**分区/归档**已完成任务，防表膨胀。
- **隔离（务必区分 outbox 与 worker 队列）**：
  - **outbox 必须与业务事务同库、同一事务写入**——这是事务性 outbox 的前提，**绝不能为隔离挪到独立队列库**，否则丢掉原子性（等于退回分布式事务）。
  - **只有非关键的重任务队列**（转码/审核）才做隔离：**第一期同一 PG 实例、独立 schema/表 + 严格索引 + 定期归档**即可；只有出现 vacuum/吞吐/锁等待问题，再拆**独立 PG 实例或自建 NATS**（二期触发项）。先不增加部署/备份复杂度。

## C4. feed / 内容主路径设计（重心第一）

> 对齐 B1 声明的重心，把 feed 从"一笔带过"提升为一等设计。

- **feed 模型：推荐流走拉模式（fan-out-on-read）**，从候选池按排序取 N 条，不做写扩散收件箱。关注流（非主入口）中小规模也用拉模式 + 缓存；出现大 V 粉丝爆量再单独处理。
- **热度分**：`score = f(播放, 完播率, 点赞, 评论, 时间衰减)`，在 **Valkey ZSET** 增量维护，分钟级由计数回刷 + 衰减重算写回；ZSET 可从 PG 重建（故用 LRU 缓存策略，见 B7.4）。
- **召回融合**：热门池 + 关注 + 标签/地域兴趣，规则融合排序；中小规模不上重推荐模型。
- **分页：keyset 游标**，扔掉旧版 `ORDER BY RAND()`（翻页重复/遗漏 + 全表 filesort）。
- **已看去重**：服务端 Valkey 集合（带 TTL）记最近已看，召回时排除；替代旧版把全部已看 id 塞回前端再 `NOT IN`（会无限膨胀打爆 SQL）。
- **删除/下架传播**：视频删除/审核驳回 → 领域事件 → 从各 ZSET/缓存剔除；对外查询默认只出 APPROVED。
- **计数回刷**：真相在 PG 关系表 + 唯一约束（点赞 `user_like(user_id, video_id)`），Valkey 只做热点缓存与快速判断。
- **反作弊**：异常播放/点赞频控（见 C7）。

## C5. 自建钱库生产硬化（P0）

"省 RDS 钱"会把风险转成 on-call，必须把生产要求写硬：

- **RTO/RPO**：明确目标（如 RPO ≤ 5 分钟、RTO ≤ 30 分钟），并用演练验收。
- **备份**：EBS 快照（日/时级）+ `pg_basebackup` + **WAL 归档到 S3 做 PITR**；备份频率对齐 RPO。
- **恢复演练**：定期从备份**真实恢复**到临时实例并跑对账校验，作为验收项——没演练过的备份等于没有。
- **高可用**：至少一个**流式复制热备**（跨 AZ），主从切换预案（手动/自动）明确。
- **连接池（HikariCP，生产显式配置）**：`maximum-pool-size`（按实例规格与 DB `max_connections` 反推，**不是越大越好**——连接过多反伤 DB）、`connection-timeout`、`max-lifetime`（略短于 DB/网络空闲断连）、`keepalive-time`、`validation-timeout`；导出 Micrometer 连接池指标（活跃/空闲/等待）进 C8。DB 端 `max_connections` 与所有实例池上限之和匹配。**虚拟线程下尤其要看清：DB 并发受连接池上限约束，虚拟线程不放大它**（B5.1）。
- **容器红线**：数据落**独立 EBS**、`--shm-size` 调大、`stop_grace_period` 加长、`restart: unless-stopped`、端口不对公网。
- **监控告警**：磁盘水位（快满预案）、复制延迟、连接数、慢查询、锁等待——全部进 C8 告警。

## C6. 应用层 HA 与发布

- **多实例**：模块化单体跑 **N≥2 实例**于 Caddy 之后，消除 SPOF；JWT 无状态，无需会话粘滞。
- **零停机发布**：滚动或蓝绿；健康检查 + 优雅下线（等在途请求排空）。
- **构建系统**：目标系统统一 **Gradle Kotlin DSL**。使用 Gradle Wrapper、`libs.versions.toml`、configuration cache、build cache、parallel；CI 主入口为 `./gradlew check`。`jooqCodegen` 是**独立任务，完整声明 inputs/outputs（含 build 脚本本身）**，支持增量 **UP-TO-DATE**（改配置才重跑）；**`@CacheableTask` / 远端 build-cache 复用留待正式骨架评估**——spike 只承诺 UP-TO-DATE，不承诺跨机缓存。生成代码放 `build/generated-src/jooq`，禁止手改，普通单测不启动 Testcontainers。
- **供应链可复现（正式骨架 day-1）**：`libs.versions.toml` 集中版本 + **dependency locking**（`gradle.lockfile` 锁定传递依赖）+ **依赖校验 `verification-metadata.xml`**（校验 checksum/签名，防依赖投毒）。spike 阶段可暂缺，正式骨架第一天上并纳入 CI。
- **CI/CD**：`./gradlew check` → 镜像 → 分组滚动部署；Flyway 迁移在部署前置步执行。
- **迁移只前滚，不依赖自动回滚**：DB 迁移用 **expand/contract（并行变更）**——先加新列/表兼容双跑，切流稳定后再删旧；失败用**补偿迁移**向前修复，禁止依赖自动 rollback（会丢数据）。
- **Flyway 生产加固（显式锁定，不依赖各版本默认）**：`spring.flyway.clean-disabled=true`（禁 clean，防误操作 / 工具或命令行差异抹库）、`spring.flyway.out-of-order=false`（顺序执行）。理由是**显式锁定消除环境间漂移**，不绑定某版本默认值。versioned 迁移一旦进入下游/永久环境即**不可变**，只能新增迁移**向前修复**（Flyway 官方语义）。
- **虚拟线程运行期（Boot 4，JDK 25）**：`spring.threads.virtual.enabled=true`。纯 Web 进程有 Tomcat 非守护线程兜底；**调度 / 消息型进程**须补 `spring.main.keep-alive=true`（虚拟线程是守护线程，否则无非守护线程时进程可能早退）。pinning：**JDK 24 JEP 491 已消除 `synchronized` 绝大部分固定，JDK 25 上风险大幅下降**；仍保留观测（`-Djdk.tracePinnedThreads` / JFR），把含 IO 的 `synchronized` 换成 `ReentrantLock`。

## C7. 安全与合规细节

在 Part A 止血之上，目标态还需：

- **密钥管理（硬规则，第一期）**：统一用 **SOPS+age**。密文配置可入库；明文**只在部署时解密**并以**只读 secret 文件**注入容器（挂载 `/run/secrets/*`，应用从文件读取，不走环境变量）；**禁止明文密钥进入 Git、镜像、日志、CI 输出、应用配置文件**。OpenBao 不进第一期，仅在出现动态凭证/租约/集中审计/跨团队密钥自助发放需求时作为二期升级目标。运维约束：① age 私钥只放部署机 / CI secret store，**不进 repo**；② 解密产物不写入镜像，挂 `/run/secrets/*`；③ 轮换要有 **runbook**（改密文 → 部署 → 验证 → 撤旧）；④ 生产**禁止全量打印 env**（环境变量与 secret path 都可能泄密）。
- **后台 2FA**：admin 登录强制二次验证。

> **TOTP 实施定案(2026-07,迁移子项目 1 第三批)**:RFC 6238,JDK Mac 自实现(不自创算法);生产参数 30s 步长 / 6 位 / HMAC-SHA1 / 验证窗口 [-1,0,+1](算法函数以 digits 参数化,RFC Appendix B 向量按 8 位验算法)。secret 应用层加密:AES-256-GCM,IV 12B 随机,tag 128b,AAD 绑定 `admin_user:{id}:totp_secret:{key_version}`,KEK 注入不进 git。防重放:`totp_last_used_step` 原子 CAS 单调递增,命中 1 行才通过。鉴权错误对外统一 401 `/problems/invalid-credentials` 防枚举(锁定/禁用/TOTP 错不区分);username 以 canonical(trim+lowercase ROOT)唯一。设计全文见 `docs/superpowers/specs/2026-07-02-module-boundaries-identity-design.md`。

- **内部调用**：目标态是模块化单体，admin↔service 是**进程内模块调用，不走内部 HTTP**——从根上消除内部鉴权面。**仅迁移期**旧双应用并存时，旧 internal API 用 **HMAC 签名**（替代明文共享密钥头 + 常量时间比较）过渡；mTLS 为二期。
- **JWT 可吊销**：引入 `jti` + 黑名单存储（Valkey），修旧版"令牌永不过期 + logout 空操作"。
- **账号锁定 + 限流**：登录/发码/关键写接口限流（Caddy 层 + 应用层），失败锁定。
- **敏感字段**：手机号/银行卡等**加密存储 + 展示脱敏**；支付回调 payload 留存策略（保留期 + 脱敏）。
- **边缘防护**：Caddy 前置基础 WAF 规则 / 频控；反作弊(薅邀请奖励)规则。

## C8. 可观测性：SLO 与告警（不只是装组件）

- **SLO**：核心接口可用性/延迟目标（如 feed P99、支付回调成功率）。
- **业务指标**：充值成功率、出款成功率、账本不平计数、**队列积压深度**、DLQ 计数、转码时长。
- **资金告警**：`balance != Σ ledger`、DLQ 非空、对账差异、出款失败率突增 → 立即告警。
- **日志**：结构化 + **脱敏规则**（禁打 token/密钥/卡号）；保留期分级。
- **链路（二期）**：Tempo + OTel 采样率按流量调。**审计**：第一期 ledger 只追加 + 独立操作审计表 + S3 归档/备份即足够；**WORM / 不可变存储为合规升级触发项**，不纳入第一期必做。

## C9. 测试与验证策略

> 旧系统 admin/common **零测试**——重构必须补齐，尤其资金不变量。

- **资金契约测试**：账本双分录、幂等键去重、CAS 冲突、余额守卫 SQL 的行为断言。
- **并发测试**：并发重复回调（不双入账）、并发领奖（不双发）、并发提现（不超额）、login 与发奖竞态（不丢更新）。
- **模块边界测试**：ArchUnit 断言依赖方向与包访问（B4；Modulith 二期）。
- **协程边界测试**：ArchUnit/约定断言 `suspend fun` 不带 `@Transactional`、事务 service 不调用 `suspend`、协程不持有事务边界（B5.1）。
- **迁移对账测试**：迁移脚本跑完自动核对 `balance == Σ ledger`。
- **spike 冒烟**：JDK 25 + Kotlin 2.4 + Gradle 9.6 + Boot 4 + jOOQ 全链最小工程（C11）纳入 CI。
- **Testcontainers 测试约定**：应用级集成测试用 **`spring-boot-testcontainers` + `@ServiceConnection`** 自动注入连接信息（免手写 `@DynamicPropertySource`）；构建期 `jooqCodegen` 的临时 PG 保留自定义 task（构建期需求，不走 `@ServiceConnection`）；**CI 禁用 reusable containers**（官方明确不适合 CI，且资源清理/网络不完整）。
- **静态检查 / lint（工具强制，不靠肉眼，已在 spike 接入 `check`）**，社区轻量实践：
  - **kotlinc `allWarningsAsErrors=true`**：编译级告警 / 废弃 API 直接失败。
  - **ktlint 14.2.0**（稳定版可跑 Kotlin 2.4）：格式 / 风格。
  - **detekt 2.0.0-alpha.5**，插件 ID **`dev.detekt`**（新 ID）：代码味 / 复杂度。**注意 detekt 稳定版 1.23.x 不支持 Kotlin 2.4**（"compiled with 2.0.21"），必须用 2.0 alpha。
  - **不上 Qodana**：跑全套 IDE inspection 偏重，社区视为多数项目 overkill；编辑器级 inspection（如 `ConvertLongToDuration`）由 IDE / LSP（Zed 的 Kotlin LSP）实时提示，不进 CI 门禁。

## C10. S3 + 阿里云 CDN 源站保护（需 POC）

跨云回源鉴权坑多，**上线前必须实测**：

- **源站锁死**：S3 桶非公开，只允许阿里云 CDN 回源（回源 IP 段白名单 / 约定密钥头），防用户绕 CDN 直连源站——否则 CDN 层的 URL 鉴权形同虚设。
- **须 POC 的点**：跨云回源鉴权、Header 透传、回源 IP 段稳定性、**Range 请求**、**HLS 分片缓存**（.ts 长缓存 / m3u8 短缓存）、回源签名。
- **成本杠杆**：命中率是头号成本项（S3→阿里云回源走 AWS 公网出流量，仅 miss 产生）；热门内容预热。
- **PoP 匹配**：确认阿里云 CDN 的 PoP 覆盖匹配**海外/东南亚**受众。
- **存储分层**：冷源文件转 S3-IA；**CDN 可回源的成品绝不进 Glacier**（需 restore，回源会失败）。

## C11. 实施前必须完成的 spike / 校验

> **本机零服务依赖（硬规则）**：开发机只需 **JDK 25 toolchain（jenv / Gradle toolchain）+ Gradle Wrapper + Docker**。PostgreSQL、Valkey、S3 mock、外部服务 mock **一律用 Testcontainers 或 Compose 起**，**本机不安装** PG / Valkey / Flyway CLI / jOOQ CLI / ffmpeg / Prometheus 等；**jOOQ codegen 由 Gradle `jooqCodegen` task 启动 Testcontainers PG → 跑 Flyway → 再运行 codegen**（Testcontainers 只提供临时依赖服务，不负责 codegen 本身），**不依赖手动 `docker compose up`**。

**JDK 25 全链** spike 四项验收（整条链 `JDK 25 + Kotlin 2.4 + Gradle 9.6 + Spring Boot 4.1 + jOOQ KotlinGenerator + Flyway + Testcontainers` 全绿才动重构）：

1. **Kotlin + Spring 机制**：验 `kotlin-spring`/all-open、`kotlin-reflect`、`jackson-module-kotlin`、`@ConfigurationProperties` 构造器绑定、nullable 语义、AOP 代理（`@Transactional`/`@Component` 代理生效）。
2. **jOOQ KotlinGenerator（单一路径，必须验收）**：forced type + Converter、生成的 Record、`RETURNING`、`SKIP LOCKED`、`ON CONFLICT`、Kotlin DSL 写法约束全部通过（**Binding、POJO/DTO 映射未在 spike 覆盖**，需要时再补验）。**不通过则暂停重构、重开 ADR**——不内置 JavaGenerator 回退（避免第二条持久层代码生成路径）。
3. **Gradle 9.6 × KGP 2.4 链路 + 构建速度**：验 KGP 2.4 与 Gradle 9.6 完整链路（含 IDE import）、configuration cache、build cache、`jooqCodegen` 输入隔离——第二次 `./gradlew check` 不无谓重跑 codegen / Testcontainers。**通过即锁定 9.6.x**。
4. **并发模型探针**（B5.1）：① 一个 `suspend` feed fan-out 编排（调阻塞 read service / Valkey / 外部 mock），一个阻塞 `@Transactional` 钱核 service——证明事务路径不需要也不接受协程；② `spring.threads.virtual.enabled=true` 下验 MVC + `@Transactional` + jOOQ blocking + **HikariCP 池大小/超时**行为正常——虚拟线程解决线程成本，但 **DB 并发仍受连接池上限约束**，这条边界要看清。

附带版本校验：PG18 `uuidv7()` × JDBC 驱动；Valkey 9.1 × Lettuce 冒烟；S3 + 阿里云 CDN 源站保护 POC（C10）；各组件最终小版本以实施当时官方文档为准（核心版本核实于 2026-07）。

### C11 spike 实测结论（2026-07，`gabon-spike/` 已跑绿，9 测试全过）

整链 `JDK 25 + Kotlin 2.4.0 + Gradle 9.6.1 + Spring Boot 4.1.0 + jOOQ 3.21.5 KotlinGenerator + Flyway + Testcontainers` 跑通。四项验收**逐项**状态：

- **① Kotlin + Spring 机制** ✅：`@Transactional` AOP 代理、`@ConfigurationProperties` 构造器绑定、jackson-kotlin（Spring 测试全通过）。
- **② jOOQ KotlinGenerator** ✅：含**真实 forced type + Converter**（`account.kind`→`AccountKind`，经 insert/select 往返）、**`RETURNING`**（outbox 单条 `UPDATE ... WHERE id IN (SELECT ... FOR UPDATE SKIP LOCKED) RETURNING`）、`ON CONFLICT`、`SKIP LOCKED`。codegen 为自定义 task（非官方插件）。
- **③ Gradle 9.6.1 × KGP 2.4 + 构建速度** ✅：配置缓存 stored/reused，二次 `check` 全 UP-TO-DATE。
- **④ 并发边界** ✅：`suspend` fan-out 调阻塞钱核 + 虚拟线程；ArchUnit 断言**无 `@Transactional suspend`** + feed 层无 `@Transactional` + 禁 MyBatis/JPA/分散 JdbcClient。

**"整链较新"实测咬出的坑（均已解决，构成落地必知）**：

| 组件 | 现象 | 落地结论 |
|---|---|---|
| **Testcontainers** | Docker 29 最低 API 升到 1.44，TC ≤1.x 报 "client version 1.32 too old"；TC 2.0 模块坐标改为 `org.testcontainers:testcontainers-postgresql`、类迁到 `org.testcontainers.postgresql.*` | 用 **TC 2.0.5**，强制覆盖 Spring Boot BOM 的旧版 |
| **Flyway** | Boot BOM 的 Flyway 版本不识别 PG18 | 强制 **11.3.0**（与 codegen 一致） |
| **ArchUnit** | 1.3.0 的 ASM 读不了 JDK 25（class v69）字节码 → 静默导入 0 类 | 升 **1.4.2** |
| **OrbStack** | Testcontainers 默认探测不到 Docker socket | 需 `DOCKER_HOST` + `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE`（或 `~/.testcontainers.properties`） |

**Boot 4 运行期 Flyway 自动迁移（已解决并验证）**：Boot 4 autoconfig 模块化后，`flyway-core` 单独在场**不再**触发自动迁移；必须用 **`org.springframework.boot:spring-boot-starter-flyway`**（含 `FlywayAutoConfiguration`），并**显式**加 `org.flywaydb:flyway-database-postgresql`（不随 starter 传递）。spike 换成 starter + `spring.flyway.enabled=true` + **删除手动 migrate** 后，表由 Boot 启动时自动建、9 测试仍全绿。

**Kotlin/Spring 空安全 flag（实测：旧指南两条都不必要）**：① `kotlin-reflect` 仍须**显式声明**（Spring 要求 classpath 有，勿赖传递引入；spike 已显式加、`check` 仍绿）；② 旧指南常配的 **`-Xjsr305=strict` 在本栈已过时**——Boot 4 / Framework 7 改用 **JSpecify**，Kotlin 2 自动翻译成 Kotlin 空安全（默认即严格，无需该 flag）；③ **`-Xannotation-default-target=param-property` 是 Kotlin 2.4 语言默认**，再显式加会因 `-Werror` 报"冗余"直接编译失败（spike 实测撞到，正好印证 `allWarningsAsErrors` lint 有效）。→ **两个 flag 都不加，只留 `kotlin-reflect`**。教训：旧博客/指南的编译器 flag 要对当前 Kotlin/Spring 版本重新核实，别照抄。

---

## 附 A. 前后架构总览对比

| 维度 | 旧 | 新 |
|---|---|---|
| 架构风格 | 双应用共享库 | 模块化单体 + 边界强制（ArchUnit；Modulith 二期）+ 领域事件 |
| 一致性 | 混同，读改写整实体 | 强一致（钱）/ 最终一致（内容）分区 |
| 资金安全 | 无幂等 / 无 CAS / 事务内 HTTP | 双分录账本 + 幂等键 + CAS + inbox/outbox + 对账 |
| 语言/构建/框架 | Java 17 / Maven / Spring Boot 3.2.4 | JDK 25 LTS / Kotlin 2.4 / Gradle KTS 9.6 / Spring Boot 4.1（spike 验收） |
| 并发模型 | thread-per-request | 阻塞钱核 + 虚拟线程；协程仅编排层（禁跨事务） |
| 持久层 | MyBatis-Plus 全实体回写 | jOOQ（唯一持久层，编译期类型安全） |
| 连接池 | Druid | HikariCP |
| Schema 迁移 | 裸 SQL | Flyway |
| 主库 | MySQL 8 共享库 | PostgreSQL 18.x 独立库（Docker，热备+PITR） |
| ID | 硬编码雪花（会撞） | 内部 bigint + 对外业务号 UUIDv7 |
| 缓存/计数 | Redisson+Jedis，Redis TTL 当真相 | Valkey 9.1，真相在 PG，缓存 LRU + 回刷 |
| 异步 | @Scheduled 轮询 | PG 队列（隔离）+ outbox/DLQ + 事件 |
| 鉴权 | opt-in fail-open | 默认拒绝 + 2FA + jti 吊销（内部调用进程内，无内部 HTTP 鉴权面） |
| 密钥 | 明文入库 | SOPS+age 外置（OpenBao 为升级项） |
| 转码 | MediaConvert 轮询 | 自建 ffmpeg 7.x / Spot 事件驱动 |
| 审核 | 弱 / 越权泄露 | 自建流水线 + 默认只出 APPROVED |
| feed | ORDER BY RAND 分页 | 拉模式 + ZSET 热度 + keyset + 去重 |
| 反向代理 | 无统一 | Caddy 2.11.x |
| 观测 | System.out / 吞异常 | 结构化日志 + Prometheus/Grafana + 告警（Loki/Tempo 二期） |
| 测试 | admin/common 零测试 | 资金契约 + 并发 + 边界 + 迁移对账测试 |
| 基础设施 | 托管零散 | AWS EC2 全自建 Docker（除 S3 + 阿里云 CDN） |

## 附 B. 关键取舍与风险

- **自建 = 用钱成本换运维成本**：钱库的 HA/备份/PITR/演练是硬要求（C5），做不到就是拿资金数据赌运气。
- **JDK25 / Kotlin / Gradle / Boot / jOOQ 全链较新**：整条依赖链兼容靠 C11 全链 spike 兜底；这是"不追新"与"用支持期内版本"之间的折中。
- **迁移风险**：MySQL→PG 方言 + 余额对账是重活（C1），绞杀者渐进 + 对账不平不切流。
- **跨云回源**：S3+阿里云 CDN 源站保护必须 POC（C10）。
- **单区域**：中小规模够用；多区域是后话。
- **开源审核召回弱于商用**：一期靠人审队列补；误杀/漏审率超阈值再作二期 ADR 引入 AWS Rekognition。

## 附 C. 落地顺序

1. **Part A 止血**（旧系统，最高优先）：密钥轮换/外置、鉴权 fail-closed、默认只出 APPROVED、登录限流、关泄密日志。
2. **spike 验收**（C11）：JDK 25 + Kotlin + Gradle 9.6 + Boot 4 + jOOQ 全链、S3+CDN 源站 POC——**先证明选型可行再动重构**。
3. **新库 + 迁移框架**：建 PostgreSQL + Flyway 治理骨架（C1/C6）——账本实现的前置，先有 schema/迁移框架才能落账本。
4. **账本核心**：账户体系 + 双分录 schema + 幂等 + CAS + inbox/outbox（C2）。
5. **迁移 + 对账**：绞杀者渐进把旧余额/流水迁入新账本，`balance == Σ ledger` 不平不切流（C1）。
6. **内容主战场**：feed（C4）+ 媒体转码 + 计数回刷。
7. **硬化（贯穿全程）**：钱库生产化（C5）、应用 HA/发布（C6）、安全（C7）、可观测 SLO（C8）、测试（C9）。
