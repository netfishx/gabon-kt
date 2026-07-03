package com.gabon.platform.security

import java.time.Instant
import java.util.UUID

/** 主体类型:code 与 refresh_token.principal_type 对齐(V2 DDL check (1,2))。 */
enum class PrincipalType(
    val code: Short,
    val role: String,
) {
    CUSTOMER(1, "ROLE_CUSTOMER"),
    ADMIN(2, "ROLE_ADMIN"),
    ;

    companion object {
        fun of(code: Short): PrincipalType = entries.first { it.code == code }
    }
}

/** 过滤器解出的已认证主体(sid = refresh family,logout 凭它吊销;expiresAt 供黑名单剩余 TTL;spec §5.2)。 */
data class GabonPrincipal(
    val id: Long,
    val type: PrincipalType,
    val sid: UUID,
    val jti: String,
    val expiresAt: Instant,
)
