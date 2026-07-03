package com.gabon.platform.security

import java.security.SecureRandom
import java.time.Clock
import java.util.UUID

/** RFC 9562 UUIDv7:48-bit unix ms + version 7 + variant 10 + 74-bit random(spec §5.2 jti)。 */
object UuidV7 {
    private val random = SecureRandom()

    fun generate(clock: Clock): UUID {
        val ms = clock.millis()
        val randA = random.nextLong() and 0x0FFFL
        val msb = (ms shl 16) or 0x7000L or randA
        val lsb = (random.nextLong() and 0x3FFFFFFFFFFFFFFFL) or Long.MIN_VALUE // variant 10
        return UUID(msb, lsb)
    }
}
