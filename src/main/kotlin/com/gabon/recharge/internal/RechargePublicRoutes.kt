package com.gabon.recharge.internal

import com.gabon.platform.security.PublicRoutesContributor
import org.springframework.stereotype.Component

/** 渠道回调公开路由(spec §7.2-1,精确模式):渠道服务器不带 JWT,安全靠 SPI 验签。 */
@Component
class RechargePublicRoutes : PublicRoutesContributor {
    override fun publicRoutes(): List<String> = listOf("/v1/recharge/callback/*")
}
