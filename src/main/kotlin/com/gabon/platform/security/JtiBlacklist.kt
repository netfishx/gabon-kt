package com.gabon.platform.security

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration

/** jti 黑名单:Valkey 异常一律上抛 = fail-closed(过滤器转 503;spec §5.2 定案)。 */
@Component
class JtiBlacklist(
    private val redis: StringRedisTemplate,
) {
    fun revoke(
        jti: String,
        ttl: Duration,
    ) {
        redis.opsForValue().set(key(jti), "1", ttl)
    }

    fun isRevoked(jti: String): Boolean = redis.hasKey(key(jti))

    private fun key(jti: String) = "auth:jti:revoked:$jti"
}
