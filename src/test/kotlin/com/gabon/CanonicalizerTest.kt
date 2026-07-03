package com.gabon

import com.gabon.identity.internal.UsernameCanonicalizer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CanonicalizerTest {
    @Test
    fun `trims surrounding whitespace and lowercases`() {
        assertThat(UsernameCanonicalizer.canonicalize("  Alice ")).isEqualTo("alice")
    }

    @Test
    fun `lowercases all-caps input`() {
        assertThat(UsernameCanonicalizer.canonicalize("BOB")).isEqualTo("bob")
    }

    @Test
    fun `mixed case with internal spacing preserved`() {
        assertThat(UsernameCanonicalizer.canonicalize(" MiXeD CaSe ")).isEqualTo("mixed case")
    }

    /**
     * ROOT locale(非 Turkish)不做特殊 dotless-i 折叠:U+0130(İ)标准 Unicode 大小写映射
     * 拆成 'i' + U+0307(COMBINING DOT ABOVE)两个码点(JDK 实测,非猜测)。断言此固定行为,
     * 防止环境默认 locale 漂移导致 Turkish 特殊映射("istanbul"单字符 i,无 dot above)。
     */
    @Test
    fun `non-ascii input follows ROOT locale case mapping, not Turkish locale`() {
        // 实测值(JDK 25, jshell):U+0130 → U+0069('i') + U+0307(COMBINING DOT ABOVE),两个码点。
        assertThat(UsernameCanonicalizer.canonicalize("İstanbul")).isEqualTo("i̇stanbul")
    }

    @Test
    fun `empty and whitespace-only input canonicalizes to empty string`() {
        assertThat(UsernameCanonicalizer.canonicalize("   ")).isEqualTo("")
    }
}
