package com.gabon.identity.internal.web

import com.gabon.identity.internal.TokenPair
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/** 边界校验(jakarta validation):失败 → MethodArgumentNotValidException → 400 /problems/validation。 */
private const val USERNAME_MIN = 3
private const val USERNAME_MAX = 32
private const val PASSWORD_MIN = 8
private const val PASSWORD_MAX = 128

data class RegisterRequest(
    @field:NotBlank
    @field:Size(min = USERNAME_MIN, max = USERNAME_MAX)
    val username: String,
    @field:NotBlank
    @field:Size(min = PASSWORD_MIN, max = PASSWORD_MAX)
    val password: String,
    val inviteCode: String?,
)

data class LoginRequest(
    @field:NotBlank
    val username: String,
    @field:NotBlank
    val password: String,
)

data class RefreshRequest(
    @field:NotBlank
    val refreshToken: String,
)

data class ChangePasswordRequest(
    @field:NotBlank
    val currentPassword: String,
    @field:NotBlank
    @field:Size(min = PASSWORD_MIN, max = PASSWORD_MAX)
    val newPassword: String,
)

data class TokenPairResponse(
    val accessToken: String,
    val refreshToken: String,
    val expiresInSeconds: Long,
) {
    companion object {
        fun from(pair: TokenPair): TokenPairResponse = TokenPairResponse(pair.accessToken, pair.refreshToken, pair.expiresInSeconds)
    }
}

data class MeResponse(
    val id: Long,
    val username: String,
)
