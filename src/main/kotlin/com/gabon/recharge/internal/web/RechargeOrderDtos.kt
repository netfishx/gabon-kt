package com.gabon.recharge.internal.web

import com.gabon.recharge.internal.RechargeOrderRepository
import jakarta.validation.constraints.Positive

data class CreateOrderRequest(
    @field:Positive
    val packageId: Long,
    @field:Positive
    val channel: Short,
)

data class CreateOrderResponse(
    val orderNo: String,
    val payload: Map<String, String>,
)

data class OrderResponse(
    val orderNo: String,
    val diamonds: Long,
    val priceCents: Long,
    val currency: String,
    val status: Short,
) {
    companion object {
        fun from(row: RechargeOrderRepository.OrderRow): OrderResponse =
            OrderResponse(row.orderNo, row.diamonds, row.priceCents, row.currency, row.status)
    }
}

data class OrdersPageResponse(
    val items: List<OrderResponse>,
    val nextCursor: Long?,
)
