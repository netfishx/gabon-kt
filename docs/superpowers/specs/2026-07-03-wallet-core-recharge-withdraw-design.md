# 设计:钱核完全体 + 充值/提现(迁移子项目 2)

> 日期:2026-07-03 ｜ 状态:逐节已确认,终稿待 review ｜ 上游:`docs/architecture-redesign.md`(C2/C3/C2.4/C2.5/C2.6)
> 本 spec 经 brainstorming 流程逐节确认,判断点均有用户定案;spec 与架构文档冲突时以架构文档为准并回改本文。

## 1. 背景与范围

子项目表(见 `2026-07-02-module-boundaries-identity-design.md` §1)中的 **#2 钱核完全体**,依赖已完成的 #1(模块边界 + 身份域)。

**本批范围**:
- wallet 钱核记账 API 完全体(冻结/结算/解冻/发奖,含 C2.1 三层不变量全部落地)
- recharge 域全链路(档位、下单、渠道回调、入账)
- withdraw 域全链路(申请冻结、admin 审批、outbox 出款 worker、回调结算/解冻)
- 渠道 SPI + 假渠道(真实渠道适配归子项目 7 切流前)

**不在本批**:reward 消费方(子项目 3,本批只落 `grantReward` 实现)、充值超时关单任务(状态 `CANCELLED` 先留)、对账差错表与人工重放 admin 工具(子项目 7 / 二期)、真实渠道对接。

## 2. 数据模型

### 2.1 三层不变量(C2.1,第一期非可选)

| 层 | 机制 | 责任 |
|---|---|---|
| ① 应用 | ledger service 统一收口,`postEntries` 写前同事务断言每笔 Σ=0 且 ≥2 行 | "统一收口"由 service + ArchUnit/契约测试保证 |
| ② 权限 | `gabon_app` 对 `ledger_txn`/`ledger_entry` 无 UPDATE/DELETE | "只追加"由权限保证 |
| ③ DB | V1 既有 `trg_ledger_balanced` deferrable constraint trigger,提交时校验每 txn Σ=0 且 ≥2 行,保留不动 | 兜底:即使绕过 service 直插不平分录,提交时被拒 |

注意责任分工:`gabon_app` 的 SELECT, INSERT 权限仍允许绕过 service 直插分录——不平分录被 ③ 挡住,平衡但越权的分录靠 ① 的收口纪律(ArchUnit + code review)防,②③ 不防"平衡的越权写入"。

### 2.2 V3__wallet_orders.sql

三张表沿 V1/V2 风格(identity PK、`created_at/updated_at` + `set_updated_at` 触发器、status smallint + CHECK)。

**`recharge_package`(充值档位,owner: recharge)**

| 列 | 约束 |
|---|---|
| `diamonds` bigint | `check (diamonds > 0)` |
| `price_cents` bigint / `currency` char(3) | `check (price_cents > 0)`、`check (currency ~ '^[A-Z]{3}$')` |
| `status` smallint | `check (status in (0,1))`,1=上架 0=下架;列表按价格排,不设排序列 |

**`recharge_order`(owner: recharge)**

| 列 | 约束 |
|---|---|
| `order_no` text | unique + 非空白 check;同时是账本幂等键 `biz_no`;生成规则 `"R-" + UUIDv7` |
| `customer_id` bigint | 仅存 id,**不跨上下文 FK**(与 V1 `account.owner_id` 口径一致) |
| `package_id` bigint | **FK → recharge_package(id)**(同上下文内 FK 允许) |
| `diamonds`/`price_cents`/`currency` | 下单时从档位**快照**(防改价影响在途订单),正数/币种 check 同上 |
| `channel` smallint / `channel_order_no` text | `check (channel > 0)`、`channel_order_no` 非空白 check(允许 null);部分唯一索引 `unique(channel, channel_order_no) where channel_order_no is not null`(一渠道流水号只映射一本地单) |
| `status` smallint | `1=CREATED 2=PROCESSING 3=SUCCESS 4=FAILED 5=CANCELLED`(C2.4) |
| 索引 | `(customer_id, id)`(keyset 订单列表) |

**`withdraw_order`(owner: withdraw)**

| 列 | 约束 |
|---|---|
| `order_no` / `customer_id` / `diamonds` / `channel` | 同 recharge_order 口径;`order_no` 规则 `"W-" + UUIDv7` |
| `payout_cents` bigint / `currency` char(3) | 按固定汇率配置换算的**快照**,正数/币种 check |
| `payout_account` jsonb | `not null check (jsonb_typeof(payout_account) = 'object')`;收款要素不建模,透传渠道 SPI |
| `channel_payout_no` text | 非空白 check(允许 null);部分唯一索引 `unique(channel, channel_payout_no) where channel_payout_no is not null` |
| `review_memo` text(可空)/ `reviewed_by` bigint / `reviewed_at` timestamptz | check ①`reviewed_by`/`reviewed_at` 同空同非空;②`(status = 1) = (reviewed_by is null)`(双向:PENDING ⟺ 无审批留痕——单向式会放过"回到 PENDING 却带留痕"的矛盾态,2026-07-03 质量评审收紧) |
| `status` smallint | `1=PENDING 2=APPROVED 3=PROCESSING 4=SUCCESS 5=FAILED 6=REJECTED`(C2.4) |
| 索引 | `(customer_id, id)`、`(status, id)`(admin 待审列表 keyset) |

**配套(与迁移同批,否则 codegen 后 ArchUnit 红)**:
- `ModuleBoundaryTest.TABLE_OWNER` 登记(key 是 **jOOQ 生成类 simpleName**,与既有 `"Account"`/`"LedgerTxn"` 条目同一口径,不是 SQL 表名):`"RechargePackage"`/`"RechargeOrder"` → `com.gabon.recharge.internal`,`"WithdrawOrder"` → `com.gabon.withdraw.internal`
- `AbstractIntegrationTest` truncate 列表加三张新表

### 2.3 V4__app_role_grants.sql(权限分离,可执行方案)

- **role 创建**:`DO $$` 守卫(role 是集群级对象,迁移需可重跑)创建 `gabon_app LOGIN`,**迁移不带密码**——生产密码由部署侧 `ALTER ROLE` 预置/secret 管理;测试由 `AbstractIntegrationTest` 容器初始化时用 owner 连接执行 `ALTER ROLE gabon_app PASSWORD 'test'`。**生产部署前提(二选一)**:预先创建 `gabon_app`(推荐,DO guard 幂等跳过),或给迁移 role CREATEROLE 权限——DO guard 只负责幂等,不绕过集群权限模型;Testcontainers 的 owner 是 superuser 故测试天然可建。
- **schema 与 sequence**:`GRANT USAGE ON SCHEMA public`;`GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public`(identity 列底层 sequence,缺此 INSERT 运行时炸)。
- **先 REVOKE 后 GRANT**(不依赖默认权限状态):`REVOKE ALL ON ledger_txn, ledger_entry FROM gabon_app` → 只 `GRANT SELECT, INSERT`;其余业务表 `GRANT SELECT, INSERT, UPDATE, DELETE`。
- **`ALTER DEFAULT PRIVILEGES`**:为未来迁移新建的表/sequence 默认授正常 DML + USAGE,免 V5+ 每张新表手工补 GRANT。**前提钉死:default privileges 只影响执行者(owner)后续创建的对象——Flyway/codegen/测试三处必须用同一 owner 连接跑迁移,否则默认授权不生效**。未来若再有 append-only 表,在其迁移里显式 REVOKE。
- **连接拓扑**:迁移/codegen 一律 owner 连接(`spring.flyway.user/password` 显式配置,不与 `spring.datasource.*` 混用;`jooqCodegen` 的 Testcontainers 流程天然 owner,不受影响);业务 datasource 切 `gabon_app`;测试双连接——runtime `DSLContext` 走 `gabon_app`(权限被全量测试天天验证),truncate/直插探针走 owner 连接。
- **验收探针**:测试断言 `gabon_app` 连接上 `UPDATE ledger_entry` 被权限拒(②层回归钉子);owner/`gabon_app` 直插不平分录提交时被 trigger 拒(③层钉子)。

## 3. wallet 钱核 API 完全体

### 3.1 API 形态

`wallet.api` 新增 **`WalletLedgerApi`**(只放写语义方法),实现在 `internal/ledger/LedgerService`;读口留在既有 `WalletBalanceApi`(新增 `frozenOf(customerId): Long`,契约同 `balanceOf`:无账户返回 0)。

幂等键与业务来源焊死在签名里——**无 entries 入参、无账户类型入参**,分录组装永远在钱核内部:

```kotlin
interface WalletLedgerApi {
    fun creditRecharge(orderNo: String, customerId: Long, diamonds: Long): Boolean
    fun freezeForWithdraw(withdrawNo: String, customerId: Long, diamonds: Long): Boolean
    fun settleWithdraw(withdrawNo: String, customerId: Long, diamonds: Long): Boolean
    fun releaseFrozen(withdrawNo: String, customerId: Long, diamonds: Long): Boolean
    fun grantReward(rewardNo: String, customerId: Long, diamonds: Long): Boolean
}
```

| 方法 | 分录(Σ=0) | biz_type |
|---|---|---|
| creditRecharge | available +N / payment_clearing −N | RECHARGE=1(已有) |
| freezeForWithdraw | available −N(守卫) / frozen +N | WITHDRAW_FREEZE=2 |
| settleWithdraw | frozen −N(守卫) / payout_clearing +N | WITHDRAW_SETTLE=3 |
| releaseFrozen | frozen −N(守卫) / available +N | WITHDRAW_RELEASE=4 |
| grantReward | available +N / platform_equity −N | REWARD=5(消费方在子项目 3) |

- 返回 `true`=本次入账,`false`=幂等短路(沿 spike 口径);守卫 0 行抛 `ProblemException(INSUFFICIENT_BALANCE)`(§7 新增 problem,409)。
- `require(diamonds > 0)`:跨上下文契约 fail-fast;端点输入能触发的场景由 recharge/withdraw controller DTO 层先校验成 400 validation,不靠 require 出 500。
- 同一 `withdrawNo` 在 2/3/4 三个 biz_type 下互不冲突、各自幂等。

**责任边界(钉死)**:settle 与 release 对同一笔提现的互斥**不由钱核保证**(幂等键不同,钱核无法跨 txn 互斥),由 withdraw 状态机 CAS 单一终态保证;钱核只保证各方法自身幂等 + 每笔 Σ=0。

### 3.2 写入骨架(五方法同构)

1. 插 `ledger_txn` 头(`onConflictDoNothing` + RETURNING),冲突即短路返回 false——**幂等门最先**,重复请求不碰守卫;
2. ensure 相关账户(自动开户 upsert,沿 spike `accountId`),拿到 account id;
3. 守卫 UPDATE 扣减侧(`WHERE balance >= amount`,0 行抛出→事务回滚,txn 头一并消失,不留垃圾头);非守卫侧 bump;
4. `postEntries(txnId, entries)`:**唯一写 `ledger_entry` 的生产入口**(私有漏斗),写前 `require(entries.size >= 2 && entries.sumOf { it.amount } == 0L)`——①层断言的代码形态。测试探针可 owner 直插,业务代码不得出现第二个入口。

提交时 V1 deferred trigger 兜底(③层)。

### 3.3 测试

- 并发双 freeze(余额仅够一次)→ 恰一成功、恰一 `INSUFFICIENT_BALANCE`;
- 并发同 `biz_no` 双 credit → 恰一入账;
- **同 `biz_no` 不同 `diamonds` 重放** → 返回 false 且余额/分录不变(防幂等门被后移);
- 五方法幂等重放各一;
- 权限探针(§2.3)两条;
- **不变量断言工具**(测试共享资产):全量账户 `balance == Σ ledger_entry.amount`(按 account_id 全量比对,不只 customer 账户)+ 全局 `Σ entry.amount = 0`;资金链路 E2E 测试终局必跑。

## 4. recharge 域

### 4.1 端点

| 端点 | 权限 |
|---|---|
| `GET /v1/recharge/packages` | CUSTOMER |
| `POST /v1/recharge/orders {packageId, channel}` | CUSTOMER |
| `GET /v1/recharge/orders?cursor=` | CUSTOMER;keyset `customer_id = me AND id < cursor ORDER BY id DESC LIMIT n`,cursor = last seen id |
| `POST /v1/recharge/callback/{channel}` | **公开路由**(`PublicRoutesContributor` 登记),安全靠验签 |

CUSTOMER 角色由 SecurityConfig 显式规则实现(§7),不靠 controller 自查。

### 4.2 下单(两步)

本地建单(`order_no = "R-" + UUIDv7`,快照档位三列,`CREATED`)→ 调渠道 SPI `createPayment` → 回填 `channel_order_no` + CAS `CREATED→PROCESSING` → 支付凭据透传前端。

- 渠道下单失败:订单留 `CREATED`(对账可见),对外 `ProblemException(PAYMENT_CHANNEL_ERROR)`(502),用户重新下单;
- 档位不存在/未上架、未知 channel → 400 validation。

### 4.3 回调(单事务,顺序钉死)

SPI `verifyAndParse`(raw body + headers,先验签后解析)→ **金额币种校验** → inbox 去重、状态机 CAS、入账**同一事务**:

1. **金额币种校验(安全关键,仅 Success——Failure 无金额字段)**:`callback.paidCents == order.price_cents && callback.currency == order.currency`,不匹配 → **不落 inbox、不改状态、结构化 ERROR + 指标、2xx ack**(签名已过说明源头是渠道侧错配,非 2xx 只会让同一条错配回调无限重试,不会变对);
2. **渠道号回填/校验**:本地 `channel_order_no` 为空(下单第二步崩溃场景)→ 同事务回填;已有且与回调**不同** → 不落 inbox、不改状态、不入账,结构化 ERROR + 指标 + 2xx ack(处理同金额错配)。Success 必带渠道号;Failure 的 `channelOrderNo` 可空,**带了就必须过同一校验**,不得绕过一致性直接打 FAILED;
3. inbox `(source, external_id)` 插入冲突即短路 2xx ack——**source 是全局命名空间,不是裸 channel**:收款/出款 channel 编码两域独立(§5.1),裸 channel 会让 recharge channel=1 与 withdraw channel=1 的同一 `external_id` 跨域互吞。定案 `source = 域基数 + channel`:recharge 基数 1000,withdraw 基数 2000(smallint 内,channel > 0 check 保证不越域);
4. Success → CAS `status in (CREATED, PROCESSING) → SUCCESS`,命中才 `creditRecharge(orderNo, customerId, diamonds)`;Failure → 同宽度 CAS → `FAILED`;
5. 事务失败全体回滚(含 inbox 行),渠道重试可完整重放——**inbox 记录与业务效果同生共死**(C2.5),"inbox 已记、业务没做"会吞掉重试,禁止拆开。

**定案与偏差说明**:
- 回调 CAS 比 C2.4 宽:`CREATED|PROCESSING → 终态`。理由:两步下单间崩溃时订单停在 `CREATED` 但渠道侧支付真实存在,渠道真相优先。
- **Failure 语义收紧**:adapter 仅在渠道**明确终态失败**时产出 Failure;pending/unknown/查询失败一律 `Ignored`(§5)。
- 终态冲突回调(如 FAILED 后又来 SUCCESS):CAS 0 行 → **2xx ack + 结构化 WARN + 指标**,不翻案不静默(对账差错表归子项目 7)。
- ack 语义:重复回调/CAS 0 行/终态冲突均 2xx(渠道停止重试);验签失败 401、解析失败 400,均不落 inbox。
- 防重三层:inbox 唯一键(同一回调重放)→ 状态机 CAS 单一终态(不同回调打同一订单)→ 账本幂等键(前两层皆漏时钱不重入)。

### 4.4 测试

重复回调 ack 且不重复入账;**同 `external_id` 跨域不互吞**(recharge 与 withdraw 同 channel 编码各自去重);PROCESSING 并发重复成功回调只入账一次;`CREATED` 直收成功回调可入账并回填渠道号(两步间崩溃场景);渠道号错配 ack 不入账不改状态(Success 与带渠道号的 Failure 两路);`FAILED` 后再收 `SUCCESS` 不翻案(WARN 路径);验签失败 401 不落 inbox;金额错配 ack 不入账;档位下架 400;keyset 翻页。

## 5. 渠道 SPI 与假渠道

### 5.1 归属与注册

收款/出款 SPI 各归各域 internal,不进 api、不跨上下文:`recharge.internal.channel.PaymentChannel`、`withdraw.internal.channel.PayoutChannel`。channel 编码两域独立空间。

`ChannelRegistry`(各域一个):Spring 注入 `List<PaymentChannel>` 构建 `Map<Short, PaymentChannel>`,**启动时 code 重复 fail-fast**(后注册静默覆盖先注册是危险态);未知 channel → 400 validation。

### 5.2 接口形态

```kotlin
interface PaymentChannel {
    val code: Short
    fun createPayment(order: RechargeOrderSnapshot): PaymentInstruction
    fun verifyAndParse(rawBody: ByteArray, headers: Map<String, String>): PaymentCallback
}
sealed interface PaymentCallback {
    class Success(externalId, orderNo, channelOrderNo, paidCents: Long, currency: String) : PaymentCallback
    class Failure(externalId, orderNo, channelOrderNo: String?, reason: String) : PaymentCallback
        // 仅渠道明确终态失败;channelOrderNo 带了就必须过 §4.3-2 一致性校验
    class Ignored(externalId) : PaymentCallback                           // pending/unknown → ack,不落 inbox,不动状态
}

interface PayoutChannel {
    val code: Short
    fun submitPayout(order: WithdrawOrderSnapshot): PayoutSubmission      // 含渠道受理号
    fun verifyAndParse(rawBody: ByteArray, headers: Map<String, String>): PayoutCallback
}
sealed interface PayoutCallback {  // 同三态;Success/Failure 均带 channelPayoutNo 与终态 reason
    class Success(externalId, orderNo, channelPayoutNo, paidCents: Long?, currency: String?) : PayoutCallback
    class Failure(externalId, orderNo, channelPayoutNo: String?, reason: String) : PayoutCallback
    class Ignored(externalId) : PayoutCallback
}
```

**契约钉死**:
- `verifyAndParse` 基于 **raw body + headers** 验签,先验后解析(反序列化再验签会被 JSON 字段顺序/空白/编码破坏);headers key **大小写不敏感归一化**后传入;
- `Success.paidCents/currency` 必须携带(收款);出款回调若渠道提供金额则同样校验 `payout_cents/currency`,错配处理同 §4.3-1;
- **`Ignored` 不落 inbox**:渠道中间态通知若与终态通知共用 `externalId`,落 inbox 会把真终态挡在门外;
- **`submitPayout` 必须以 `order_no` 幂等**:outbox worker 重试会重复调用,同一单号须返回同一受理结果,不得重复打款(真实渠道靠商户单号幂等)。

### 5.3 假渠道

- 两域各一(`FakePaymentChannel`/`FakePayoutChannel`),验签 **HMAC-SHA256 对 raw body 计算**、密钥配置注入——让 raw body 验签路径真实可测,非空壳 return true;第一期两方向可共用一个 secret,真实渠道按 channel 独立配置;
- `FakePaymentChannel.createPayment` 返回假支付跳转 payload;
- `FakePayoutChannel.submitPayout` 记录受理并可配置成功/失败/超时行为(供 worker 退避与 DLQ 测试);**同 `order_no` 重复提交必须返回同一受理号**(模拟真实渠道幂等语义,worker 重试测试的前提);
- 注册按 `gabon.channel.fake.enabled` 开关(测试恒开);真实渠道适配在子项目 7 切流前替换,SPI 不动调用方。

## 6. withdraw 域 + outbox 出款 worker

### 6.1 端点

| 端点 | 权限 |
|---|---|
| `POST /v1/withdraw/orders {diamonds, channel, payoutAccount}` | CUSTOMER |
| `GET /v1/withdraw/orders?cursor=` | CUSTOMER |
| `POST /v1/withdraw/callback/{channel}` | 公开 + 验签 |
| `GET /v1/admin/withdraw/orders?status=&cursor=` | ADMIN(既有角色门);status 过滤限定枚举值,非法 400 validation,不默默查空 |
| `POST /v1/admin/withdraw/orders/{id}/approve` / `.../reject {memo}` | ADMIN |

### 6.2 申请(单事务)

DTO 校验(正数、`gabon.withdraw` min/max 限额、`payoutAccount` 非空 object)→ 建单 `PENDING`(`payout_cents` 按固定汇率配置换算快照)→ `freezeForWithdraw(orderNo, ...)`。余额不足 → 409 回滚,**不留订单**。

### 6.3 审批

- **approve**:CAS `PENDING→APPROVED`(0 行 → `ORDER_STATE_CONFLICT` 409)+ 审批留痕 + **同事务写 outbox 事件** `PAYOUT_SUBMIT`——事务性 outbox 的本尊用法。**payload 只放 `orderNo`**:handler 每次从 DB 读最新订单快照,订单表是唯一真相,不把金额/收款要素塞进 payload 造双真相。
- **reject**:CAS `PENDING→REJECTED` **命中后**才 + 留痕 + memo + 同事务 `releaseFrozen`;CAS 0 行不解冻(重复 reject 不二次解冻——虽然 releaseFrozen 自身幂等,顺序纪律仍须钉死)。

### 6.4 出款执行(outbox handler,全量幂等可重入)

1. 读单;终态(SUCCESS/FAILED/REJECTED)→ 直接完成(幂等);
2. CAS `APPROVED → PROCESSING`(短事务);0 行且当前已是 `PROCESSING` → 继续(重入重放);0 行且其它态 → 完成不出款;
3. `submitPayout`(**网络调用不裹 DB 事务**);
4. 回填 `channel_payout_no`(短事务,**幂等**):订单已有相同受理号 → 直接成功;已有**不同**受理号 → 不覆盖,结构化 ERROR + 指标。

任一点崩溃,outbox 租约过期重捡后凭 SPI `order_no` 幂等安全重放;"handler 成功但标 DONE 失败"的重放同样被此骨架吸收。

### 6.5 出款回调

同 §4.3 骨架(验签 → 金额币种校验(若渠道提供)→ 渠道号回填/校验 → inbox/CAS/记账同事务):
- **`channel_payout_no` 回填/校验**同 §4.3-2:本地为空(受理号回填前崩溃场景)→ 同事务回填;已有且不同 → ERROR + 指标 + 2xx ack,不改状态不记账;
- Success → CAS `APPROVED|PROCESSING→SUCCESS` + `settleWithdraw`;
- Failure(仅明确终态,网络失败/处理中/未知/查无此单一律 Ignored)→ 同宽度 CAS `→FAILED` + `releaseFrozen`。

宽 CAS 理由同 recharge:受理号回填前崩溃时渠道真相优先(`APPROVED` 直收成功回调可结算)。

### 6.6 outbox worker 骨架(platform.outbox,业务无关)

- `@Scheduled fixedDelay` 阻塞轮询(钱核血缘禁协程);
- **`OutboxRepo.lease` 返回值升级为 `LeasedOutboxRow(id, eventType, payload, attempts, maxAttempts)`**(现返回 `List<Long>` 不够派发 handler 与算 DLQ);
- **`OutboxHandler` 接口定义在 `platform.outbox`**,withdraw 实现接口;Spring 注入 `List<OutboxHandler>` 建 registry(`event_type → handler`,启动时重复 fail-fast)——platform 不 import withdraw,依赖方向不破;
- 完成语义:成功 → `DONE`;抛异常 → **按领取时 +1 后的 `attempts` 判断**:`attempts >= max_attempts` → `DEAD` + ERROR 告警日志(人工重放入口 = 二期 admin 工具);否则 → `READY` + `next_run_at = now + min(2^attempts × base, cap)` 指数退避(防 off-by-one);
- **过期租约结果不可覆盖(stale worker 防护)**:markDone/markRetry/markDead 一律 `WHERE id = ? AND status = IN_FLIGHT AND attempts = leasedAttempts`,0 行 = 本 worker 的租约已被重捡(handler 跑超租期,他人已接管)→ **no-op 静默放弃**,不得覆盖新租约的状态;handler 业务效果由其自身幂等吸收(§6.4);
- worker 状态落库与 handler 业务事务分离(handler 自管事务,骨架独立短事务记结果)。

### 6.7 测试

余额不足申请回滚不留单;approve 并发双击恰一成功;approve 与 outbox 事件同事务(回滚场景验证);重复 reject 不二次解冻;全链路 E2E(申请→审批→worker→假渠道→回调→结算)终局跑全量不变量断言;假渠道持续失败→退避→DLQ;受理号冲突不覆盖 + ERROR;stale worker 防护——租约过期被重捡后,原 worker 的 markDone/markRetry 0 行 no-op 不覆盖新租约;回调乱序/重复/金额错配;`APPROVED` 直收回调可结算并回填受理号;admin 非法 status 400。

## 7. 横切收口

### 7.1 新增 ProblemType

| 类型 | 状态码 | URI | 标题 |
|---|---|---|---|
| `INSUFFICIENT_BALANCE` | 409 | `/problems/insufficient-balance` | Insufficient balance |
| `ORDER_STATE_CONFLICT` | 409 | `/problems/order-state-conflict` | Order state conflict |
| `PAYMENT_CHANNEL_ERROR` | 502 | `/problems/payment-channel-error` | Channel error(覆盖收款下单与出款提交失败) |

其余复用既有(validation/unauthenticated/forbidden/auth-store-unavailable)。

### 7.2 安全链顺序(钉死在 SecurityConfig)

1. 公开路由 permitAll——callback 用**精确模式** `/v1/recharge/callback/*`、`/v1/withdraw/callback/*`(经 `PublicRoutesContributor` 由各域贡献),**必须注册在 hasRole 规则之前**;
2. `/v1/admin/**` → ADMIN(既有,覆盖 admin 提现审批);
3. `/v1/recharge/**`、`/v1/withdraw/**` → **hasRole(CUSTOMER)**(新增,堵 admin token 串门);
4. `anyRequest` → authenticated(既有兜底)。

### 7.3 配置(@ConfigurationProperties,时间量一律 `java.time.Duration` 类型,防秒/毫秒混用)

| 前缀 | 项 |
|---|---|
| `gabon.withdraw` | min/max 钻石、汇率(钻石→cents)、币种 |
| `gabon.channel.fake` | enabled、HMAC secret |
| `gabon.outbox.worker` | poll 间隔(Duration)、batch、租期(Duration)、退避 base/cap(Duration) |

### 7.4 测试全局资产

- **不变量断言工具**(§3.3):资金链路 E2E 终局必跑;
- ArchUnit 零新增即覆盖:钱核禁协程已含 `..recharge..`/`..withdraw..`;表所有权三张新表登记即受断言;
- 权限双连接基建(§2.3)供全部测试复用。

### 7.5 实施批次(plan 阶段细化)

| 批 | 内容 |
|---|---|
| 1 | V3+V4 迁移、双连接测试基建、表所有权登记、wallet 钱核 API 完全体(含权限/trigger 探针、并发测试、不变量工具) |
| 2 | recharge 域全链路(档位、下单、SPI + 假渠道、回调、列表、CUSTOMER 角色规则) |
| 3 | withdraw 域 + outbox worker(申请、审批、出款执行、回调、DLQ、E2E 不变量收口) |
