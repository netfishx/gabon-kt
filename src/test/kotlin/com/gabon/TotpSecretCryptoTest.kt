package com.gabon

import com.gabon.identity.internal.TotpKekProps
import com.gabon.identity.internal.TotpSecretCrypto
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.Base64
import javax.crypto.AEADBadTagException

/** AES-256-GCM secret 加密(spec §5.4):往返 + AAD 绑定拒绝跨用户/跨版本搬运 + IV 随机。 */
class TotpSecretCryptoTest {
    // 32 字节全零 KEK(测试专用，与 AbstractIntegrationTest 里注册的测试键同形态)。
    private val kekBase64 = Base64.getEncoder().encodeToString(ByteArray(32))
    private val crypto = TotpSecretCrypto(TotpKekProps(kekBase64 = kekBase64))
    private val plaintext = "JBSWY3DPEHPK3PXP".toByteArray()

    @Test
    fun `round trips plaintext through encrypt and decrypt`() {
        val blob = crypto.encrypt(adminId = 1L, plaintext = plaintext)
        val decrypted = crypto.decrypt(adminId = 1L, keyVersion = crypto.keyVersion, blob = blob)
        assertThat(decrypted).isEqualTo(plaintext)
    }

    @Test
    fun `rejects decryption under a different admin id`() {
        val blob = crypto.encrypt(adminId = 1L, plaintext = plaintext)
        assertThatThrownBy { crypto.decrypt(adminId = 2L, keyVersion = crypto.keyVersion, blob = blob) }
            .isInstanceOf(AEADBadTagException::class.java)
    }

    @Test
    fun `rejects decryption under a different key version`() {
        val blob = crypto.encrypt(adminId = 1L, plaintext = plaintext)
        val wrongVersion = (crypto.keyVersion + 1).toShort()
        assertThatThrownBy { crypto.decrypt(adminId = 1L, keyVersion = wrongVersion, blob = blob) }
            .isInstanceOf(AEADBadTagException::class.java)
    }

    @Test
    fun `same plaintext encrypts to different ciphertext due to random iv`() {
        val first = crypto.encrypt(adminId = 1L, plaintext = plaintext)
        val second = crypto.encrypt(adminId = 1L, plaintext = plaintext)
        assertThat(first).isNotEqualTo(second)
    }

    @Test
    fun `blob length is iv plus plaintext plus gcm tag`() {
        val blob = crypto.encrypt(adminId = 1L, plaintext = plaintext)
        val ivBytes = 12
        val tagBytes = 16
        assertThat(blob).hasSize(ivBytes + plaintext.size + tagBytes)
    }
}
