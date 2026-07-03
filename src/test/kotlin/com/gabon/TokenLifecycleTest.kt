package com.gabon

import com.gabon.identity.internal.TokenPair
import com.gabon.identity.internal.TokenService
import com.gabon.jooq.tables.references.REFRESH_TOKEN
import com.gabon.platform.security.AccessTokenCodec
import com.gabon.platform.security.JtiBlacklist
import com.gabon.platform.security.PrincipalType
import com.gabon.platform.web.ProblemException
import com.gabon.platform.web.ProblemType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/** refresh token 生命周期验收(spec §5.2):原子旋转、重放吊销全 family、logout/revokeAll、明文不落库。 */
class TokenLifecycleTest : AbstractIntegrationTest() {
    @Autowired
    lateinit var tokens: TokenService

    @Autowired
    lateinit var codec: AccessTokenCodec

    @Autowired
    lateinit var blacklist: JtiBlacklist

    @Test
    fun `rotation issues a new pair and replay of the old token revokes the whole family`() {
        val first = tokens.issuePair(PrincipalType.CUSTOMER, 1L, "10.0.0.1", "ua")
        val second = tokens.refresh(first.refreshToken, null, null)

        assertThat(second.refreshToken).isNotEqualTo(first.refreshToken)
        // 新对同 family:sid 延续,logout 才能凭 sid 吊销整条链
        assertThat(codec.verify(second.accessToken)!!.sid).isEqualTo(codec.verify(first.accessToken)!!.sid)

        // 旧 token 再用 = 重放 → 统一 401
        assertRefreshRejected(first.refreshToken)
        // family 全吊销:重放后新对也失效
        assertRefreshRejected(second.refreshToken)
    }

    @Test
    fun `concurrent refreshes of one token let exactly one side win and the replay revokes the fresh pair`() {
        val first = tokens.issuePair(PrincipalType.CUSTOMER, 2L, null, null)
        val outcomes = arrayOfNulls<Result<TokenPair>>(2)
        val start = CountDownLatch(1)
        val t1 =
            thread {
                start.await()
                outcomes[0] = runCatching { tokens.refresh(first.refreshToken, null, null) }
            }
        val t2 =
            thread {
                start.await()
                outcomes[1] = runCatching { tokens.refresh(first.refreshToken, null, null) }
            }
        start.countDown()
        t1.join()
        t2.join()

        val (winners, losers) = outcomes.map { it!! }.partition { it.isSuccess }
        assertThat(winners).hasSize(1) // 恰一方旋转成功
        assertThat(losers).hasSize(1)
        val loserFailure = losers.single().exceptionOrNull()
        assertThat(loserFailure).isInstanceOf(ProblemException::class.java)
        assertThat((loserFailure as ProblemException).type).isEqualTo(ProblemType.INVALID_CREDENTIALS)

        // 重放路径吊销含胜者新对:同 token 并发使用即可疑,胜者的新 refresh 之后也 401
        assertRefreshRejected(winners.single().getOrThrow().refreshToken)
    }

    @Test
    fun `expired refresh token is rejected`() {
        val pair = tokens.issuePair(PrincipalType.CUSTOMER, 3L, null, null)
        dsl.execute("update refresh_token set expires_at = now() - interval '1 hour'")
        assertRefreshRejected(pair.refreshToken)
    }

    @Test
    fun `logout revokes the family and blacklists the jti`() {
        val pair = tokens.issuePair(PrincipalType.CUSTOMER, 4L, null, null)
        val principal = codec.verify(pair.accessToken)!!

        tokens.logout(principal.sid, principal.jti, principal.expiresAt)

        assertRefreshRejected(pair.refreshToken)
        assertThat(blacklist.isRevoked(principal.jti)).isTrue()
    }

    @Test
    fun `revoke all kills every family of the principal`() {
        val p1 = tokens.issuePair(PrincipalType.CUSTOMER, 5L, null, null)
        val p2 = tokens.issuePair(PrincipalType.CUSTOMER, 5L, null, null)
        val principal = codec.verify(p1.accessToken)!!

        tokens.revokeAll(PrincipalType.CUSTOMER, 5L, principal.jti, principal.expiresAt)

        assertRefreshRejected(p1.refreshToken)
        assertRefreshRejected(p2.refreshToken)
        assertThat(blacklist.isRevoked(principal.jti)).isTrue()
    }

    @Test
    fun `plaintext refresh token never reaches the database`() {
        val pair = tokens.issuePair(PrincipalType.ADMIN, 6L, null, null)
        val row =
            dsl
                .select(REFRESH_TOKEN.TOKEN_HASH, REFRESH_TOKEN.EXPIRES_AT)
                .from(REFRESH_TOKEN)
                .fetchOne()!!
        val stored = row.value1()!!

        assertThat(stored).hasSize(32) // SHA-256 摘要
        assertThat(stored).isNotEqualTo(pair.refreshToken.toByteArray())
        assertThat(stored).isEqualTo(MessageDigest.getInstance("SHA-256").digest(pair.refreshToken.toByteArray()))
        // TTL 默认 30 天(gabon.security.refresh.ttl 配置化)
        assertThat(row.value2()).isBetween(OffsetDateTime.now().plusDays(29), OffsetDateTime.now().plusDays(31))
    }

    @Test
    fun `already expired access ticket does not enter the blacklist`() {
        val pair = tokens.issuePair(PrincipalType.CUSTOMER, 7L, null, null)
        val principal = codec.verify(pair.accessToken)!!

        tokens.logout(principal.sid, principal.jti, Instant.now().minusSeconds(60))

        assertThat(blacklist.isRevoked(principal.jti)).isFalse()
        assertRefreshRejected(pair.refreshToken) // 过期票不入黑名单,但 family 照吊
    }

    private fun assertRefreshRejected(rawRefreshToken: String) {
        val ex = assertThrows<ProblemException> { tokens.refresh(rawRefreshToken, null, null) }
        assertThat(ex.type).isEqualTo(ProblemType.INVALID_CREDENTIALS)
    }
}
