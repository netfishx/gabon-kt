package com.gabon.identity.internal

import com.gabon.platform.security.GabonPrincipal
import com.gabon.platform.security.PrincipalType
import com.gabon.platform.web.ProblemException
import com.gabon.platform.web.ProblemType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom

/**
 * admin 鉴权应用服务(spec §5.4)。写路径阻塞 `@Transactional`(禁协程,全局规则)。
 *
 * 强 2FA:一旦 `totp_enabled`,登录必带正确 TOTP;缺失/错误与密码错、账号禁用同走统一 401
 * `ProblemException(INVALID_CREDENTIALS)` 防枚举;TOTP 失败计入锁定计数(scope="admin")。
 */
@Service
class AdminAuthService(
    private val admins: AdminUserRepository,
    private val tokens: TokenService,
    private val crypto: TotpSecretCrypto,
    private val verifier: TotpVerifier,
    private val passwordEncoder: PasswordEncoder,
    private val protection: LoginProtection,
) {
    private val random = SecureRandom()

    /** 计时对齐用假 hash(与线上同一 encoder,cost 一致);未知账号路径拿它跑一次 matches 抵消 bcrypt 耗时差。 */
    private val dummyHash = requireNotNull(passwordEncoder.encode(DUMMY_PASSWORD)) { "password encoder returned null" }

    @Transactional
    fun login(
        username: String,
        password: String,
        totpCode: String?,
        ip: String,
        userAgent: String?,
    ): TokenPair {
        val canonical = UsernameCanonicalizer.canonicalize(username)
        protection.checkIpLimit(ip)
        protection.assertNotLocked(SCOPE_ADMIN, canonical)
        val auth = admins.findAuthByCanonical(canonical)
        if (auth == null) {
            // 等功耗:未知账号也付一次 bcrypt 代价,消除"跳过 matches"暴露的计时侧信道(结果丢弃)。
            passwordEncoder.matches(password, dummyHash)
            reject(canonical, "unknown admin", countFailure = true)
        }
        if (!passwordEncoder.matches(password, auth.passwordHash)) reject(canonical, "bad password", countFailure = true)
        // 账号禁用不计失败计数(封禁是人工态,不因此累加锁定)——仍走统一 401
        if (!auth.active) reject(canonical, "disabled", countFailure = false)
        if (auth.totpEnabled) verifyTotp(auth, totpCode, canonical)
        protection.onSuccess(SCOPE_ADMIN, canonical)
        return tokens.issuePair(PrincipalType.ADMIN, auth.id, ip, userAgent)
    }

    /** enroll(需 ADMIN token):生成 20B secret → 加密落库(enabled 保持 false,可重复覆盖未确认 secret)→ 返回 otpauth URI。 */
    @Transactional
    fun enroll(adminId: Long): String {
        val admin =
            admins.findById(adminId)
                ?: throw ProblemException(ProblemType.INVALID_CREDENTIALS, "admin gone: $adminId")
        val secret = ByteArray(SECRET_BYTES).also { random.nextBytes(it) }
        admins.saveTotpSecret(adminId, crypto.encrypt(adminId, secret), crypto.keyVersion)
        return otpauthUri(admin.username, secret)
    }

    /**
     * confirm:解密未确认 secret → 窗口 ±1 验证 + CAS 消费 step → `totp_enabled=true`。
     * enable 用 confirm 验证过的**同一份密文**做指纹守卫(0 行 = 已启用 / 在途被覆盖 → VALIDATION),
     * 杜绝"启用被并发 enroll 覆盖后的新 secret"致 authenticator 锁死。
     */
    @Transactional
    fun confirm(
        adminId: Long,
        code: String,
    ) {
        val pending = pendingSecret(adminId)
        if (!verifier.verifyAndConsume(adminId, pending.secret, code)) {
            throw ProblemException(ProblemType.INVALID_CREDENTIALS, "totp confirm failed: $adminId")
        }
        if (admins.enableTotp(adminId, pending.enc) != 1) {
            throw ProblemException(ProblemType.VALIDATION, "totp already enabled or secret changed: $adminId")
        }
    }

    /** 待确认密材:enc 密文(供 enable 指纹守卫)+ 解密明文(供窗口验证)同批返回,二者必须同源同事务读取。 */
    private data class PendingSecret(
        val enc: ByteArray,
        val secret: ByteArray,
    )

    /** 取回待确认密材:账号不存在 → 401;无待确认密材 → VALIDATION;解密失败上抛 fail fast。 */
    private fun pendingSecret(adminId: Long): PendingSecret {
        val admin =
            admins.findById(adminId)
                ?: throw ProblemException(ProblemType.INVALID_CREDENTIALS, "admin gone: $adminId")
        val enc = admin.totpSecretEnc
        val version = admin.totpKeyVersion
        if (enc == null || version == null) {
            throw ProblemException(ProblemType.VALIDATION, "no pending totp secret: $adminId")
        }
        return PendingSecret(enc, crypto.decrypt(adminId, version, enc))
    }

    fun logout(principal: GabonPrincipal) {
        tokens.logout(principal.sid, principal.jti, principal.expiresAt)
    }

    private fun verifyTotp(
        auth: AdminUserRepository.AdminAuthRow,
        totpCode: String?,
        canonical: String,
    ) {
        if (totpCode.isNullOrBlank()) reject(canonical, "totp missing", countFailure = true)
        // 启用态必有密材(V2 DDL check 保证),解密失败(AAD 错/密文损坏)不该静默 → 上抛 fail fast
        val secret = crypto.decrypt(auth.id, auth.totpKeyVersion!!, auth.totpSecretEnc!!)
        if (!verifier.verifyAndConsume(auth.id, secret, totpCode)) reject(canonical, "bad totp", countFailure = true)
    }

    /** 统一 401 出口:防枚举,所有失败原因渲染同一 body,内部原因只进日志(单一 throw 点)。 */
    private fun reject(
        canonical: String,
        reason: String,
        countFailure: Boolean,
    ): Nothing {
        if (countFailure) protection.onFailure(SCOPE_ADMIN, canonical)
        throw ProblemException(ProblemType.INVALID_CREDENTIALS, "$reason: $canonical")
    }

    /**
     * otpauth URI(计划注记 9):label percent-encoded(URLEncoder UTF-8 且 `+`→`%20`),secret Base32 去 padding。
     * 含空格/冒号/@ 的用户名也生成合法 URI(query 分隔符外无裸空格,secret 无 padding `=`)。
     */
    private fun otpauthUri(
        username: String,
        secret: ByteArray,
    ): String {
        val secretB32 = Base32.encode(secret).trimEnd('=')
        val label = URLEncoder.encode("$LABEL_PREFIX:$username", StandardCharsets.UTF_8).replace("+", "%20")
        return "otpauth://totp/$label?secret=$secretB32&issuer=$ISSUER" +
            "&algorithm=SHA1&digits=${Totp.PROD_DIGITS}&period=${Totp.STEP_SECONDS}"
    }

    companion object {
        private const val SCOPE_ADMIN = "admin"

        /** 计时对齐假密码,非真实凭据,仅用于生成 dummyHash。 */
        private const val DUMMY_PASSWORD = "timing-equalizer-not-a-real-password"

        /** RFC 6238 secret 长度:20 字节(160-bit,与 HMAC-SHA1 块对齐)。 */
        private const val SECRET_BYTES = 20
        private const val LABEL_PREFIX = "gabon-admin"
        private const val ISSUER = "gabon"
    }
}
