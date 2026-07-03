package com.gabon.identity.internal

import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.time.Clock

/**
 * 生产 TOTP 验证器(spec §5.4):6 位、窗口 [-1,0,+1]、常量时间比较、CAS 消费 step。
 * 命中窗口内某 step 后必须经 `casConsumeStep` 单调抢占——只有落库那 1 行才算通过(同 code 重放/旧 step 皆拒)。
 */
@Component
class TotpVerifier(
    private val adminRepo: AdminUserRepository,
    private val clock: Clock,
) {
    fun verifyAndConsume(
        adminId: Long,
        secret: ByteArray,
        code: String,
    ): Boolean {
        val nowStep = Totp.stepOf(clock.instant().epochSecond)
        for (delta in -Totp.WINDOW..Totp.WINDOW) {
            val step = nowStep + delta
            val expected = Totp.codeForStep(secret, step, Totp.PROD_DIGITS)
            if (MessageDigest.isEqual(expected.toByteArray(), code.toByteArray())) {
                return adminRepo.casConsumeStep(adminId, step)
            }
        }
        return false
    }
}
