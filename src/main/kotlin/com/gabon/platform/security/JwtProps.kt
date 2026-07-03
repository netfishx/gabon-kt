package com.gabon.platform.security

import org.springframework.boot.context.properties.ConfigurationProperties
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Base64

/** JWT 密钥注入:prod 走 secret-file(/run/secrets,SOPS 流程),测试走 secret-base64;二选一,fail fast。 */
@ConfigurationProperties("gabon.security.jwt")
data class JwtProps(
    val secretBase64: String? = null,
    val secretFile: String? = null,
    val accessTtl: Duration = DEFAULT_ACCESS_TTL,
) {
    fun secretBytes(): ByteArray {
        require((secretBase64 != null) xor (secretFile != null)) {
            "exactly one of gabon.security.jwt.secret-base64 / secret-file must be set"
        }
        return secretBase64?.let { Base64.getDecoder().decode(it) }
            ?: Files.readAllBytes(Path.of(secretFile!!))
    }

    companion object {
        /** spec §5.2:access token 默认 15 分钟。 */
        private val DEFAULT_ACCESS_TTL: Duration = Duration.ofMinutes(15)
    }
}
