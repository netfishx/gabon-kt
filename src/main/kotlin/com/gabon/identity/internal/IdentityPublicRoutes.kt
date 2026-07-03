package com.gabon.identity.internal

import com.gabon.platform.security.PublicRoutesContributor
import org.springframework.stereotype.Component

/**
 * 身份域公开路由(默认拒绝的唯一放行通道;spec §5.6)。
 * 除这几条外全部需票;admin login 一并在此声明(Task 6 使用),其余 admin 路由默认收敛 ROLE_ADMIN。
 */
@Component
class IdentityPublicRoutes : PublicRoutesContributor {
    override fun publicRoutes(): List<String> =
        listOf(
            "/v1/auth/login",
            "/v1/auth/register",
            "/v1/auth/refresh",
            "/v1/admin/auth/login",
        )
}
