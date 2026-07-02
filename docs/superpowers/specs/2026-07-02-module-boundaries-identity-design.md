# 设计:模块边界 + 鉴权骨架(迁移子项目 1)

> 日期:2026-07-02 ｜ 状态:已批准 ｜ 上游:`docs/architecture-redesign.md`(B4/B5.1/B7.5/C7/C9)
> 本 spec 经 brainstorming 流程逐节确认,判断点均有用户定案。

## 1. 背景与范围

整体目标是把旧系统(`../gabon`,Java 17 / Boot 3 / MySQL / MyBatis-Plus,双应用共享库)迁移到本仓。迁移分解为 7 个子项目,各自走独立 spec → plan → 实施循环:

| # | 子项目 | 依赖 |
|---|---|---|
| **1** | **模块边界 + 鉴权骨架(本 spec)** | 无 |
| 2 | 钱核完全体(提现双分录/充值 inbox/状态机/outbox worker) | 1 |
| 3 | 奖励域(任务/签到/VIP/活动) | 2 |
| 4 | 内容域 + feed | 1 |
| 5 | 媒体流水线 | 1 |
| 6 | 审核 + admin/报表 | 1,4 |
| 7 | 数据迁移 + 对账 + 切流 | 2-6 渐进 |

本 spec 范围:九个限界上下文的包格子与 ArchUnit 边界强制、完整身份域(登录/登出/刷新/jti 吊销/登录保护/admin TOTP 2FA)、`/v1` API 基座(默认拒绝 + problem+json)。

对外 API 契约:**全新 `/v1`**(B7.5),不背旧路径与 `JsonData` 包裹;旧客户端切流依赖端适配层,归子项目 7 讨论。

## 2. 架构决策记录

> 采用方案 A:单 Gradle 模块 + `com.gabon.<context>.{api,internal}` 包边界 + ArchUnit 强制。
> B(多 Gradle 子项目物理隔离)/ C(Spring Modulith 提前上)都不是当前实现分支,而是架构改案:B 会重做已验证的 jOOQ/codegen/Gradle 构建链;C 违反 Modulith 二期增强的分期。若要改 B/C,必须新 ADR + spike,不在当前开发路径里讨论。

依据:`docs/architecture-redesign.md` B4(模块化单体 + 一期 ArchUnit)、B6(Modulith 二期)、CLAUDE.md(spike pattern 不推翻)。

## 3. 包结构与模块格子

```
com.gabon
├── platform/                    ← 共享基建(非限界上下文,豁免依赖方向规则)
│   ├── security/                   默认拒绝链、JWT 过滤器、principal 模型、PublicRoutesContributor
│   ├── outbox/                     现 com.gabon.outbox 迁入
│   └── web/                        problem+json 错误模型、公共 web 配置
├── identity/    {api, internal}    C端账号+凭证、admin 账号、token/jti、2FA、限流锁定
├── wallet/      {api, internal}    钱包与账本;现 com.gabon.ledger → wallet/internal/ledger/
├── recharge/    {api, internal}    充值(空格子)
├── withdraw/    {api, internal}    提现出款(空格子)
├── reward/      {api, internal}    任务/签到/VIP/活动(空格子)
├── content/     {api, internal}    视频/评论/点赞/关注/feed;现 com.gabon.feed → content/internal/feed/
├── media/       {api, internal}    上传/转码流水线(空格子)
├── moderation/  {api, internal}    内容审核(空格子)
└── reporting/   {api, internal}    报表后台(空格子)
```

- **空格子用真实 marker 类**(ArchUnit 扫 classpath 必须看得见,`package-info.kt` 不保证产出 class):
  - `api/<Context>Api.kt`:`interface <Context>ApiMarker`
  - `internal/<Context>InternalMarker.kt`:`internal object <Context>InternalMarker`
- **现有代码迁移**:`ledger` → `wallet.internal.ledger`;`outbox` → `platform.outbox`;`feed` → `content.internal.feed`。连带:`build.gradle.kts` forced type FQCN 更新;既有 ArchUnit 钱核禁协程规则的包集合改为 `..wallet..`/`..recharge..`/`..withdraw..`(覆盖整上下文,严格强于原 `..ledger..`/`..payment..`/`..withdraw..`)。
- **`AccountKind` 定性**:forced type 使 `com.gabon.jooq` 生成代码引用 `wallet.internal.ledger.AccountKind`,可接受(jooq 是豁免区);但该枚举**只限钱核内部/持久层使用**,其他上下文不得将其当 API 类型;wallet 对外暴露账户类型时另设 `wallet.api` 类型。
- **广告域边界条件**:旧系统 `Ad/Advertisement` 暂并入 `content`;只要广告还是内容展示/配置,不涉及投放预算、计费、结算、商户账户,就留在 content;一旦出现商业闭环再拆独立上下文(届时新增白名单项)。
- **`com.gabon.jooq`** 生成代码维持现状,是 schema 的类型化投影,不是模块;但跨上下文表访问受 §4 规则 6(表所有权)约束。

## 4. ArchUnit 边界规则集

新增 `ModuleBoundaryTest`(与既有 `ArchitectureTest` 并列,同进 `./gradlew check`):

| # | 规则 | 实现要点 |
|---|---|---|
| 1 | 禁止跨上下文访问 `..internal..` | 九上下文两两生成 `noClasses()...dependOnClassesThat()` 断言 |
| 2 | 跨上下文只许依赖对方 `..api..` | 上下文对外依赖 ∈ {对方 api, platform, jooq, 三方库} |
| 3 | 依赖方向白名单(显式常量表) | `recharge→wallet`、`withdraw→wallet`、`reward→wallet`、`moderation→content`、`media→content`、`reporting→各上下文 api`;白名单外的上下文间依赖即失败 |
| 4 | 豁免清单(集中一个常量,可 review) | `com.gabon.platform..`(人人可依)、`com.gabon.jooq..`(生成代码)、`GabonApplication`(启动装配) |
| 5 | CI 门禁 | 规则进 `./gradlew check`,不允许跳过测试合并;不新增 CI 步骤 |
| 6 | **表所有权** | 业务代码只能在所属上下文的 internal 仓储/服务中访问该上下文拥有的 jOOQ 表;跨上下文数据访问必须走对方 api。显式 table→owner 白名单(常量);**白名单里无主的表直接失败**(新迁移必须登记归属) |

- **reporting 单向**:reporting 只允许依赖各上下文 api,**任何上下文不得依赖 reporting**(防后台查询反向污染业务域)。
- **jooq 豁免的边界**:`com.gabon.jooq..` 豁免仅表示"import 生成代码不构成跨上下文包依赖",**不绕过表所有权**——任意上下文直接读写他人表(如 content 引用 `ACCOUNT`)会被规则 6 拦下,B4"跨模块只经 api/领域事件"由此闭环。初始白名单:`account`/`ledger_txn`/`ledger_entry` → wallet;`outbox`/`inbox` → platform;`customer`/`admin_user`/`refresh_token` → identity。
- 既有 4 条断言(jOOQ-only、钱核禁协程、feed 无事务、禁 `@Transactional suspend`)保留,仅更新包匹配;钱核禁协程覆盖 `..wallet..`/`..recharge..`/`..withdraw..` 整上下文。

## 5. 身份域(identity)

### 5.1 数据模型(`V2__identity_core.sql`)

| 表 | 关键列 | 说明 |
|---|---|---|
| `customer` | `id` bigint identity PK、`username`(展示用原始输入)、`username_canonical` **unique**、`password_hash`、`invite_code` unique、`invited_by` nullable、`status`(active/disabled)、`last_login_at`、`created_at/updated_at` | 只收鉴权必需字段:旧表余额列不进(wallet 域投影)、VIP 不进(reward 域)、头像/签名等资料字段归属后续子项目定。邀请码只记录关系,发奖归 reward 域 |
| `admin_user` | `id`、`username`、`username_canonical` unique、`password_hash`、`totp_secret_enc` bytea nullable、`totp_key_version` smallint、`totp_last_used_step` bigint、`totp_enabled` bool、`status`、`created_at/updated_at`、`last_login_at` | TOTP 材料加密存储,见 5.4 |
| `refresh_token` | `id`、`family_id` uuid、`principal_type` smallint + `principal_id` bigint、`token_hash` unique(SHA-256)、`expires_at`、`rotated_at` nullable、`revoked_at` nullable、`last_used_at`、`created_ip`、`created_user_agent`、`created_at` | 明文不落库;索引:`token_hash` unique、`(principal_type, principal_id)`、`family_id`、`expires_at`(批量吊销与清理) |

**username 规范化(DDL + 代码双契约)**:注册与登录统一 canonicalize(`trim` + `lowercase(Locale.ROOT)`,规则集中一处),唯一约束落在 `username_canonical`;展示名以后另设字段。不用 citext(不引扩展,规范化显式发生在系统边界)。

### 5.2 Token 模型

- **Access JWT**(jjwt 0.12,HS256):claims = `sub` / `typ`(customer|admin)/ `roles` / `jti`(uuidv7)/ **`sid`(= refresh family_id,session 标识)** / `iat` / `exp`(**15 分钟**,与 refresh TTL 均为配置参数,此为默认值)。签名密钥从文件注入(`/run/secrets/*`,SOPS 流程),不进 git;测试用 test 固定键;prod 缺失即启动失败(fail fast)。
- **Refresh token**:256-bit 随机不透明串,**旋转式**——每次 refresh 换新、旧 token 标记 `rotated_at`;**重用检测**:已 rotated/revoked 的 token 再次出现 → 吊销整个 family(防重放)。TTL 30 天。
- **旋转并发语义(实现约束,防并发双旋转)**:旋转必须原子抢占——`UPDATE refresh_token SET rotated_at = now() WHERE token_hash = ? AND rotated_at IS NULL AND revoked_at IS NULL AND expires_at > now()`,命中 1 行才签发新 token;0 行且该 token 存在(已 rotated/revoked)即判定重放 → 吊销整个 family。与 C2.4 状态机 CAS 同款模式,禁止读-改-写。
- **登出**:凭 access JWT 的 `sid` 定位并吊销对应 refresh family + 当前 `jti` 进 Valkey 黑名单(TTL = 剩余有效期);logout 请求无需提交 refresh token。
- **改密**:吊销该主体全部 family + 黑名单当前 jti。
- **jti 黑名单 fail-closed(定案)**:JWT 过滤器查黑名单时 Valkey 不可用 → 拒绝请求返回 503;Valkey 纳入 readiness 探针。安全优先,access 仅 15 分钟,爆炸半径可控。

### 5.3 登录保护(C7)

- **账号维度**:连续 5 次失败 → 锁 15 分钟(Valkey 计数器 + TTL 自动解锁);人工封禁走 PG `status`。**失败计数覆盖 TOTP 错误**(防密码正确后无限猜 6 位码)。
- **IP 维度**:滑动窗口限流(30 次/10 分钟)→ 429。
- 阈值均为配置参数,上述为默认值。

### 5.4 Admin 2FA(TOTP)

- **算法定案**:RFC 6238,JDK `Mac` 实现,**不自创算法**;生产参数写死:**30s 步长 / 6 位 / HMAC-SHA1 / 验证窗口最多 [-1, 0, +1]**。算法函数以 `digits` 为参数:RFC 6238 Appendix B 官方向量是 **8 位** TOTP 值,用 8 位验证算法本身正确;生产 verifier 固定 6 位(硬编码 6 位会导致官方向量测不过)。
- **secret 保护**:`totp_secret_enc` = AES-256-GCM 应用层加密,KEK 从 `/run/secrets/*` 注入;**AAD 绑定 `admin_user:{id}:totp_secret:{key_version}`**(防密文跨用户搬运);IV 12 bytes 随机,tag 128 bit(默认);`key_version` 支持轮换(轮换=重加密)。
- **防重放(含并发语义,实现约束)**:接受 TOTP 必须原子 CAS——`UPDATE admin_user SET totp_last_used_step = :step WHERE id = :id AND (totp_last_used_step IS NULL OR totp_last_used_step < :step)`,**命中 1 行才算验证成功**;0 行(并发双请求同一 code,或重放旧 step)按统一 401 处理。禁止读-校验-写(RFC 6238 one-time validation;step 单调递增同时挡住窗口内回退的旧 code)。
- **流程**:enroll → 返回 `otpauth://` URI(前端渲二维码)→ confirm 校验通过后置 `totp_enabled`。登录时密码正确且已启用 2FA 而 `totpCode` 缺失/错误 → 对外仍是统一 401(见 §6)。

### 5.5 端点(全部 `/v1`)

```
POST /v1/auth/register        用户名+密码+邀请码(可选);成功即返回 token 对
POST /v1/auth/login           {username, password} → {accessToken, refreshToken, expiresIn}
POST /v1/auth/refresh         旋转换新
POST /v1/auth/logout          吊销 family + jti 黑名单
GET  /v1/auth/me              当前主体信息
POST /v1/auth/password        改密 → 吊销全部 session
POST /v1/admin/auth/login     {username, password, totpCode?}
POST /v1/admin/auth/logout
POST /v1/admin/auth/totp/enroll | confirm
```

### 5.6 默认拒绝机制

Spring Security 单过滤链:`anyRequest().authenticated()` 兜底,admin 路由要求 admin 角色。**公开路由由各上下文声明**:`platform.security` 定义 `PublicRoutesContributor` bean 契约,identity 贡献 login/register/refresh;白名单集中汇总,可 review,新增公开端点必须显式声明。

## 6. 错误处理(platform.web)

- **RFC 9457 problem+json**:响应只出 `type`(**稳定 URI reference**,如 `/problems/invalid-credentials`)、`title`、`status`、`detail`。problem 类型集中注册(enum **持有** URI 字符串;enum 名不是对外契约)。
- **鉴权错误统一防枚举**(OWASP):用户不存在/密码错/disabled/locked/TOTP 错,对外一律 **401 `/problems/invalid-credentials`**;内部细分原因只进结构化日志(供告警排查)。**不用 423**(会泄露用户名存在)。`429` 仅用于 IP/全局限流。
- **内部异常 fail fast**:对外 500 通用 problem,不泄内部细节;请求 DTO 校验(jakarta validation)只发生在系统边界。

## 7. 测试策略

| 类别 | 覆盖 |
|---|---|
| ArchUnit(`ModuleBoundaryTest`) | §4 全部规则;既有 4 断言更新包匹配后全绿 |
| TOTP 单测 | RFC 6238 Appendix B 向量(**8 位**,验算法函数);窗口边界;同 step 重放拒绝 + **并发双请求同一 code 仅一方成功**(CAS 0 行路径);加解密往返 + AAD 篡改拒绝(注入 `java.time.Clock` 保证确定性) |
| 身份域集成测试(PG+Valkey) | register→login→me 闭环;5 次失败→锁定(锁定期内正确密码也 401);refresh 旋转 + 重用检测吊销全 family;**并发双 refresh 同一旧 token 仅一方成功**;logout→jti 黑名单生效**且该 family 的 refresh token 不可再用**;改密→全 session 吊销;admin TOTP enroll→confirm→登录;Valkey 不可用→503 fail-closed |
| 回归 | 既有 9 测试随包迁移后全绿 |

测试基建:`AbstractPgTest` → **`AbstractIntegrationTest`**,单例 PG + 单例 Valkey(Testcontainers `valkey/valkey:9.1-alpine`),CLAUDE.md 测试约定同步更新。

## 8. 实施分批(落地顺序,>3 文件拆批定案)

1. **第一批**:包迁移 + marker + ArchUnit 边界测试 + 文档 B4/CLAUDE.md,同步跑现有回归。
2. **第二批**:identity DDL(`V2__identity_core.sql`)+ PG/Valkey 测试基类。
3. **第三批**:platform.security / platform.web / problem / token / TOTP / 端点与集成测试。
4. **springdoc 单独 spike**:查官方文档确认 Boot 4.1 兼容性;不兼容就先不进主线,结论回填架构文档 B6。

依赖新增(主线):spring-boot-starter-security、jjwt 0.12.x、spring-boot-starter-data-redis(Lettuce)。实现时具体 API 一律查最新文档,不赖预训练数据。

## 9. 文档增补(随实施批次落盘)

- `docs/architecture-redesign.md` **B4 决策注记**:§2 的方案 A 措辞。
- `docs/architecture-redesign.md` **C7 增补 TOTP 定案**:参数(30s/6位/HMAC-SHA1/窗口±1)、secret 应用层加密(AES-256-GCM + AAD + KEK 注入)、同 step 防重放、统一 401 防枚举语义、username canonical 规范化。
- `CLAUDE.md`:目录结构(九格子 + platform)、测试约定(`AbstractIntegrationTest` + Valkey)、硬规则包名(`..payment..` → `..recharge..`)。

## 10. 判断点定案汇总

| 判断点 | 定案 |
|---|---|
| Valkey 黑名单不可用 | fail-closed 503,Valkey 进 readiness |
| 密码哈希 | Spring Security `DelegatingPasswordEncoder`(bcrypt 默认);旧库凭证迁移归子项目 7 |
| TOTP 实现 | JDK Mac 自实现 RFC 6238,受 §5.4 全部约束 |
| 测试基类 | 改名 `AbstractIntegrationTest`,PG+Valkey 双单例 |
| username 规范化 | lowercase canonical 字段 + unique index,不用 citext |
| 锁定/限流响应 | 对外统一 401;429 仅 IP/全局限流 |
| 2FA 范围 | 仅 admin;C 端 2FA 不在本期 |
| logout 定位 family | access JWT 增加 `sid` claim(= family_id),logout 无需提交 refresh token |
| refresh 旋转并发 | 原子 UPDATE 抢占(CAS 模式),0 行判重放吊销 family |
| TOTP 接受并发 | `totp_last_used_step` 原子 CAS 递增,命中 1 行才通过,0 行统一 401 |
| 表所有权 | table→owner 白名单进 ArchUnit(§4 规则 6),jooq 豁免不绕过所有权 |

## 11. 明确不做(YAGNI 边界)

- C 端 2FA、OAuth/社交登录、多因素策略引擎。
- Spring Modulith、多 Gradle 子项目(见 §2,需新 ADR)。
- 用户资料域(头像/签名/展示名)、邀请发奖逻辑(归 reward)。
- mTLS、OpenBao(架构文档二期项)。
