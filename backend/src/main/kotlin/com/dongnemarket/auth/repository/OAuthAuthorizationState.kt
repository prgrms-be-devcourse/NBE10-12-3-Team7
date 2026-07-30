package com.dongnemarket.auth.repository

import com.dongnemarket.auth.entity.OAuthProvider
import java.time.Instant

/**
 * OAuth 인가 시작 시점에 발급되어 Redis에 저장되는 1회용 상태.
 *
 * `@JvmRecord` 로 두어 Java 호출부의 record 접근자를 그대로 유지한다.
 *
 * 모든 참조형 컴포넌트를 nullable 로 둔다 — 원본 Java record 는 null 검사를 하지 않았고, 특히
 * [oidcNonce] 는 카카오 흐름에서 실제로 null 이다(`AuthService.startAuthorization`, 그리고
 * `RedisOAuthStateRepository` 가 저장 시 빈 문자열로, 복원 시 다시 null 로 변환한다).
 * non-null 로 조이면 Kotlin 이 생성자에 null 검사를 넣어 카카오 로그인이 NPE 로 죽는다.
 * (backend.md「전환 시 규칙」— 공개 API 의 nullability 는 전환 완료 후 별도 패스에서 조인다.)
 *
 * @param oidcNonce Google ID Token의 nonce 클레임 검증용. 카카오는 서명 ID Token을 쓰지 않으므로 null.
 */
@JvmRecord
data class OAuthAuthorizationState(
    val provider: OAuthProvider?,
    val browserCorrelationHash: String?,
    val redirectUri: String?,
    val codeVerifier: String?,
    val oidcNonce: String?,
    val issuedAt: Instant?,
)
