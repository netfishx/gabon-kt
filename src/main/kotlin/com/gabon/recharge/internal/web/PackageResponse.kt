package com.gabon.recharge.internal.web

import com.gabon.recharge.internal.RechargePackageRepository

data class PackageResponse(
    val id: Long,
    val diamonds: Long,
    val priceCents: Long,
    val currency: String,
) {
    companion object {
        fun from(row: RechargePackageRepository.PackageRow): PackageResponse =
            PackageResponse(row.id, row.diamonds, row.priceCents, row.currency)
    }
}
