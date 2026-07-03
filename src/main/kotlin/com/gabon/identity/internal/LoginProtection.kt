package com.gabon.identity.internal

import com.gabon.platform.security.AuthStoreUnavailableException
import com.gabon.platform.web.ProblemException
import com.gabon.platform.web.ProblemType
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Component
import java.time.Duration

/** 登录保护参数(spec §5.3):账号锁定 + IP 固定窗口限流,均可配。 */
@ConfigurationProperties("gabon.security.login")
data class LoginProtectionProps(
    val maxFailures: Long = DEFAULT_MAX_FAILURES,
    val lockDuration: Duration = Duration.ofMinutes(DEFAULT_LOCK_MINUTES),
    val ipLimit: Long = DEFAULT_IP_LIMIT,
    val ipWindow: Duration = Duration.ofMinutes(DEFAULT_IP_WINDOW_MINUTES),
) {
    companion object {
        private const val DEFAULT_MAX_FAILURES = 5L
        private const val DEFAULT_LOCK_MINUTES = 15L
        private const val DEFAULT_IP_LIMIT = 30L
        private const val DEFAULT_IP_WINDOW_MINUTES = 10L
    }
}

/**
 * 登录保护(spec §5.3):固定窗口计数,Lua 脚本原子 INCR+PEXPIRE——杜绝"INCR 成功后 PEXPIRE 失败
 * 留下永久 key"导致的永久锁定/限流。所有 redis 调用经 store{} 包装为 AuthStoreUnavailableException
 * = fail-closed(同 JtiBlacklist 模式,覆盖连接失败/命令超时;handler 出 503)。
 * ProblemException(RATE_LIMITED/锁定)在 store 块外抛出,不受包装影响。
 */
@Component
class LoginProtection(
    private val redis: StringRedisTemplate,
    private val props: LoginProtectionProps,
) {
    private val incrWithTtl = DefaultRedisScript(INCR_WITH_TTL, Long::class.java)

    /** IP 固定窗口:先原子自增再判超限;超限抛 429(计数已落,窗口内不再放行)。 */
    fun checkIpLimit(ip: String) {
        val count = store { redis.execute(incrWithTtl, listOf(ipKey(ip)), props.ipWindow.toMillis().toString())!! }
        if (count > props.ipLimit) throw ProblemException(ProblemType.RATE_LIMITED, "ip=$ip count=$count")
    }

    /** 锁定探测:失败计数达阈值即拒(锁定期内正确密码也 401,防枚举与暴破)。 */
    fun assertNotLocked(
        scope: String,
        canonical: String,
    ) {
        val count = store { redis.opsForValue().get(failKey(scope, canonical)) }?.toLong() ?: 0L
        if (count >= props.maxFailures) {
            throw ProblemException(ProblemType.INVALID_CREDENTIALS, "locked scope=$scope user=$canonical")
        }
    }

    /** 失败自增:窗口 = lockDuration,过期即解锁(只前滚,无手动清零)。 */
    fun onFailure(
        scope: String,
        canonical: String,
    ) {
        store { redis.execute(incrWithTtl, listOf(failKey(scope, canonical)), props.lockDuration.toMillis().toString()) }
    }

    /** 成功清零该账号失败计数(IP 窗口不清,固定窗口独立)。 */
    fun onSuccess(
        scope: String,
        canonical: String,
    ) {
        store { redis.delete(failKey(scope, canonical)) }
    }

    private fun <T> store(block: () -> T): T =
        try {
            block()
        } catch (e: DataAccessException) {
            throw AuthStoreUnavailableException(e)
        }

    private fun ipKey(ip: String) = "auth:ip:$ip"

    private fun failKey(
        scope: String,
        canonical: String,
    ) = "auth:fail:$scope:$canonical"

    companion object {
        private val INCR_WITH_TTL =
            """
            local c = redis.call('INCR', KEYS[1])
            if c == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end
            return c
            """.trimIndent()
    }
}
