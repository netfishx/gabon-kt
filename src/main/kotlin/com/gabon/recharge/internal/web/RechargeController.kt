package com.gabon.recharge.internal.web

import com.gabon.platform.security.GabonPrincipal
import com.gabon.recharge.internal.RechargeService
import jakarta.validation.Valid
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** C 端充值端点(spec §4.1):recharge 路由由安全链限 CUSTOMER;回调另走公开 controller(Task 4)。 */
@RestController
@RequestMapping("/v1/recharge")
class RechargeController(
    private val service: RechargeService,
) {
    @GetMapping("/packages")
    fun listPackages(): List<PackageResponse> = service.listPackages().map(PackageResponse::from)

    @PostMapping("/orders")
    fun createOrder(
        @Valid @RequestBody req: CreateOrderRequest,
    ): CreateOrderResponse {
        val result = service.createOrder(currentPrincipal().id, req.packageId, req.channel)
        return CreateOrderResponse(result.orderNo, result.payload)
    }

    @GetMapping("/orders")
    fun listOrders(
        @RequestParam(required = false) cursor: Long?,
    ): OrdersPageResponse {
        val rows = service.listOrders(currentPrincipal().id, cursor)
        return OrdersPageResponse(
            items = rows.map(OrderResponse::from),
            nextCursor = if (rows.size == RechargeService.PAGE_SIZE) rows.last().id else null,
        )
    }

    /** 需票路由:授权链已保证认证存在,直取 principal(同 AuthController 先例)。 */
    private fun currentPrincipal(): GabonPrincipal = SecurityContextHolder.getContext().authentication!!.principal as GabonPrincipal
}
