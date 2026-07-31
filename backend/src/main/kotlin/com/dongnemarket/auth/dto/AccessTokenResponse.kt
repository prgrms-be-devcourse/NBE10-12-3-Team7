package com.dongnemarket.auth.dto

/**
 * 원본 Java 클래스가 equals/hashCode 를 정의하지 않았으므로 data class 로 바꾸지 않는다
 * (값 기반 동등성·copy·componentN 이 새로 생기면 기존에 없던 동작이 추가된다).
 * 생성자는 private 을 유지하고 정적 팩토리 [of] 만 공개한다.
 *
 * `open class` · `open val` 은 원본 Java 의 JVM 표면을 맞추기 위한 것이다 — Java 클래스와 getter 는
 * 기본 non-final 이었다. 생성자가 private 이라 실제로 상속할 수는 없고(원본도 같다) 표면만 같아진다.
 * 근거는 `docs/kotlin-migration/auth-migration-notes.md` 「응답 DTO finality」절.
 */
open class AccessTokenResponse private constructor(
    open val accessToken: String?,
) {
    companion object {
        @JvmStatic
        fun of(accessToken: String?): AccessTokenResponse = AccessTokenResponse(accessToken)
    }
}
