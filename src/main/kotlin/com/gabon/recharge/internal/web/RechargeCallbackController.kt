package com.gabon.recharge.internal.web

import com.gabon.recharge.internal.RechargeService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/**
 * 渠道回调入口(spec §4.3):公开路由(RechargePublicRoutes),raw body 原样透传 SPI 验签
 * (先验后解析);headers key 小写归一(servlet 容器大小写差异,spec §5.2)。
 * 正常返回 = 2xx ack;401/400 由 SPI 抛 ProblemException 经全局 handler 渲染。
 */
@RestController
class RechargeCallbackController(
    private val service: RechargeService,
) {
    @PostMapping("/v1/recharge/callback/{channel}")
    fun callback(
        @PathVariable channel: Short,
        @RequestBody rawBody: ByteArray,
        request: HttpServletRequest,
    ) {
        val headers = request.headerNames.asSequence().associate { it.lowercase() to request.getHeader(it) }
        service.handleCallback(channel, rawBody, headers)
    }
}
