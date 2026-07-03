package com.gabon.identity.internal

import com.gabon.jooq.tables.references.REFRESH_TOKEN
import com.gabon.platform.security.PrincipalType
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

/** claimForRotation 命中行的归属信息:旋转成功后据此在同 family 签发新对。 */
data class RotatedRow(
    val familyId: UUID,
    val principalType: PrincipalType,
    val principalId: Long,
)

@Repository
class RefreshTokenRepository(
    private val dsl: DSLContext,
) {
    /** 7 个入参均为 refresh_token 自然列,拆 DTO 徒增一层映射(签名形态为规格固定)。 */
    @Suppress("LongParameterList")
    fun insert(
        familyId: UUID,
        type: PrincipalType,
        principalId: Long,
        tokenHash: ByteArray,
        expiresAt: OffsetDateTime,
        ip: String?,
        userAgent: String?,
    ) {
        dsl
            .insertInto(REFRESH_TOKEN)
            .set(REFRESH_TOKEN.FAMILY_ID, familyId)
            .set(REFRESH_TOKEN.PRINCIPAL_TYPE, type.code)
            .set(REFRESH_TOKEN.PRINCIPAL_ID, principalId)
            .set(REFRESH_TOKEN.TOKEN_HASH, tokenHash)
            .set(REFRESH_TOKEN.EXPIRES_AT, expiresAt)
            .set(REFRESH_TOKEN.CREATED_IP, ip)
            .set(REFRESH_TOKEN.CREATED_USER_AGENT, userAgent)
            .execute()
    }

    /** 原子旋转抢占(spec §5.2):命中 1 行才算成功;全程 DB 时钟。 */
    fun claimForRotation(tokenHash: ByteArray): RotatedRow? =
        dsl
            .update(REFRESH_TOKEN)
            .set(REFRESH_TOKEN.ROTATED_AT, DSL.currentOffsetDateTime())
            .set(REFRESH_TOKEN.LAST_USED_AT, DSL.currentOffsetDateTime())
            .where(
                REFRESH_TOKEN.TOKEN_HASH
                    .eq(tokenHash)
                    .and(REFRESH_TOKEN.ROTATED_AT.isNull)
                    .and(REFRESH_TOKEN.REVOKED_AT.isNull)
                    .and(REFRESH_TOKEN.EXPIRES_AT.gt(DSL.currentOffsetDateTime())),
            ).returningResult(REFRESH_TOKEN.FAMILY_ID, REFRESH_TOKEN.PRINCIPAL_TYPE, REFRESH_TOKEN.PRINCIPAL_ID)
            .fetchOne()
            ?.let { RotatedRow(it.value1()!!, PrincipalType.of(it.value2()!!), it.value3()!!) }

    fun familyOf(tokenHash: ByteArray): UUID? =
        dsl
            .select(REFRESH_TOKEN.FAMILY_ID)
            .from(REFRESH_TOKEN)
            .where(REFRESH_TOKEN.TOKEN_HASH.eq(tokenHash))
            .fetchOne()
            ?.value1()

    fun revokeFamily(familyId: UUID): Int =
        dsl
            .update(REFRESH_TOKEN)
            .set(REFRESH_TOKEN.REVOKED_AT, DSL.currentOffsetDateTime())
            .where(REFRESH_TOKEN.FAMILY_ID.eq(familyId).and(REFRESH_TOKEN.REVOKED_AT.isNull))
            .execute()

    fun revokeAllFor(
        type: PrincipalType,
        principalId: Long,
    ): Int =
        dsl
            .update(REFRESH_TOKEN)
            .set(REFRESH_TOKEN.REVOKED_AT, DSL.currentOffsetDateTime())
            .where(
                REFRESH_TOKEN.PRINCIPAL_TYPE
                    .eq(type.code)
                    .and(REFRESH_TOKEN.PRINCIPAL_ID.eq(principalId))
                    .and(REFRESH_TOKEN.REVOKED_AT.isNull),
            ).execute()
}
