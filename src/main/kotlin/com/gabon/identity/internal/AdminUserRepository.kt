package com.gabon.identity.internal

import com.gabon.jooq.tables.references.ADMIN_USER
import org.jooq.DSLContext
import org.jooq.Record
import org.springframework.stereotype.Repository

/**
 * admin 账号仓储(spec §5.4):TOTP 密材/CAS step 均属 admin_user,访问只许发生在 identity.internal
 * (ModuleBoundaryTest 表所有权断言)。所有 jOOQ 条件走 `.eq()/.lt()` DSL(全局规则)。
 */
@Repository
class AdminUserRepository(
    private val dsl: DSLContext,
) {
    data class AdminAuthRow(
        val id: Long,
        val username: String,
        val passwordHash: String,
        val active: Boolean,
        val totpEnabled: Boolean,
        val totpSecretEnc: ByteArray?,
        val totpKeyVersion: Short?,
    )

    fun findAuthByCanonical(canonical: String): AdminAuthRow? =
        selectAuth().where(ADMIN_USER.USERNAME_CANONICAL.eq(canonical)).fetchOne()?.let(::toAuthRow)

    fun findById(id: Long): AdminAuthRow? = selectAuth().where(ADMIN_USER.ID.eq(id)).fetchOne()?.let(::toAuthRow)

    /**
     * enroll 落库:仅当尚未启用(`TOTP_ENABLED.eq(false)`)允许写/覆盖未确认 secret;
     * 命中 0 行(账号不存在或已启用)= 非法覆盖,fail fast 暴露。
     */
    fun saveTotpSecret(
        id: Long,
        enc: ByteArray,
        keyVersion: Short,
    ) {
        val rows =
            dsl
                .update(ADMIN_USER)
                .set(ADMIN_USER.TOTP_SECRET_ENC, enc)
                .set(ADMIN_USER.TOTP_KEY_VERSION, keyVersion)
                .where(ADMIN_USER.ID.eq(id).and(ADMIN_USER.TOTP_ENABLED.eq(false)))
                .execute()
        check(rows == 1) { "cannot save totp secret for absent/enabled admin $id" }
    }

    /** confirm 启用:仅 `TOTP_ENABLED.eq(false)` 时置真;返回行数(0 = 已启用,调用方转 VALIDATION)。 */
    fun enableTotp(id: Long): Int =
        dsl
            .update(ADMIN_USER)
            .set(ADMIN_USER.TOTP_ENABLED, true)
            .where(ADMIN_USER.ID.eq(id).and(ADMIN_USER.TOTP_ENABLED.eq(false)))
            .execute()

    /** TOTP 接受的并发语义(spec §5.4):命中 1 行才算验证成功,0 行 = 并发同 code / 重放旧 step。 */
    fun casConsumeStep(
        adminId: Long,
        step: Long,
    ): Boolean =
        dsl
            .update(ADMIN_USER)
            .set(ADMIN_USER.TOTP_LAST_USED_STEP, step)
            .where(
                ADMIN_USER.ID
                    .eq(adminId)
                    .and(ADMIN_USER.TOTP_LAST_USED_STEP.isNull.or(ADMIN_USER.TOTP_LAST_USED_STEP.lt(step))),
            ).execute() == 1

    private fun selectAuth() =
        dsl
            .select(
                ADMIN_USER.ID,
                ADMIN_USER.USERNAME,
                ADMIN_USER.PASSWORD_HASH,
                ADMIN_USER.STATUS,
                ADMIN_USER.TOTP_ENABLED,
                ADMIN_USER.TOTP_SECRET_ENC,
                ADMIN_USER.TOTP_KEY_VERSION,
            ).from(ADMIN_USER)

    private fun toAuthRow(r: Record): AdminAuthRow =
        AdminAuthRow(
            id = r.get(ADMIN_USER.ID)!!,
            username = r.get(ADMIN_USER.USERNAME)!!,
            passwordHash = r.get(ADMIN_USER.PASSWORD_HASH)!!,
            active = r.get(ADMIN_USER.STATUS) == ACTIVE,
            totpEnabled = r.get(ADMIN_USER.TOTP_ENABLED)!!,
            totpSecretEnc = r.get(ADMIN_USER.TOTP_SECRET_ENC),
            totpKeyVersion = r.get(ADMIN_USER.TOTP_KEY_VERSION),
        )

    companion object {
        /** status 投影:1=active 0=disabled(V2 DDL check (0,1))。 */
        private const val ACTIVE: Short = 1
    }
}
