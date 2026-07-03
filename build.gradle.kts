import org.flywaydb.core.Flyway
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jooq.codegen.GenerationTool
import org.jooq.meta.jaxb.Database
import org.jooq.meta.jaxb.ForcedType
import org.jooq.meta.jaxb.Generate
import org.jooq.meta.jaxb.Generator
import org.jooq.meta.jaxb.Jdbc
import org.jooq.meta.jaxb.Target
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import org.jooq.meta.jaxb.Configuration as JooqConfig

buildscript {
    repositories { mavenCentral() }
    dependencies {
        classpath("org.jooq:jooq-codegen:3.21.5")
        classpath("org.flywaydb:flyway-core:11.3.0")
        classpath("org.flywaydb:flyway-database-postgresql:11.3.0")
        classpath("org.testcontainers:testcontainers-postgresql:2.0.5") // TC 2.0：模块前缀 testcontainers-，传递引入 core
        classpath("org.postgresql:postgresql:42.7.5")
    }
}

plugins {
    kotlin("jvm") version "2.4.0"
    kotlin("plugin.spring") version "2.4.0"
    id("org.springframework.boot") version "4.1.0"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
    id("dev.detekt") version "2.0.0-alpha.5" // detekt 2.0 新插件 ID；支持 Kotlin 2.4 / JDK 25
}

group = "com.gabon"
version = "0.0.1"

repositories { mavenCentral() }

java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_25)
        allWarningsAsErrors.set(true) // 编译级告警/废弃 API 直接失败(CI 拦，不靠肉眼)
        // 注：旧 Spring/Kotlin 指南常加 -Xjsr305=strict 与 -Xannotation-default-target=param-property。
        // 本栈两者都不需要：Boot 4 已改用 JSpecify(Kotlin 2 自动翻译成空安全)；param-property 是 Kotlin 2.4 语言默认(再加会因 -Werror 报冗余错)。
    }
}

val jooqVersion = "3.21.5"
configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jooq" && requested.name in listOf("jooq", "jooq-kotlin")) {
            useVersion(jooqVersion)
        }
        if (requested.group == "org.testcontainers") {
            useVersion("2.0.5") // Docker 29 需 TC ≥2.0.2；覆盖 Spring Boot BOM 的旧版
        }
        if (requested.group == "org.flywaydb") {
            useVersion("11.3.0") // PG18 支持；与 codegen 用的 Flyway 版本一致，覆盖 BOM 旧版
        }
    }
}

val generatedDir = layout.buildDirectory.dir("generated/jooq")
kotlin.sourceSets["main"].kotlin.srcDir(generatedDir)

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jooq")
    implementation("org.jooq:jooq:$jooqVersion")
    implementation("org.springframework.boot:spring-boot-starter-flyway") // Boot 4：autoconfig 在 starter 里，flyway-core 单独不触发
    implementation("org.flywaydb:flyway-database-postgresql") // starter 不传递，需显式加
    implementation("org.jetbrains.kotlin:kotlin-reflect") // Spring 官方要求 classpath 有 kotlin-reflect(勿依赖传递引入)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    runtimeOnly("org.postgresql:postgresql")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // Boot 4 模块化:@AutoConfigureMockMvc 已从 starter-test 拆到 webmvc-test(包 org.springframework.boot.webmvc.test.autoconfigure)
    testImplementation("org.springframework.boot:spring-boot-webmvc-test")
    testImplementation("org.testcontainers:testcontainers-postgresql:2.0.5")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.4.2") // 1.4.x 的 ASM 才能读 Java 25 字节码
}

// jOOQ codegen: 启动 Testcontainers PG → 跑 Flyway → 用 KotlinGenerator 生成
tasks.register("jooqCodegen") {
    group = "build"
    description = "Start Testcontainers PG, run Flyway, generate jOOQ Kotlin sources"
    val migrations = layout.projectDirectory.dir("src/main/resources/db/migration")
    val outDir = generatedDir.get().asFile
    inputs.dir(migrations).withPathSensitivity(PathSensitivity.RELATIVE)
    // codegen 配置(forced types/包名/generator 等)内联在本文件，纳入 input 以免改配置后代码陈旧
    inputs.file(layout.projectDirectory.file("build.gradle.kts"))
    outputs.dir(outDir)
    doLast {
        outDir.deleteRecursively()
        val pg = PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))
        pg.start()
        try {
            Flyway
                .configure()
                .dataSource(pg.jdbcUrl, pg.username, pg.password)
                .locations("filesystem:${migrations.asFile.absolutePath}")
                .load()
                .migrate()

            val cfg =
                JooqConfig()
                    .withJdbc(
                        Jdbc()
                            .withDriver("org.postgresql.Driver")
                            .withUrl(pg.jdbcUrl)
                            .withUser(pg.username)
                            .withPassword(pg.password),
                    ).withGenerator(
                        Generator()
                            .withName("org.jooq.codegen.KotlinGenerator")
                            .withDatabase(
                                Database()
                                    .withName("org.jooq.meta.postgres.PostgresDatabase")
                                    .withInputSchema("public")
                                    .withExcludes("flyway_schema_history")
                                    .withForcedTypes(
                                        // 验收②：真实 forced type —— account.kind → AccountKind(经 Converter)
                                        ForcedType()
                                            .withUserType("com.gabon.wallet.internal.ledger.AccountKind")
                                            .withConverter("com.gabon.wallet.internal.ledger.AccountKindConverter")
                                            .withIncludeExpression("(?i:.*account\\.kind)")
                                            .withIncludeTypes("(?i:smallint|int2)"),
                                    ),
                            ).withGenerate(Generate().withPojos(false).withDaos(false))
                            .withTarget(
                                Target()
                                    .withPackageName("com.gabon.jooq")
                                    .withDirectory(outDir.absolutePath),
                            ),
                    )
            GenerationTool.generate(cfg)
        } finally {
            pg.stop()
        }
    }
}

tasks.named("compileKotlin") { dependsOn("jooqCodegen") }

// lint：ktlint 稳定版可跑 Kotlin 2.4；排除生成的 jOOQ 代码。
// detekt 稳定版尚不支持 Kotlin 2.4（"compiled with 2.0.21, running 2.4.0"），待 2.0.0 stable 再加。
ktlint {
    filter { exclude { it.file.path.contains("/build/generated/") } }
}
// ktlint 的 main 源任务把 generated 目录当输入，需显式依赖 codegen（Gradle 9 严格校验）
tasks.matching { it.name.startsWith("runKtlint") }.configureEach { dependsOn("jooqCodegen") }

// detekt 2.0：只扫手写源，避开 build/generated（jOOQ 生成代码不参与）
detekt {
    source.setFrom("src/main/kotlin", "src/test/kotlin")
    buildUponDefaultConfig = true
    config.setFrom("config/detekt/detekt.yml")
}

tasks.withType<Test> {
    useJUnitPlatform()
    // 透传 Docker 环境给测试 worker（Testcontainers）
    System.getenv("DOCKER_HOST")?.let { environment("DOCKER_HOST", it) }
    System.getenv("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE")?.let {
        environment("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE", it)
    }
    // 告诉 ArchUnit main 类目录（确定性，不依赖 classloader/jar 扫描）
    systemProperty("archunit.main.classes", sourceSets["main"].output.classesDirs.asPath)
}
