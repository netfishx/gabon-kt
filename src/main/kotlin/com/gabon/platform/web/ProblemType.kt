package com.gabon.platform.web

import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import java.net.URI

/**
 * problem 类型注册处(spec §6):type 是稳定 URI reference,enum 名不是对外契约。
 * 鉴权失败一律 INVALID_CREDENTIALS(防枚举);内部细分原因走 ProblemException.internalReason 只进日志。
 */
enum class ProblemType(
    val status: HttpStatus,
    private val uri: String,
    val title: String,
) {
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "/problems/invalid-credentials", "Invalid credentials"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "/problems/unauthenticated", "Authentication required"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "/problems/forbidden", "Access denied"),
    USERNAME_TAKEN(HttpStatus.CONFLICT, "/problems/username-taken", "Username already taken"),
    INSUFFICIENT_BALANCE(HttpStatus.CONFLICT, "/problems/insufficient-balance", "Insufficient balance"),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "/problems/rate-limited", "Too many requests"),
    VALIDATION(HttpStatus.BAD_REQUEST, "/problems/validation", "Invalid request"),
    AUTH_STORE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "/problems/auth-store-unavailable", "Authentication store unavailable"),
    INTERNAL(HttpStatus.INTERNAL_SERVER_ERROR, "/problems/internal", "Internal error"),
    ;

    fun toProblemDetail(detail: String? = null): ProblemDetail =
        ProblemDetail.forStatus(status).also {
            it.type = URI.create(uri)
            it.title = title
            if (detail != null) it.detail = detail
        }
}

/** 业务异常:对外渲染 type 对应 problem,internalReason 只进日志(防枚举)。 */
class ProblemException(
    val type: ProblemType,
    val internalReason: String,
) : RuntimeException(internalReason)
