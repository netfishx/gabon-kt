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
                "identity",
                "wallet",
                "recharge",
                "withdraw",
                "reward",
                "content",
                "media",
                "moderation",
                "reporting",
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

        /**
         * 规则 6:表所有权(jOOQ 生成表类简单名 → **允许访问该表的包前缀**)。新迁移的表必须在此登记。
         * 值取所属上下文的 internal 包——表访问只能发生在 owner 的 internal 仓储/服务里(spec §4),
         * api 包引用自家表同样违规;platform 是共享内核、无 api/internal 切分,用整包。
         */
        private val TABLE_OWNER: Map<String, String> =
            mapOf(
                "Account" to "com.gabon.wallet.internal",
                "LedgerTxn" to "com.gabon.wallet.internal",
                "LedgerEntry" to "com.gabon.wallet.internal",
                "Outbox" to "com.gabon.platform",
                "Inbox" to "com.gabon.platform",
                "Customer" to "com.gabon.identity.internal",
                "AdminUser" to "com.gabon.identity.internal",
                "RefreshToken" to "com.gabon.identity.internal",
                "RechargePackage" to "com.gabon.recharge.internal",
                "RechargeOrder" to "com.gabon.recharge.internal",
                "WithdrawOrder" to "com.gabon.withdraw.internal",
            )

        private fun pkg(ctx: String) = "com.gabon.$ctx"

        /**
         * 表本体识别:仅 tables/tables.records 两包;`*Kt`(references 顶层属性持有类)与
         * `*Path`(KotlinGenerator 关联路径类)不是表本体,返回 null。
         */
        private fun tableNameOf(target: JavaClass): String? {
            val inTables = target.packageName == "com.gabon.jooq.tables"
            val inRecords = target.packageName == "com.gabon.jooq.tables.records"
            return if ((!inTables && !inRecords) || target.simpleName.endsWith("Kt")) {
                null
            } else {
                target.simpleName.removeSuffix("Record").removeSuffix("Path")
            }
        }
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
     * 规则 6:jOOQ 表访问只许发生在所属上下文的 internal(platform 整包)内;白名单无主的表直接失败。
     * jooq 包豁免只针对包依赖(规则 2/3 不禁),表所有权在此闭环(spec §4"jooq 豁免的边界")。
     * 候选依赖三路收集:类级依赖(字段/签名等)+ 方法调用返回类型 + 字段访问类型——
     * 后两路兜住经 references 顶层属性(字节码 TablesKt.getACCOUNT() 返回 Account)的
     * 裸参数调用形态(如 dsl.fetchCount(ACCOUNT)),单看类级依赖会漏。
     */
    @Test
    fun `jooq table access is limited to the owning module`() {
        val onlyOwnTables =
            object : ArchCondition<JavaClass>("only access jOOQ tables owned by its module") {
                override fun check(
                    clazz: JavaClass,
                    events: ConditionEvents,
                ) {
                    val candidates =
                        clazz.directDependenciesFromSelf.asSequence().map { it.targetClass } +
                            clazz.methodCallsFromSelf.asSequence().map { it.target.rawReturnType } +
                            clazz.fieldAccessesFromSelf.asSequence().map { it.target.rawType }
                    candidates.forEach { target ->
                        val tableName = tableNameOf(target) ?: return@forEach
                        val allowed = TABLE_OWNER[tableName]
                        if (allowed == null) {
                            events.add(
                                SimpleConditionEvent.violated(
                                    clazz,
                                    "jOOQ 表 $tableName 未在 TABLE_OWNER 登记归属——新迁移必须登记(spec §4 规则 6)",
                                ),
                            )
                        } else if (clazz.packageName != allowed && !clazz.packageName.startsWith("$allowed.")) {
                            events.add(
                                SimpleConditionEvent.violated(
                                    clazz,
                                    "${clazz.name} 访问表 $tableName:该表仅允许 $allowed.. 内访问(api 包也不例外,跨上下文走对方 api)",
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

    /**
     * 根包白名单:com.gabon 根包只许启动装配(GabonApplication/GabonApplicationKt);
     * 散落根包的类不落任何上下文格子,是边界规则盲区(WalletProps 教训;第二批终审 Minor #2)。
     */
    @Test
    fun `root package only hosts application bootstrap`() {
        classes()
            .that()
            .resideInAPackage("com.gabon")
            .should()
            .haveSimpleNameStartingWith("GabonApplication")
            .check(classes)
    }

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
}
