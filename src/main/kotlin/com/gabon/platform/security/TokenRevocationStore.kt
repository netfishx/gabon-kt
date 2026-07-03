package com.gabon.platform.security

import org.springframework.dao.DataAccessException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * 票据吊销存储(spec §5.2):jti 黑名单(单票粒度)+ sid 吊销标记(family 粒度,登出/重放)
 * + principal iat-cutoff(主体粒度,改密);读路径三键一次 MGET,不增 Valkey 往返。
 * 存储故障统一包装为 AuthStoreUnavailableException 上抛 = fail-closed(过滤器/advice 转 503;
 * spec §5.2 定案)。本类所有调用 100% 走 Redis,在此 catch DataAccessException 宽基类是安全的
 * (不可能混入 jOOQ/PG 侧异常),且比逐个枚举 Redis 专属子类更稳——Lettuce 命令超时翻译成
 * QueryTimeoutException,不在专属子类里。
 */
@Component
class TokenRevocationStore(
    private val redis: StringRedisTemplate,
) {
    fun revoke(
        jti: String,
        ttl: Duration,
    ) {
        store { redis.opsForValue().set(jtiKey(jti), "1", ttl) }
    }

    /** family 吊销标记(登出/重放):该 sid 的全部存量 access 立即失效;TTL = access TTL,越过后存量票已自然过期。 */
    fun revokeSid(
        sid: UUID,
        ttl: Duration,
    ) {
        store { redis.opsForValue().set(sidKey(sid), "1", ttl) }
    }

    /** 主体级 iat-cutoff(改密):cutoff 前签发的全部 access 立即失效;TTL 语义同上。 */
    fun revokePrincipal(
        type: PrincipalType,
        principalId: Long,
        cutoff: Instant,
        ttl: Duration,
    ) {
        store { redis.opsForValue().set(principalKey(type, principalId), cutoff.epochSecond.toString(), ttl) }
    }

    /** 三键一次 MGET:jti/sid 命中即吊销;principal 键存在且票 iat < 吊销秒(同秒签发为已接受的残余敞口,spec §5.2)。 */
    fun isRevoked(principal: GabonPrincipal): Boolean =
        store {
            val keys = listOf(jtiKey(principal.jti), sidKey(principal.sid), principalKey(principal.type, principal.id))
            val values = redis.opsForValue().multiGet(keys)!!
            values[0] != null ||
                values[1] != null ||
                values[2]?.let { principal.issuedAt.epochSecond < it.toLong() } == true
        }

    private fun <T> store(block: () -> T): T =
        try {
            block()
        } catch (e: DataAccessException) {
            throw AuthStoreUnavailableException(e)
        }

    private fun jtiKey(jti: String) = "auth:jti:revoked:$jti"

    private fun sidKey(sid: UUID) = "auth:revoke:sid:$sid"

    private fun principalKey(
        type: PrincipalType,
        principalId: Long,
    ) = "auth:revoke:principal:${type.code}:$principalId"
}
