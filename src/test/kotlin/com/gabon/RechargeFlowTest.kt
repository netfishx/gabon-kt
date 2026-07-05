package com.gabon

import com.gabon.jooq.tables.references.RECHARGE_PACKAGE
import com.gabon.platform.security.AccessTokenCodec
import com.gabon.platform.security.PrincipalType
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/** recharge 域验收(spec §4):档位、角色门;下单/列表用例随批 2 Task 3 补入。 */
@AutoConfigureMockMvc
class RechargeFlowTest : AbstractIntegrationTest() {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var codec: AccessTokenCodec

    @Test
    fun `active packages are listed by ascending price`() {
        seedPackage(diamonds = 500, priceCents = 4_900)
        seedPackage(diamonds = 100, priceCents = 1_000)
        seedPackage(diamonds = 9_999, priceCents = 99_900, active = false) // 下架不出现
        mockMvc
            .perform(get("/v1/recharge/packages").header(AUTH, "Bearer ${customerToken()}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].priceCents").value(1_000))
            .andExpect(jsonPath("$[1].priceCents").value(4_900))
    }

    @Test
    fun `admin token is rejected on recharge routes`() {
        mockMvc
            .perform(get("/v1/recharge/packages").header(AUTH, "Bearer ${adminToken()}"))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.type").value("/problems/forbidden"))
    }

    @Test
    fun `anonymous is rejected on recharge routes`() {
        mockMvc
            .perform(get("/v1/recharge/packages"))
            .andExpect(status().isUnauthorized)
    }

    private fun customerToken(customerId: Long = 1L): String = codec.issue(customerId, PrincipalType.CUSTOMER, UUID.randomUUID())

    private fun adminToken(): String = codec.issue(2L, PrincipalType.ADMIN, UUID.randomUUID())

    private fun seedPackage(
        diamonds: Long,
        priceCents: Long,
        currency: String = "CNY",
        active: Boolean = true,
    ): Long =
        dsl
            .insertInto(RECHARGE_PACKAGE)
            .set(RECHARGE_PACKAGE.DIAMONDS, diamonds)
            .set(RECHARGE_PACKAGE.PRICE_CENTS, priceCents)
            .set(RECHARGE_PACKAGE.CURRENCY, currency)
            .set(RECHARGE_PACKAGE.STATUS, if (active) PKG_ACTIVE else PKG_INACTIVE) // if 表达式是 Int 型,必须给 Short 常量
            .returningResult(RECHARGE_PACKAGE.ID)
            .fetchOne()!!
            .value1()!!

    companion object {
        const val AUTH = "Authorization"
        private const val PKG_ACTIVE: Short = 1
        private const val PKG_INACTIVE: Short = 0
    }
}
