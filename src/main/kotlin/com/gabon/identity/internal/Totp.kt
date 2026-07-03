package com.gabon.identity.internal

import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * RFC 6238 TOTP(JDK Mac 实现,不自创算法;spec §5.4 定案)。
 * 生产参数:30s / 6 位 / HmacSHA1 / 窗口 [-1,0,+1]。digits 参数化:RFC Appendix B 向量按 8 位验算法。
 */
object Totp {
    const val STEP_SECONDS = 30L
    const val PROD_DIGITS = 6
    const val WINDOW = 1
    private val POW10 = intArrayOf(1, 10, 100, 1_000, 10_000, 100_000, 1_000_000, 10_000_000, 100_000_000)
    private const val OFFSET_MASK = 0x0f
    private const val BYTE_MASK = 0xff
    private const val MSB_MASK = 0x7f
    private const val SHIFT_BYTE3 = 24
    private const val SHIFT_BYTE2 = 16
    private const val SHIFT_BYTE1 = 8

    fun stepOf(epochSecond: Long): Long = epochSecond / STEP_SECONDS

    fun codeForStep(
        secret: ByteArray,
        step: Long,
        digits: Int,
        algorithm: String = "HmacSHA1",
    ): String {
        val mac = Mac.getInstance(algorithm)
        mac.init(SecretKeySpec(secret, "RAW"))
        val hash = mac.doFinal(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(step).array())
        val offset = hash.last().toInt() and OFFSET_MASK
        val binary =
            ((hash[offset].toInt() and MSB_MASK) shl SHIFT_BYTE3) or
                ((hash[offset + 1].toInt() and BYTE_MASK) shl SHIFT_BYTE2) or
                ((hash[offset + 2].toInt() and BYTE_MASK) shl SHIFT_BYTE1) or
                (hash[offset + 3].toInt() and BYTE_MASK)
        return (binary % POW10[digits]).toString().padStart(digits, '0')
    }
}

/** RFC 4648 Base32(otpauth URI 用;JDK 无内置,不为此引依赖)。 */
object Base32 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    private const val BITS_PER_CHAR = 5
    private const val BITS_PER_BYTE = 8
    private const val CHAR_MASK = 0x1fL
    private const val BYTE_MASK = 0xffL
    private const val BLOCK_CHARS = 8

    fun encode(data: ByteArray): String {
        val sb = StringBuilder()
        var buffer = 0L
        var bits = 0
        for (b in data) {
            buffer = (buffer shl BITS_PER_BYTE) or (b.toLong() and BYTE_MASK)
            bits += BITS_PER_BYTE
            while (bits >= BITS_PER_CHAR) {
                bits -= BITS_PER_CHAR
                sb.append(ALPHABET[((buffer shr bits) and CHAR_MASK).toInt()])
            }
        }
        if (bits > 0) sb.append(ALPHABET[((buffer shl (BITS_PER_CHAR - bits)) and CHAR_MASK).toInt()])
        while (sb.length % BLOCK_CHARS != 0) sb.append('=')
        return sb.toString()
    }
}
