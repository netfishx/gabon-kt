package com.gabon.platform.security

/** 各上下文声明公开路由(默认拒绝的唯一放行通道;spec §5.6)。 */
fun interface PublicRoutesContributor {
    fun publicRoutes(): List<String>
}
