package com.gabon.platform.security

/**
 * 鉴权存储(Valkey)不可用:TokenRevocationStore 在源头把一切 DataAccessException(连接失败/命令超时/
 * 系统异常)包装为本异常;消费方(过滤器/advice)据此 fail-closed 出 503,PG 侧异常不可能流入。
 */
class AuthStoreUnavailableException(
    cause: Throwable,
) : RuntimeException("auth store unavailable", cause)
