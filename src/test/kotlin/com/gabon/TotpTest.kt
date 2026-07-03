package com.gabon

import com.gabon.identity.internal.Base32
import com.gabon.identity.internal.Totp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** RFC 6238 Appendix B 官方向量(HMAC-SHA1, 8 位);验证算法本身正确,生产 verifier 固定 6 位(spec §5.4)。 */
class TotpTest {
    private val seed = "12345678901234567890".toByteArray()
    private val vectorDigits = 8

    /** epochSecond → 期望 8 位 TOTP 值(RFC 6238 Appendix B, SHA1 行) */
    private val rfcVectors =
        listOf(
            59L to "94287082",
            1_111_111_109L to "07081804",
            1_111_111_111L to "14050471",
            1_234_567_890L to "89005924",
            2_000_000_000L to "69279037",
            20_000_000_000L to "65353130",
        )

    @Test
    fun `rfc 6238 appendix b vectors pass at 8 digits`() {
        rfcVectors.forEach { (epochSecond, expected) ->
            val step = Totp.stepOf(epochSecond)
            val code = Totp.codeForStep(seed, step, vectorDigits)
            assertThat(code)
                .withFailMessage("epochSecond=$epochSecond step=$step expected=$expected actual=$code")
                .isEqualTo(expected)
        }
    }

    @Test
    fun `stepOf floors to the 30 second window`() {
        assertThat(Totp.stepOf(59L)).isEqualTo(1L)
        assertThat(Totp.stepOf(60L)).isEqualTo(2L)
        assertThat(Totp.stepOf(0L)).isEqualTo(0L)
        assertThat(Totp.stepOf(29L)).isEqualTo(0L)
        assertThat(Totp.stepOf(30L)).isEqualTo(1L)
    }

    @Test
    fun `production digits yields 6 characters with leading zeros preserved`() {
        // step=30(该 seed 下)截断值 26920 < 100000，若无 padStart 会输出 5 位——实测钉死该场景。
        assertThat(Totp.codeForStep(seed, 30L, Totp.PROD_DIGITS)).isEqualTo("026920")

        repeat(20) { step ->
            val code = Totp.codeForStep(seed, step.toLong(), Totp.PROD_DIGITS)
            assertThat(code).hasSize(Totp.PROD_DIGITS)
            assertThat(code).matches("\\d{6}")
        }
    }

    @Test
    fun `production constants match spec fixed parameters`() {
        assertThat(Totp.STEP_SECONDS).isEqualTo(30L)
        assertThat(Totp.PROD_DIGITS).isEqualTo(6)
        assertThat(Totp.WINDOW).isEqualTo(1)
    }

    @Test
    fun `base32 encodes known vector with padding`() {
        assertThat(Base32.encode("foobar".toByteArray())).isEqualTo("MZXW6YTBOI======")
    }

    @Test
    fun `base32 encodes empty input to empty string`() {
        assertThat(Base32.encode(ByteArray(0))).isEqualTo("")
    }
}
