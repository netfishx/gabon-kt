package com.gabon.identity.internal

import com.gabon.platform.security.AccessTokenCodec
import com.gabon.platform.security.JtiBlacklist
import com.gabon.platform.security.PrincipalType
import com.gabon.platform.web.ProblemException
import com.gabon.platform.web.ProblemType
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID

/** refresh token TTL(spec §5.2:默认 30 天,配置键 gabon.security.refresh.ttl)。 */
@ConfigurationProperties("gabon.security.refresh")
data class RefreshProps(
    val ttl: Duration = Duration.ofDays(DEFAULT_TTL_DAYS),
) {
    companion object {
        private const val DEFAULT_TTL_DAYS = 30L
    }
}

/** 签发结果:refresh 明文只存在于此返回值,库中仅 SHA-256。 */
data class TokenPair(
    val accessToken: String,
    val refreshToken: String,
    val expiresInSeconds: Long,
)

/** 阻塞事务边界:旋转+签发同事务;协程禁入(全局规则)。 */
@Service
class TokenService(
    private val repo: RefreshTokenRepository,
    private val codec: AccessTokenCodec,
    private val blacklist: JtiBlacklist,
    private val props: RefreshProps,
    private val clock: Clock,
) {
    private val random = SecureRandom()

    @Transactional
    fun issuePair(
        type: PrincipalType,
        principalId: Long,
        ip: String?,
        userAgent: String?,
    ): TokenPair {
        val family = UUID.randomUUID()
        return issueInFamily(family, type, principalId, ip, userAgent)
    }

    /**
     * 旋转:原子抢占命中 1 行才发新对;0 行且 hash 存在 = 重放 → 吊销整个 family(spec §5.2)。
     * noRollbackFor 是安全语义的必要条件:重放路径先 revokeFamily 再抛 401,吊销必须随事务
     * 提交——默认回滚规则会把吊销一并回滚,family 逃生(并发测试钉死此语义)。
     */
    @Transactional(noRollbackFor = [ProblemException::class])
    fun refresh(
        rawRefreshToken: String,
        ip: String?,
        userAgent: String?,
    ): TokenPair {
        val hash = sha256(rawRefreshToken)
        val row = repo.claimForRotation(hash)
        if (row == null) {
            val family = repo.familyOf(hash)
            if (family != null) {
                repo.revokeFamily(family) // 重放:已旋转/已吊销/已过期的 token 再现 → 全 family 吊销
                throw ProblemException(ProblemType.INVALID_CREDENTIALS, "refresh replay, family=$family revoked")
            }
            throw ProblemException(ProblemType.INVALID_CREDENTIALS, "unknown refresh token")
        }
        return issueInFamily(row.familyId, row.principalType, row.principalId, ip, userAgent)
    }

    fun logout(
        sid: UUID,
        jti: String,
        expiresAt: Instant,
    ) {
        repo.revokeFamily(sid)
        blacklistRemaining(jti, expiresAt)
    }

    fun revokeAll(
        type: PrincipalType,
        principalId: Long,
        currentJti: String,
        expiresAt: Instant,
    ) {
        repo.revokeAllFor(type, principalId)
        blacklistRemaining(currentJti, expiresAt)
    }

    /** 黑名单 TTL = 票的剩余有效期(spec §5.2 原文,非完整 access TTL);已过期不入。 */
    private fun blacklistRemaining(
        jti: String,
        expiresAt: Instant,
    ) {
        val remaining = Duration.between(clock.instant(), expiresAt)
        if (!remaining.isNegative && !remaining.isZero) blacklist.revoke(jti, remaining)
    }

    private fun issueInFamily(
        family: UUID,
        type: PrincipalType,
        principalId: Long,
        ip: String?,
        userAgent: String?,
    ): TokenPair {
        val raw = ByteArray(RAW_BYTES).also { random.nextBytes(it) }
        val rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
        val expiresAt = OffsetDateTime.ofInstant(clock.instant().plus(props.ttl), ZoneOffset.UTC)
        repo.insert(family, type, principalId, sha256(rawToken), expiresAt, ip, userAgent)
        val access = codec.issue(principalId, type, family)
        return TokenPair(access, rawToken, codec.accessTtl().seconds)
    }

    private fun sha256(raw: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())

    companion object {
        private const val RAW_BYTES = 32 // 256-bit 随机(spec §5.2)
    }
}
