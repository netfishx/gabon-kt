package com.gabon.identity.internal.web

import com.gabon.identity.internal.AdminAuthService
import com.gabon.platform.security.GabonPrincipal
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/** admin 登录请求:totpCode 可选(未启用 2FA 时不需);启用后缺失/错误统一 401(服务层判定)。 */
data class AdminLoginRequest(
    @field:NotBlank
    val username: String,
    @field:NotBlank
    val password: String,
    val totpCode: String?,
)

data class TotpConfirmRequest(
    @field:NotBlank
    val code: String,
)

data class TotpEnrollResponse(
    val otpauthUri: String,
)

/**
 * admin 鉴权端点(spec §5.4)。公开:login(IdentityPublicRoutes 声明);
 * 其余 admin 路由默认收敛 ROLE_ADMIN(SecurityConfig)——customer token 访问 → 403 /problems/forbidden。
 * principal 从 SecurityContextHolder 取(JwtAuthFilter 已注入 GabonPrincipal)。
 */
@RestController
@RequestMapping("/v1/admin/auth")
class AdminAuthController(
    private val admin: AdminAuthService,
) {
    @PostMapping("/login")
    fun login(
        @Valid @RequestBody req: AdminLoginRequest,
        request: HttpServletRequest,
    ): TokenPairResponse =
        TokenPairResponse.from(
            admin.login(req.username, req.password, req.totpCode, request.remoteAddr, request.getHeader(USER_AGENT)),
        )

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun logout() {
        admin.logout(currentPrincipal())
    }

    @PostMapping("/totp/enroll")
    fun enroll(): TotpEnrollResponse = TotpEnrollResponse(admin.enroll(currentPrincipal().id))

    @PostMapping("/totp/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun confirm(
        @Valid @RequestBody req: TotpConfirmRequest,
    ) {
        admin.confirm(currentPrincipal().id, req.code)
    }

    /** 需票路由:授权链已保证认证存在,直取 principal(fail fast,认证缺失即 NPE 暴露,不静默降级)。 */
    private fun currentPrincipal(): GabonPrincipal = SecurityContextHolder.getContext().authentication!!.principal as GabonPrincipal

    companion object {
        private const val USER_AGENT = "User-Agent"
    }
}
