package com.gabon.recharge.internal.channel

import com.gabon.platform.web.ProblemException
import com.gabon.platform.web.ProblemType
import org.springframework.stereotype.Component

/** 渠道注册表(spec §5.1):Spring 注入全部 PaymentChannel;code 重复启动 fail-fast(后注册静默覆盖是危险态)。 */
@Component
class PaymentChannelRegistry(
    channels: List<PaymentChannel>,
) {
    private val byCode: Map<Short, PaymentChannel> =
        channels.groupBy { it.code }.mapValues { (code, list) ->
            check(list.size == 1) { "duplicate payment channel code $code: ${list.map { c -> c::class.simpleName }}" }
            list.single()
        }

    /** 未知 channel = 边界输入错误 → 400(spec §5.1)。 */
    fun byCode(code: Short): PaymentChannel =
        byCode[code] ?: throw ProblemException(ProblemType.VALIDATION, "unknown payment channel: $code")
}
