# CLAUDE.md

本文件指导 Claude Code 在 **gabon-next** 仓库工作。规则源于 `docs/architecture-redesign.md`,以该文档为准。

## 这是什么

gabon 平台**新版后端主仓**:Kotlin/JVM + Spring Boot + jOOQ 模块化单体,承接钱包/内容/feed 等域(替换旧版 `../gabon`)。
技术栈已通过 spike 验证(见 `docs/architecture-redesign.md` C11),现进入**正式开发**——此前 spike 定下的 pattern(钱核双分录、outbox、协程边界、jOOQ-only、lint)就是要沿用和扩展的形态,不要推翻。

## 先读架构文档(硬规则)

**写任何代码前先读 `docs/architecture-redesign.md`**,理解新代码在系统中的位置。
- Part A 止血清单 / Part B 目标架构 ADR / Part C 实施蓝图(账本、队列、feed、生产硬化、安全、测试)。
- 架构冲突即停,先澄清,不假设。

## 已验证技术栈(具体版本,勿擅自升降)

| 组件 | 版本 | 备注 |
|---|---|---|
| JDK | **25 LTS**(Zulu) | 全链 toolchain 25 + jvmTarget 25 |
| Kotlin | **2.4.0** | `allWarningsAsErrors=true` |
| Gradle | **9.6.1**(Kotlin DSL) | 配置缓存 + build cache |
| Spring Boot | **4.1.0** | autoconfig 已模块化(见下 Flyway 坑) |
| jOOQ | **3.21.5** | KotlinGenerator,自定义 `jooqCodegen` task(非官方插件) |
| Flyway | **11.3.0** | 强制覆盖 BOM 旧版(识别 PG18) |
| PostgreSQL | **18**(Testcontainers `postgres:18-alpine`) | 本机不装 |
| Testcontainers | **2.0.5** | 坐标 `org.testcontainers:testcontainers-postgresql`,包 `org.testcontainers.postgresql.*` |
| ArchUnit | **1.4.2** | 1.3.x 读不了 JDK25 字节码 |
| ktlint | **14.2.0**(稳定版) | |
| detekt | **2.0.0-alpha.5**(插件 ID `dev.detekt`) | 稳定版 1.23.x 不支持 Kotlin 2.4 |
| kotlinx.coroutines | **1.10.1** | 仅编排层 |

版本坑详情见 `README.md` 的"实测出来的版本坑"表。

## 构建 / 测试

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-25.jdk/Contents/Home
# OrbStack + Testcontainers:必须显式指向 socket(本机相关,换机器需改)
export DOCKER_HOST=unix:///Users/ethanwang/.orbstack/run/docker.sock
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
./gradlew check            # codegen + 编译 + ktlint + detekt + 16 测试(CI 主入口)

./gradlew test --tests "com.gabon.RechargeIdempotencyTest"   # 跑单个测试类(需同样的 Docker 环境变量)
./gradlew ktlintFormat     # 格式自动修复
./gradlew jooqCodegen      # 单独重新生成 jOOQ 代码
```

环境变量可持久化:`~/.testcontainers.properties` 写 `docker.host=unix:///Users/ethanwang/.orbstack/run/docker.sock`,免每次 export(`JAVA_HOME` 仍需)。

- **本机零服务依赖**:只需 JDK 25 + Gradle Wrapper + Docker。PG/Valkey/S3 mock 一律 Testcontainers,**本机不装** PG/Valkey/Flyway CLI/jOOQ CLI/ffmpeg。
- jOOQ 代码由 `jooqCodegen` task 生成(启 Testcontainers PG → 跑 Flyway → KotlinGenerator);输出在 `build/generated/jooq`,**禁止手改**。
- `jooqCodegen` 的 input 是**整个 `build.gradle.kts`** 与 `src/main/resources/db/migration/`:对二者的任何改动(哪怕只调依赖版本)都会重跑 codegen(含起 Testcontainers,约几十秒),属预期行为。

## 测试约定

- 集成测试一律继承 `AbstractIntegrationTest`:单例 PG + 单例 Valkey 容器整个测试 JVM 复用 + `@BeforeEach` truncate 全表;**新增表必须同步加进其 truncate 列表**,否则测试间脏数据。
- 连接注入当前用 `@DynamicPropertySource`(PG datasource + `spring.data.redis.*`;架构文档 C9 提的 `@ServiceConnection` 是目标形态,尚未采用)。Valkey 客户端依赖随第三批进入,本批仅容器与属性。
- ArchUnit 依赖 Gradle 注入的 `archunit.main.classes` 系统属性;脱离 Gradle 直跑(IDE)会导入 0 类、断言空过,**以 `./gradlew check` 结果为准**。

## 硬规则(多数由 ArchUnit 强制,见 `src/test/.../ArchitectureTest.kt`)

- **持久层只用 jOOQ**:禁 MyBatis/MyBatis-Plus、JPA/Hibernate、分散的 `JdbcClient`(ArchUnit 断言)。裸 SQL 用 jOOQ plain SQL API。Kotlin 条件用 `.eq()/.ge()` DSL,**禁用 `==`/`>=`**。
- **协程边界**(见 B5.1):`suspend` 函数**禁带 `@Transactional`**;钱核(`..wallet..`/`..recharge..`/`..withdraw..`)阻塞 + `@Transactional` + 虚拟线程,**不依赖 kotlinx.coroutines**(ArchUnit 断言);协程仅用于 feed/外部 IO 编排(结构化并发/超时/取消,不是吞吐手段)。
- **钱核**:双分录(一笔 txn ≥2 行、Σ=0,延迟约束触发器校验)+ 幂等键 `(biz_type, biz_no)` + 守卫 UPDATE(`WHERE balance>=amount`,0 行即失败)+ 余额投影。**不变量 `balance == Σ ledger` 神圣不可破**;"改余额不写分录"的方法**不得**进钱核 service(守卫探针留测试层)。
- **outbox / 队列**:PG `SKIP LOCKED` 原子领取;拉取必须含**过期租约重捡**:`(status=ready AND next_run_at<=now) OR (status=in_flight AND lease_until<now)`——否则 worker 崩溃后任务永久卡死。
- **Flyway 迁移**:只前滚(expand/contract + 补偿迁移),禁自动回滚;versioned 迁移进环境后**不可变**,只能新增;生产 `spring.flyway.clean-disabled=true`。
- **Boot 4 Flyway 坑**:`flyway-core` 单独在场**不触发**自动迁移,必须用 `spring-boot-starter-flyway` + 显式 `flyway-database-postgresql`。
- **Fail Fast**:内部代码信任类型系统,不加多余 `?:`/`||` fallback;异常第一时间暴露。只在系统边界(用户输入/外部 API)校验。
- **模块边界**:九上下文 `com.gabon.<context>.{api,internal}`;跨上下文只依赖对方 api + 方向白名单 + **表所有权**(业务代码只访问自己上下文的 jOOQ 表,白名单无主的表失败;不覆盖 plain SQL 字符串中的表名)——均由 `ModuleBoundaryTest` 断言,白名单/豁免集中在该文件常量里。

## 静态检查 / lint(工具强制,不靠肉眼,均在 `./gradlew check`)

- `allWarningsAsErrors=true`(编译级)+ ktlint(格式,`ktlintFormat` 自动修)+ detekt(代码味,配置 `config/detekt/detekt.yml`)。
- **旧 Kotlin/Spring flag 别照抄**:`-Xjsr305=strict`(Boot 4 已迁 JSpecify,过时)、`-Xannotation-default-target=param-property`(Kotlin 2.4 已是默认,加了会因 `-Werror` 报冗余失败)——两者都**不加**。
- 编辑器级 inspection(如 `ConvertLongToDuration`)由 IDE/LSP 实时提示,不进 CI 门禁,不上 Qodana。
- `kotlin-reflect` 须**显式声明**(Spring 要求 classpath 有,勿赖传递引入)。

## 目录结构

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

## 交流约定

- 与用户交流用**中文**(代码/标识符/命令/包名保留英文)。
- 写代码前先给【方案 + 改动文件 + 验证命令】等确认;>3 文件先拆分;改完必须实际跑 `./gradlew check` 再说完成。
