package com.gabon.platform.web

import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
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

    /** Valkey 等鉴权基础设施不可用 → fail-closed 503(spec §5.2 定案) */
    @ExceptionHandler(DataAccessException::class)
    fun handleStoreDown(e: DataAccessException): ResponseEntity<ProblemDetail> {
        log.error("auth store unavailable", e)
        return ResponseEntity
            .status(ProblemType.AUTH_STORE_UNAVAILABLE.status)
            .body(ProblemType.AUTH_STORE_UNAVAILABLE.toProblemDetail())
    }

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(e: Exception): ResponseEntity<ProblemDetail> {
        log.error("unhandled", e)
        return ResponseEntity.status(ProblemType.INTERNAL.status).body(ProblemType.INTERNAL.toProblemDetail())
    }
}
