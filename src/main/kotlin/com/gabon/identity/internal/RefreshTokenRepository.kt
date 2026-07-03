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

    /**
     * 吊销 family 全部未吊销行,循环至 0 行(spec §5.2):READ COMMITTED 下单遍 UPDATE 的
     * EvalPlanQual 只重查既有行、不重扫等待期间并发旋转提交的新插入行——首遍阻塞在在途
     * 旋转已抢占的行上直至其提交,下一遍以新语句快照收割其新行;此后才起步的旋转在抢占时
     * 即见 revoked_at → 0 行 → 走重放路径。无并发时第二遍即 0 行,恒多一次空 UPDATE。
     */
    fun revokeFamily(familyId: UUID): Int {
        var total = 0
        while (true) {
            val rows =
                dsl
                    .update(REFRESH_TOKEN)
                    .set(REFRESH_TOKEN.REVOKED_AT, DSL.currentOffsetDateTime())
                    .where(REFRESH_TOKEN.FAMILY_ID.eq(familyId).and(REFRESH_TOKEN.REVOKED_AT.isNull))
                    .execute()
            if (rows == 0) return total
            total += rows
        }
    }

    /** 循环语义同 revokeFamily;RETURNING 收集被吊 family 集合,供调用方逐一写 sid 吊销标记。 */
    fun revokeAllFor(
        type: PrincipalType,
        principalId: Long,
    ): Set<UUID> {
        val families = mutableSetOf<UUID>()
        while (true) {
            val revoked =
                dsl
                    .update(REFRESH_TOKEN)
                    .set(REFRESH_TOKEN.REVOKED_AT, DSL.currentOffsetDateTime())
                    .where(
                        REFRESH_TOKEN.PRINCIPAL_TYPE
                            .eq(type.code)
                            .and(REFRESH_TOKEN.PRINCIPAL_ID.eq(principalId))
                            .and(REFRESH_TOKEN.REVOKED_AT.isNull),
                    ).returningResult(REFRESH_TOKEN.FAMILY_ID)
                    .fetch()
            if (revoked.isEmpty()) return families
            revoked.forEach { families.add(it.value1()!!) }
        }
    }
}
