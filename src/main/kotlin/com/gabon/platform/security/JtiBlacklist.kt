package com.gabon.platform.security

import org.springframework.dao.DataAccessException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * jti 黑名单:存储故障统一包装为 AuthStoreUnavailableException 上抛 = fail-closed
 * (过滤器/advice 转 503;spec §5.2 定案)。本类所有调用 100% 走 Redis,在此 catch
 * DataAccessException 宽基类是安全的(不可能混入 jOOQ/PG 侧异常),且比逐个枚举
 * Redis 专属子类更稳——Lettuce 命令超时翻译成 QueryTimeoutException,不在专属子类里。
 */
@Component
class JtiBlacklist(
    private val redis: StringRedisTemplate,
) {
    fun revoke(
        jti: String,
        ttl: Duration,
    ) {
        try {
            redis.opsForValue().set(key(jti), "1", ttl)
        } catch (e: DataAccessException) {
            throw AuthStoreUnavailableException(e)
        }
    }

    fun isRevoked(jti: String): Boolean =
        try {
            redis.hasKey(key(jti))
        } catch (e: DataAccessException) {
            throw AuthStoreUnavailableException(e)
        }

    private fun key(jti: String) = "auth:jti:revoked:$jti"
}
