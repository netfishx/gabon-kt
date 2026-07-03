package com.gabon.identity.internal

import com.gabon.jooq.tables.references.CUSTOMER
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository

/**
 * C 端账号仓储(spec §5.1):唯一约束落在 canonical/invite_code 列,访问只许发生在 identity.internal
 * (ModuleBoundaryTest 表所有权断言)。所有 jOOQ 条件走 `.eq()` DSL(全局规则)。
 */
@Repository
class CustomerRepository(
    private val dsl: DSLContext,
) {
    data class AuthRow(
        val id: Long,
        val passwordHash: String,
        val active: Boolean,
    )

    data class MeRow(
        val id: Long,
        val username: String,
    )

    /**
     * 插入新客户。冲突语义靠 arbiter index 精确区分(spec §5.1):
     * - `ON CONFLICT (invite_code) DO NOTHING` → invite_code 撞车返回 null,调用方换码重试;
     * - username_canonical 撞车不在 arbiter 内 → PG 抛 unique_violation → jOOQ 译为
     *   DuplicateKeyException,调用方转 409(USERNAME_TAKEN)。
     * 两者由此路径与异常两条通道天然分离,无需读约束名。
     */
    fun insert(
        username: String,
        canonical: String,
        passwordHash: String,
        inviteCode: String,
        invitedBy: Long?,
    ): Long? =
        dsl
            .insertInto(CUSTOMER)
            .set(CUSTOMER.USERNAME, username)
            .set(CUSTOMER.USERNAME_CANONICAL, canonical)
            .set(CUSTOMER.PASSWORD_HASH, passwordHash)
            .set(CUSTOMER.INVITE_CODE, inviteCode)
            .set(CUSTOMER.INVITED_BY, invitedBy)
            .onConflict(CUSTOMER.INVITE_CODE)
            .doNothing()
            .returningResult(CUSTOMER.ID)
            .fetchOne()
            ?.value1()

    /**
     * FOR UPDATE(spec §5.2 串行化契约):登录读凭据与改密读/写凭据对同一行互斥——
     * 无锁时"登录读到旧 hash → 改密完成吊销 → 登录继续签发"的新 family 会逃过
     * revokeAllFor 与 iat-cutoff。锁跨 bcrypt(~100ms)仅串行化同一账号,可接受。
     */
    fun findAuthByCanonical(canonical: String): AuthRow? =
        dsl
            .select(CUSTOMER.ID, CUSTOMER.PASSWORD_HASH, CUSTOMER.STATUS)
            .from(CUSTOMER)
            .where(CUSTOMER.USERNAME_CANONICAL.eq(canonical))
            .forUpdate()
            .fetchOne()
            ?.let { AuthRow(it.value1()!!, it.value2()!!, it.value3() == ACTIVE) }

    /** FOR UPDATE:与 findAuthByCanonical 同一串行化契约(改密侧)。 */
    fun findPasswordHashById(id: Long): String? =
        dsl
            .select(CUSTOMER.PASSWORD_HASH)
            .from(CUSTOMER)
            .where(CUSTOMER.ID.eq(id))
            .forUpdate()
            .fetchOne()
            ?.value1()

    fun findInviterIdByInviteCode(inviteCode: String): Long? =
        dsl
            .select(CUSTOMER.ID)
            .from(CUSTOMER)
            .where(CUSTOMER.INVITE_CODE.eq(inviteCode))
            .fetchOne()
            ?.value1()

    fun findMeById(id: Long): MeRow? =
        dsl
            .select(CUSTOMER.ID, CUSTOMER.USERNAME)
            .from(CUSTOMER)
            .where(CUSTOMER.ID.eq(id))
            .fetchOne()
            ?.let { MeRow(it.value1()!!, it.value2()!!) }

    fun touchLastLogin(id: Long) {
        dsl
            .update(CUSTOMER)
            .set(CUSTOMER.LAST_LOGIN_AT, DSL.currentOffsetDateTime())
            .where(CUSTOMER.ID.eq(id))
            .execute()
    }

    fun updatePasswordHash(
        id: Long,
        passwordHash: String,
    ) {
        dsl
            .update(CUSTOMER)
            .set(CUSTOMER.PASSWORD_HASH, passwordHash)
            .where(CUSTOMER.ID.eq(id))
            .execute()
    }

    companion object {
        /** status 投影:1=active 0=disabled(V2 DDL check (0,1))。 */
        private const val ACTIVE: Short = 1
    }
}
