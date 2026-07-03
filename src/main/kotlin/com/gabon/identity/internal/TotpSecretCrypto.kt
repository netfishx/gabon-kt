package com.gabon.identity.internal

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** KEK 注入:prod 走 kek-file(/run/secrets),测试走 kek-base64;二选一 fail fast(同 JwtProps 模式)。 */
@ConfigurationProperties("gabon.security.totp")
data class TotpKekProps(
    val kekBase64: String? = null,
    val kekFile: String? = null,
    val keyVersion: Short = 1,
) {
    fun kekBytes(): ByteArray {
        require((kekBase64 != null) xor (kekFile != null)) {
            "exactly one of gabon.security.totp.kek-base64 / kek-file must be set"
        }
        return kekBase64?.let { Base64.getDecoder().decode(it) } ?: Files.readAllBytes(Path.of(kekFile!!))
    }
}

/**
 * TOTP secret 应用层加密(spec §5.4):AES-256-GCM,IV 12B 随机,tag 128b,存 iv||ct||tag;
 * AAD = "admin_user:{id}:totp_secret:{key_version}"(防密文跨用户/跨版本搬运)。
 */
@Component
class TotpSecretCrypto(
    props: TotpKekProps,
) {
    private val kek = SecretKeySpec(props.kekBytes(), "AES")
    val keyVersion: Short = props.keyVersion
    private val random = SecureRandom()

    fun encrypt(
        adminId: Long,
        plaintext: ByteArray,
    ): ByteArray {
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, kek, GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(aad(adminId, keyVersion))
        return iv + cipher.doFinal(plaintext)
    }

    /** AAD 不匹配(密文搬运/版本错)→ AEADBadTagException 上抛,fail fast。 */
    fun decrypt(
        adminId: Long,
        keyVersion: Short,
        blob: ByteArray,
    ): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, kek, GCMParameterSpec(TAG_BITS, blob.copyOfRange(0, IV_BYTES)))
        cipher.updateAAD(aad(adminId, keyVersion))
        return cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
    }

    private fun aad(
        adminId: Long,
        version: Short,
    ) = "admin_user:$adminId:totp_secret:$version".toByteArray()

    companion object {
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
    }
}
