package com.gabon

import com.gabon.platform.security.UuidV7
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class UuidV7Test {
    private val base = Instant.parse("2026-07-03T00:00:00.123Z")

    private fun fixedClock(instant: Instant): Clock = Clock.fixed(instant, ZoneOffset.UTC)

    @Test
    fun `generates rfc 9562 version 7 variant 2`() {
        repeat(64) {
            val uuid = UuidV7.generate(fixedClock(base))
            assertThat(uuid.version()).isEqualTo(7)
            assertThat(uuid.variant()).isEqualTo(2)
        }
    }

    @Test
    fun `embeds unix millis in the high 48 bits`() {
        val uuid = UuidV7.generate(fixedClock(base))
        assertThat(uuid.mostSignificantBits ushr 16).isEqualTo(base.toEpochMilli())
    }

    @Test
    fun `timestamps are non-decreasing under an advancing clock`() {
        var previous = 0L
        for (offsetMs in 0L..50L) {
            val uuid = UuidV7.generate(fixedClock(base.plusMillis(offsetMs)))
            val timestamp = uuid.mostSignificantBits ushr 16
            assertThat(timestamp).isGreaterThanOrEqualTo(previous)
            previous = timestamp
        }
    }
}
