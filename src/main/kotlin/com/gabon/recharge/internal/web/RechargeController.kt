package com.gabon.recharge.internal.web

import com.gabon.recharge.internal.RechargePackageRepository
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** C 端充值端点(spec §4.1):recharge 路由由安全链限 CUSTOMER;回调另走公开 controller(Task 4)。 */
@RestController
@RequestMapping("/v1/recharge")
class RechargeController(
    private val packages: RechargePackageRepository,
) {
    @GetMapping("/packages")
    fun listPackages(): List<PackageResponse> = packages.listActive().map(PackageResponse::from)
}
