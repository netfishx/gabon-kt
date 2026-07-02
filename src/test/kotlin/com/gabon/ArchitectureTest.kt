package com.gabon

import com.tngtech.archunit.core.domain.JavaMethod
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods
import org.junit.jupiter.api.Test
import org.springframework.transaction.annotation.Transactional
import java.io.File
import java.nio.file.Paths

/** 验收④/边界：ArchUnit 钉死持久层单一路径 + 协程/事务边界 */
class ArchitectureTest {
    // Gradle 通过系统属性传入 main 类目录，确定性导入（需 ArchUnit 1.4+ 才能解析 JDK 25 字节码）
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

    @Test
    fun `no forbidden persistence libraries`() {
        noClasses()
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "org.mybatis..",
                "com.baomidou..", // MyBatis-Plus
                "jakarta.persistence..",
                "javax.persistence..",
                "org.hibernate..",
                "org.springframework.jdbc.core.simple..", // 分散 JdbcClient
            ).check(classes)
    }

    /** 钱核边界：资金包（阻塞 + @Transactional）不得依赖协程库——协程仅编排层（见 B5.1） */
    @Test
    fun `money core packages do not depend on coroutines`() {
        noClasses()
            .that()
            .resideInAnyPackage("..ledger..", "..payment..", "..withdraw..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("kotlinx.coroutines..")
            .check(classes)
    }

    @Test
    fun `feed orchestration layer has no transactional`() {
        noMethods()
            .that()
            .areDeclaredInClassesThat()
            .resideInAPackage("..feed..")
            .should()
            .beAnnotatedWith(Transactional::class.java)
            .check(classes)
    }

    /** 核心边界：@Transactional 不得加在 suspend 函数上（suspend 编译后带 Continuation 参数） */
    @Test
    fun `no transactional suspend functions`() {
        val notSuspend =
            object : ArchCondition<JavaMethod>("not be a suspend function") {
                override fun check(
                    method: JavaMethod,
                    events: ConditionEvents,
                ) {
                    val suspend = method.rawParameterTypes.any { it.name == "kotlin.coroutines.Continuation" }
                    if (suspend) {
                        events.add(SimpleConditionEvent.violated(method, "${method.fullName} 是 @Transactional suspend（禁止）"))
                    }
                }
            }
        methods()
            .that()
            .areAnnotatedWith(Transactional::class.java)
            .should(notSuspend)
            .check(classes)
    }
}
