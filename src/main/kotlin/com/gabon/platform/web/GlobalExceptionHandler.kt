package com.gabon.platform.web

import com.gabon.platform.security.AuthStoreUnavailableException
import org.slf4j.LoggerFactory
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.ErrorResponse
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class GlobalExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(ProblemException::class)
    fun handleProblem(e: ProblemException): ResponseEntity<ProblemDetail> {
        log.info("problem={} reason={}", e.type.name, e.internalReason)
        return ResponseEntity.status(e.type.status).body(e.type.toProblemDetail())
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(): ResponseEntity<ProblemDetail> =
        ResponseEntity.status(ProblemType.VALIDATION.status).body(ProblemType.VALIDATION.toProblemDetail())

    /**
     * 请求体不可读(缺字段/类型不符/坏 JSON,含 Jackson 3 FAIL_ON_NULL_FOR_PRIMITIVES 对非空
     * 数值字段的反序列化失败):客户端输入错误 → 400,不得落 500 兜底污染错误告警。
     * 本仓 advice 的 Exception 兜底会抢在框架默认 400 映射之前,故必须显式收口。
     */
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadable(e: HttpMessageNotReadableException): ResponseEntity<ProblemDetail> {
        log.info("unreadable request body: {}", e.message)
        return ResponseEntity.status(ProblemType.VALIDATION.status).body(ProblemType.VALIDATION.toProblemDetail())
    }

    /**
     * Valkey 鉴权基础设施不可用 → fail-closed 503(spec §5.2)。
     * 包装发生在 Redis 专属组件(TokenRevocationStore 等)内部:连接失败/命令超时/系统异常全覆盖,
     * jOOQ/PG 侧异常不可能流入包装,不会冒充 auth-store 故障——PG 侧落兜底 500(spec §6 fail fast)。
     */
    @ExceptionHandler(AuthStoreUnavailableException::class)
    fun handleAuthStoreDown(e: AuthStoreUnavailableException): ResponseEntity<ProblemDetail> {
        log.error("auth store unavailable", e)
        return ResponseEntity
            .status(ProblemType.AUTH_STORE_UNAVAILABLE.status)
            .body(ProblemType.AUTH_STORE_UNAVAILABLE.toProblemDetail())
    }

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(e: Exception): ResponseEntity<ProblemDetail> {
        if (e is ErrorResponse) {
            // 框架级 HTTP 语义异常(NoResourceFound 404 / 405 / 415 …,Framework 6.1+ 均实现
            // ErrorResponse):保留其自带状态与 problem body,不得被兜底冒成 500
            return ResponseEntity.status(e.statusCode).body(e.body)
        }
        log.error("unhandled", e)
        return ResponseEntity.status(ProblemType.INTERNAL.status).body(ProblemType.INTERNAL.toProblemDetail())
    }
}
