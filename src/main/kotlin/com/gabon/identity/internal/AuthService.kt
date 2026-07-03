package com.gabon.identity.internal

import com.gabon.platform.security.GabonPrincipal
import com.gabon.platform.security.PrincipalType
import com.gabon.platform.web.ProblemException
import com.gabon.platform.web.ProblemType
import org.springframework.dao.DuplicateKeyException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom

/**
 * C 端鉴权应用服务(spec §5.1/§5.3)。写路径阻塞 `@Transactional`(禁协程,全局规则)。
 *
 * 防枚举核心:所有登录失败(未知用户/密码错/账号禁用/锁定)一律
 * `ProblemException(INVALID_CREDENTIALS, 内部原因)`——对外渲染同一 401 body,内部原因只进日志。
 */
@Service
class AuthService(
    private val customers: CustomerRepository,
    private val tokens: TokenService,
    private val passwordEncoder: PasswordEncoder,
    private val protection: LoginProtection,
) {
    private val random = SecureRandom()

    @Transactional
    fun register(
        username: String,
        password: String,
        inviteCode: String?,
        ip: String?,
        userAgent: String?,
    ): TokenPair {
        val canonical = UsernameCanonicalizer.canonicalize(username)
        val invitedBy =
            inviteCode?.let {
                customers.findInviterIdByInviteCode(it)
                    ?: throw ProblemException(ProblemType.VALIDATION, "invalid invite code: $it")
            }
        val passwordHash = requireNotNull(passwordEncoder.encode(password)) { "password encoder returned null" }
        val id = insertWithInviteRetry(username, canonical, passwordHash, invitedBy)
        return tokens.issuePair(PrincipalType.CUSTOMER, id, ip, userAgent)
    }

    @Transactional
    fun login(
        username: String,
        password: String,
        ip: String,
        userAgent: String?,
    ): TokenPair {
        val canonical = UsernameCanonicalizer.canonicalize(username)
        protection.checkIpLimit(ip)
        protection.assertNotLocked(SCOPE_CUSTOMER, canonical)
        val auth = customers.findAuthByCanonical(canonical) ?: rejectLogin(canonical, "unknown user", countFailure = true)
        if (!passwordEncoder.matches(password, auth.passwordHash)) rejectLogin(canonical, "bad password", countFailure = true)
        // 账号禁用不计失败计数(密码可能正确,封禁是人工态,不因此累加锁定)——仍走统一 401
        if (!auth.active) rejectLogin(canonical, "disabled", countFailure = false)
        protection.onSuccess(SCOPE_CUSTOMER, canonical)
        customers.touchLastLogin(auth.id)
        return tokens.issuePair(PrincipalType.CUSTOMER, auth.id, ip, userAgent)
    }

    /** 统一 401 出口:防枚举,所有失败原因渲染同一 body,内部原因只进日志(单一 throw 点)。 */
    private fun rejectLogin(
        canonical: String,
        reason: String,
        countFailure: Boolean,
    ): Nothing {
        if (countFailure) protection.onFailure(SCOPE_CUSTOMER, canonical)
        throw ProblemException(ProblemType.INVALID_CREDENTIALS, "$reason: $canonical")
    }

    fun refresh(
        rawRefreshToken: String,
        ip: String?,
        userAgent: String?,
    ): TokenPair = tokens.refresh(rawRefreshToken, ip, userAgent)

    fun logout(principal: GabonPrincipal) {
        tokens.logout(principal.sid, principal.jti, principal.expiresAt)
    }

    fun me(principalId: Long): CustomerRepository.MeRow =
        customers.findMeById(principalId)
            ?: throw ProblemException(ProblemType.INVALID_CREDENTIALS, "customer gone: $principalId")

    /** 改密:验旧→改 hash→吊销全部 session 并黑名单当前 jti(其它 session 与本 access 全失效)。 */
    @Transactional
    fun changePassword(
        principal: GabonPrincipal,
        currentPassword: String,
        newPassword: String,
    ) {
        val hash =
            customers.findPasswordHashById(principal.id)
                ?: throw ProblemException(ProblemType.INVALID_CREDENTIALS, "customer gone: ${principal.id}")
        if (!passwordEncoder.matches(currentPassword, hash)) {
            throw ProblemException(ProblemType.INVALID_CREDENTIALS, "bad current password: ${principal.id}")
        }
        val newHash = requireNotNull(passwordEncoder.encode(newPassword)) { "password encoder returned null" }
        customers.updatePasswordHash(principal.id, newHash)
        tokens.revokeAll(PrincipalType.CUSTOMER, principal.id, principal.jti, principal.expiresAt)
    }

    /**
     * invite_code 撞车(insert 返回 null)换码重试;canonical 撞车经 DuplicateKeyException → 409。
     * 重试上限 fail fast:32^10 空间下耗尽即系统级异常,不静默吞掉。
     */
    private fun insertWithInviteRetry(
        username: String,
        canonical: String,
        passwordHash: String,
        invitedBy: Long?,
    ): Long {
        repeat(MAX_INVITE_RETRIES) {
            val id =
                try {
                    customers.insert(username, canonical, passwordHash, generateInviteCode(), invitedBy)
                } catch (e: DuplicateKeyException) {
                    throw ProblemException(ProblemType.USERNAME_TAKEN, "canonical taken: $canonical ($e)")
                }
            if (id != null) return id
        }
        throw IllegalStateException("invite code generation exhausted $MAX_INVITE_RETRIES retries")
    }

    private fun generateInviteCode(): String =
        (1..INVITE_LEN).map { INVITE_ALPHABET[random.nextInt(INVITE_ALPHABET.length)] }.joinToString("")

    companion object {
        private const val SCOPE_CUSTOMER = "customer"

        /** 邀请码字母表 A-Z2-7(去易混 0/1/8/9),长度 10(spec §5.1)。 */
        private const val INVITE_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        private const val INVITE_LEN = 10
        private const val MAX_INVITE_RETRIES = 5
    }
}
