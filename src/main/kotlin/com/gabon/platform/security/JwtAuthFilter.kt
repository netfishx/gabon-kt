package com.gabon.platform.security

import com.gabon.platform.web.ProblemType
import com.gabon.platform.web.ProblemWriter
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.ObjectMapper

private const val BEARER_PREFIX = "Bearer "

/**
 * bearer → 校验 → jti 黑名单(fail-closed)→ SecurityContext。
 * 无票/无效票不在此拦截,由默认拒绝链的入口点出 401;吊销票任何路由一律 401 短路。
 */
@Component
class JwtAuthFilter(
    private val codec: AccessTokenCodec,
    private val blacklist: JtiBlacklist,
    private val objectMapper: ObjectMapper,
) : OncePerRequestFilter() {
    private enum class Revocation { ACTIVE, REVOKED, STORE_DOWN }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        val token =
            request
                .getHeader("Authorization")
                ?.takeIf { it.startsWith(BEARER_PREFIX) }
                ?.removePrefix(BEARER_PREFIX)
        val principal = token?.let(codec::verify)
        if (principal == null) {
            chain.doFilter(request, response)
            return
        }
        when (checkRevocation(principal.jti)) {
            Revocation.ACTIVE -> {
                val auth =
                    UsernamePasswordAuthenticationToken.authenticated(
                        principal,
                        null,
                        listOf(SimpleGrantedAuthority(principal.type.role)),
                    )
                SecurityContextHolder.getContext().authentication = auth
                chain.doFilter(request, response)
            }
            // 吊销票再现 = 强可疑信号:立即拒,公开路由也不放行
            Revocation.REVOKED -> ProblemWriter.write(response, objectMapper, ProblemType.UNAUTHENTICATED)
            Revocation.STORE_DOWN -> ProblemWriter.write(response, objectMapper, ProblemType.AUTH_STORE_UNAVAILABLE)
        }
    }

    /** fail-closed:黑名单在源头把全部存储故障(含 Lettuce 超时)包装为 AuthStoreUnavailableException。 */
    private fun checkRevocation(jti: String): Revocation =
        try {
            if (blacklist.isRevoked(jti)) Revocation.REVOKED else Revocation.ACTIVE
        } catch (e: AuthStoreUnavailableException) {
            logger.error("jti blacklist unavailable, failing closed", e)
            Revocation.STORE_DOWN
        }
}
