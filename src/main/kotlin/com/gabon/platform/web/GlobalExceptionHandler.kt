package com.gabon.platform.web

import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.RedisConnectionFailureException
import org.springframework.data.redis.RedisSystemException
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
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
     * Valkey 鉴权基础设施不可用 → fail-closed 503(spec §5.2)。
     * **只认 Redis 专属异常**:jOOQ 经 Spring 翻译的 PG 异常同为 DataAccessException,
     * 不得冒充 auth-store 故障——PG 侧异常落兜底 500(spec §6 fail fast;Task 1 质量审查收窄)。
     */
    @ExceptionHandler(RedisConnectionFailureException::class, RedisSystemException::class)
    fun handleAuthStoreDown(e: DataAccessException): ResponseEntity<ProblemDetail> {
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
