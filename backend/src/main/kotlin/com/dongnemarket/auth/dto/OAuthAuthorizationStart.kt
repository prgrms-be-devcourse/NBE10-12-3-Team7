package com.dongnemarket.auth.dto

/**
 * 프론트가 그대로 리다이렉트할 수 있는 완성된 인가 URL과 발급된 state, state 만료까지 남은 시간.
 *
 * `@JvmRecord` 로 두어 Java 호출부의 record 접근자를 그대로 유지한다.
 * 참조형 컴포넌트는 원본 Java record 와 동일하게 nullable 로 둔다.
 */
@JvmRecord
data class OAuthAuthorizationStart(
    val authorizationUrl: String?,
    val state: String?,
    val expiresInSeconds: Long,
)
