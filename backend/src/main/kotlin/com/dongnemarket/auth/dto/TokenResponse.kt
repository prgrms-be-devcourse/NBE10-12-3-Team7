package com.dongnemarket.auth.dto

/**
 * 원본과 동일하게 private 생성자 + 정적 팩토리만 노출한다. data class 아님.
 * `open class` · `open val` 로 원본의 non-final 클래스·getter 표면을 맞춘다.
 */
open class TokenResponse private constructor(
    open val accessToken: String?,
    open val refreshToken: String?,
) {
    companion object {
        @JvmStatic
        fun of(
            accessToken: String?,
            refreshToken: String?,
        ): TokenResponse = TokenResponse(accessToken, refreshToken)
    }
}
