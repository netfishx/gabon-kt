# gabon-next

gabon 平台**新版后端主仓**(Kotlin/JVM + Spring Boot + jOOQ 模块化单体)。
技术栈已通过 spike 验证(见 `docs/architecture-redesign.md` C11),现进入正式开发。
**开发规则见 `CLAUDE.md`;架构设计见 `docs/architecture-redesign.md`(先读再写)。**

## 技术栈基线(已验证,作为正式开发基线)

`JDK 25 + Kotlin 2.4 + Gradle 9.6 + Spring Boot 4.1 + jOOQ KotlinGenerator + Flyway + Testcontainers`
整链在 JDK 25 上跑通,当前骨架 9 测试全绿(ArchitectureTest 4 + OutboxLease 2 + 幂等/守卫/协程 各 1)。已落地并作为范式保留的能力:

1. **Kotlin + Spring 机制** — `@Service`/`@Transactional` AOP 代理、`@ConfigurationProperties`(`WalletProps` data class 构造器绑定)、jackson-kotlin 均正常(Spring 测试全通过即证明)。
2. **jOOQ KotlinGenerator** — 从 Flyway 迁移后的 PG18 生成 Kotlin;`ON CONFLICT`、`RETURNING`(outbox 单条 `UPDATE ... WHERE id IN (SELECT ... FOR UPDATE SKIP LOCKED) RETURNING`)、`SKIP LOCKED`、**真实 forced type + Converter**(`account.kind`→`AccountKind` 枚举,经 insert/select 往返)全部可用。codegen 为自定义 Gradle task 调 `GenerationTool`(非官方插件)。
3. **Gradle 9.6 × KGP 2.4 + 构建速度** — 配置缓存 stored→reused;第二次 `./gradlew check` 全 `UP-TO-DATE`(`jooqCodegen` 不重跑 Testcontainers),2s。
4. **并发边界** — `suspend` feed 编排并发 fan-out 调阻塞钱核;虚拟线程(`spring.threads.virtual.enabled`)+ HikariCP;`@Transactional` 钱核保持阻塞。

## 运行

前置:JDK 25、Docker(本机为 OrbStack)。

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-25.jdk/Contents/Home
# OrbStack + Testcontainers:必须显式指向 socket
export DOCKER_HOST=unix:///Users/ethanwang/.orbstack/run/docker.sock
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
./gradlew check
```

或持久化(免每次 export):`~/.testcontainers.properties` 写
`docker.host=unix:///Users/ethanwang/.orbstack/run/docker.sock`。

## 静态检查 / lint(工具强制,不靠肉眼)

社区轻量实践:detekt + ktlint;**不上 Qodana**(跑全套 IDE inspection 偏重,社区视为多数项目 overkill)。三者均已实测跑通并**接入 `./gradlew check`**:

- **kotlinc `allWarningsAsErrors=true`**:编译级告警 / 废弃 API 直接失败。
- **ktlint 14.2.0**(稳定版):格式 / 风格;`ktlintFormat` 自动修,`ktlintCheck` 进 check(排除生成代码,声明依赖 codegen)。
- **detekt 2.0.0-alpha.5**(**新插件 ID `dev.detekt`**):代码味 / 复杂度 / 潜在 bug。⚠️ detekt **稳定版 1.23.x 不支持 Kotlin 2.4**(报 "compiled with 2.0.21, running 2.4.0"),必须用 2.0 alpha(`dev.detekt`);配置 `config/detekt/detekt.yml`(枚举码不算 magic number、行长 140),只扫手写源、不扫生成代码。
- **编辑器级 inspection**(你在 Zed 看到的 `ConvertLongToDuration` 等):由 Zed 的 Kotlin LSP 实时提示——社区做法是这类当编辑期建议、不进 CI 门禁,不为它上 Qodana。

## 实测出来的版本坑(已回填架构文档)

整链"较新"确实咬了几口,均已解决,已回填 `docs/architecture-redesign.md`:

| 组件 | 现象 | 结论 |
|---|---|---|
| **Testcontainers** | Docker 29 最低 API 1.44,TC ≤1.x 报 "client version 1.32 too old";且 2.0 模块坐标改为 `testcontainers-postgresql`、包名迁到 `org.testcontainers.postgresql` | 用 **2.0.5**,强制覆盖 Boot BOM 旧版 |
| **Flyway** | Boot BOM 的 Flyway 版本太旧,不识别 PG18 | 强制 **11.3.0**(与 codegen 一致) |
| **ArchUnit** | 1.3.0 的 ASM 读不了 JDK 25(class v69)字节码 → 静默导入 0 类 | 升 **1.4.2** |
| **Gradle** | 9.6.1 + KGP 2.4 + JDK 25 实测正常 | **9.6.1** 从候选(🧪)转锁定 |
| **jOOQ** | KotlinGenerator 生成 Kotlin,DSL 全可用 | **3.21.5** |
| **OrbStack** | TC 默认探测不到 socket | 需 `DOCKER_HOST` + `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE` |
| **Kotlin flag** | `-Xannotation-default-target=param-property` 在 Kotlin 2.4 报"冗余"→ `-Werror` 下编译失败 | **不加**(2.4 已是语言默认);旧指南 flag 要按版本重核 |

### Boot 4 Flyway 自动迁移(已解决并验证)

Boot 4 autoconfig 模块化后,`flyway-core` 单独在场**不再**触发自动迁移。修法:用
**`spring-boot-starter-flyway`**(含 `FlywayAutoConfiguration`)+ 显式 `flyway-database-postgresql`
(不随 starter 传递),`spring.flyway.enabled=true`。删掉测试里的手动 `Flyway.migrate()` 后,表由
Boot 启动时自动建、9 测试仍全绿 → 运行期自动迁移已验证。

### Kotlin/Spring 空安全 flag(旧指南两条都不必要)

- **`kotlin-reflect` 显式声明**:Spring 要求 classpath 有,勿赖传递引入(已显式加,`check` 仍绿)。
- **不加 `-Xjsr305=strict`**:Boot 4 / Framework 7 改用 **JSpecify**,Kotlin 2 自动翻译成空安全(默认即严格),该 flag 已过时。
- **不加 `-Xannotation-default-target=param-property`**:**Kotlin 2.4 已是语言默认**,再加会因 `-Werror` 报"冗余"编译失败(见上表)。
- 教训:旧博客/指南的编译器 flag 必须对当前 Kotlin/Spring 版本重新核实,别照抄。
