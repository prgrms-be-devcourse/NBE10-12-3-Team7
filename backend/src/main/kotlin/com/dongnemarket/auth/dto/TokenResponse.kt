package com.dongnemarket.auth.dto

/** 원본과 동일하게 private 생성자 + 정적 팩토리만 노출한다. data class 아님. */
class TokenResponse private constructor(
    val accessToken: String?,
    val refreshToken: String?,
) {
    companion object {
        @JvmStatic
        fun of(
            accessToken: String?,
            refreshToken: String?,
        ): TokenResponse = TokenResponse(accessToken, refreshToken)
    }
}
