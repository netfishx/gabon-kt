package com.gabon.platform.security

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.Date
import java.util.UUID

@Component
class AccessTokenCodec(
    props: JwtProps,
    private val clock: Clock,
) {
    private val key = Keys.hmacShaKeyFor(props.secretBytes())
    private val ttl = props.accessTtl

    fun issue(
        principalId: Long,
        type: PrincipalType,
        sid: UUID,
    ): String {
        val now = clock.instant()
        return Jwts
            .builder()
            .subject(principalId.toString())
            .claim("typ", type.code.toInt())
            .claim("roles", listOf(type.role))
            .claim("sid", sid.toString())
            .id(UuidV7.generate(clock).toString())
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plus(ttl)))
            .signWith(key, Jwts.SIG.HS256)
            .compact()
    }

    /**
     * 校验失败返回 null(过滤器按未认证处理,入口点 401);不抛——无效票不是系统错误。
     * runCatching 兜住全部结构性错误(签名过但 typ 类型不对/缺 sid/未知 PrincipalType 等),
     * 恶意构造的畸形票绝不冒成 500。
     */
    fun verify(token: String): GabonPrincipal? =
        runCatching {
            val claims =
                Jwts
                    .parser()
                    .verifyWith(key)
                    .clock { Date.from(clock.instant()) }
                    .build()
                    .parseSignedClaims(token)
                    .payload
            GabonPrincipal(
                id = claims.subject.toLong(),
                type = PrincipalType.of((claims["typ"] as Number).toShort()),
                sid = UUID.fromString(claims["sid"] as String),
                jti = claims.id,
                expiresAt = claims.expiration.toInstant(),
            )
        }.getOrNull()

    fun accessTtl() = ttl
}
