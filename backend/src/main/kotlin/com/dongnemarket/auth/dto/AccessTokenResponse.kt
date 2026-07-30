package com.dongnemarket.auth.dto

/**
 * 원본 Java 클래스가 equals/hashCode 를 정의하지 않았으므로 data class 로 바꾸지 않는다
 * (값 기반 동등성·copy·componentN 이 새로 생기면 기존에 없던 동작이 추가된다).
 * 생성자는 private 을 유지하고 정적 팩토리 [of] 만 공개한다.
 */
class AccessTokenResponse private constructor(
    val accessToken: String?,
) {
    companion object {
        @JvmStatic
        fun of(accessToken: String?): AccessTokenResponse = AccessTokenResponse(accessToken)
    }
}
