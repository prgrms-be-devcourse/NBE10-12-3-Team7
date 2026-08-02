package com.dongnemarket.auth.client

import com.dongnemarket.auth.entity.OAuthProvider

/**
 * 소셜 로그인 제공자로부터 확인한 사용자 신원. [email]은 제공자가 검증 완료로 표시한 값만 여기
 * 담긴다(검증되지 않은 이메일은 클라이언트 구현체가 애초에 이 객체를 만들지 않고 예외를 던진다).
 *
 * `@JvmRecord` 로 두어 Java 호출부의 record 접근자(`provider()` `providerUserId()` `email()`)를
 * 그대로 유지한다 — 일반 data class 로 바꾸면 `getProvider()` 로 바뀌어 아직 Java 인 호출부가 전부 깨진다.
 *
 * 참조형 컴포넌트는 원본 Java record 와 동일하게 nullable 로 둔다(전환 완료 후 별도 패스에서 조인다).
 */
@JvmRecord
data class OAuthUserIdentity(
    val provider: OAuthProvider?,
    val providerUserId: String?,
    val email: String?,
)
