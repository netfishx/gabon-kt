package com.gabon.identity.internal.web

import com.gabon.identity.internal.AuthService
import com.gabon.platform.security.GabonPrincipal
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * C 端鉴权端点(spec §5.1)。公开:login/register/refresh(IdentityPublicRoutes 声明);
 * 需票:me/logout/password——principal 从 SecurityContextHolder 取(JwtAuthFilter 已注入 GabonPrincipal)。
 * 客户端 IP 取 request.remoteAddr(代理头解析是部署层事,一期不做)。
 */
@RestController
@RequestMapping("/v1/auth")
class AuthController(
    private val auth: AuthService,
) {
    @PostMapping("/register")
    fun register(
        @Valid @RequestBody req: RegisterRequest,
        request: HttpServletRequest,
    ): TokenPairResponse =
        TokenPairResponse.from(
            auth.register(req.username, req.password, req.inviteCode, request.remoteAddr, request.getHeader(USER_AGENT)),
        )

    @PostMapping("/login")
    fun login(
        @Valid @RequestBody req: LoginRequest,
        request: HttpServletRequest,
    ): TokenPairResponse =
        TokenPairResponse.from(
            auth.login(req.username, req.password, request.remoteAddr, request.getHeader(USER_AGENT)),
        )

    @PostMapping("/refresh")
    fun refresh(
        @Valid @RequestBody req: RefreshRequest,
        request: HttpServletRequest,
    ): TokenPairResponse =
        TokenPairResponse.from(
            auth.refresh(req.refreshToken, request.remoteAddr, request.getHeader(USER_AGENT)),
        )

    @GetMapping("/me")
    fun me(): MeResponse {
        val principal = currentPrincipal()
        val me = auth.me(principal.id)
        return MeResponse(me.id, me.username)
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun logout() {
        auth.logout(currentPrincipal())
    }

    @PostMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun changePassword(
        @Valid @RequestBody req: ChangePasswordRequest,
    ) {
        auth.changePassword(currentPrincipal(), req.currentPassword, req.newPassword)
    }

    /** 需票路由:授权链已保证认证存在,直取 principal(fail fast,认证缺失即 NPE 暴露,不静默降级)。 */
    private fun currentPrincipal(): GabonPrincipal = SecurityContextHolder.getContext().authentication!!.principal as GabonPrincipal

    companion object {
        private const val USER_AGENT = "User-Agent"
    }
}
